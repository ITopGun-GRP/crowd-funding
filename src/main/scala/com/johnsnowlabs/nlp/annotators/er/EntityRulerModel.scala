/*
 * Copyright 2017-2022 John Snow Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.johnsnowlabs.nlp.annotators.er

import com.johnsnowlabs.nlp.AnnotatorType.{CHUNK, DOCUMENT, TOKEN}
import com.johnsnowlabs.nlp.annotators.common.{IndexedToken, Sentence, SentenceSplit, TokenizedSentence, TokenizedWithSentence}
import com.johnsnowlabs.nlp.serialization.StructFeature
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel, HasPretrained, HasSimpleAnnotate}
import com.johnsnowlabs.storage.Database.{ENTITY_PATTERNS, ENTITY_REGEX_PATTERNS, Name}
import com.johnsnowlabs.storage._
import org.apache.spark.ml.param.{BooleanParam, StringArrayParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}

/**
 * Instantiated model of the [[EntityRulerApproach]].
 * For usage and examples see the documentation of the main class.
 *
 * @param uid internally renquired UID to make it writable
 * @groupname anno Annotator types
 * @groupdesc anno Required input and expected output annotator types
 * @groupname Ungrouped Members
 * @groupname param Parameters
 * @groupname setParam Parameter setters
 * @groupname getParam Parameter getters
 * @groupname Ungrouped Members
 * @groupprio param  1
 * @groupprio anno  2
 * @groupprio Ungrouped 3
 * @groupprio setParam  4
 * @groupprio getParam  5
 * @groupdesc param A list of (hyper-)parameter keys this annotator can take. Users can set and get the parameter values through setters and getters, respectively.
 */
class EntityRulerModel(override val uid: String) extends AnnotatorModel[EntityRulerModel]
  with HasSimpleAnnotate[EntityRulerModel] with HasStorageModel {

  def this() = this(Identifiable.randomUID("ENTITY_RULER"))

  private val logger: Logger = LoggerFactory.getLogger("Credentials")

  private[er] val enablePatternRegex = new BooleanParam(this, "enablePatternRegex",
    "Enables regex pattern match")

  private[er] val useStorage = new BooleanParam(this, "useStorage", "Whether to use RocksDB storage to serialize patterns")

  private[er] val regexEntities = new StringArrayParam(this, "regexEntities", "entities defined in regex patterns")

  private[er] val entityRulerFeatures: StructFeature[EntityRulerFeatures] =
    new StructFeature[EntityRulerFeatures](this, "Structure to store data when RocksDB is not used")

  private[er] val sentenceMatch = new BooleanParam(this, "sentenceMatch",
    "Whether to find match at sentence level. True: sentence level. False: token level")

  private[er] def setEnablePatternRegex(value: Boolean): this.type = set(enablePatternRegex, value)

  private[er] def setRegexEntities(value: Array[String]): this.type = set(regexEntities, value)

  private[er] def setEntityRulerFeatures(value: EntityRulerFeatures): this.type = set(entityRulerFeatures, value)

  private[er] def setUseStorage(value: Boolean): this.type = set(useStorage, value)

  private[er] def setSentenceMatch(value: Boolean): this.type = set(sentenceMatch, value)

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  val inputAnnotatorTypes: Array[String] = Array(DOCUMENT, TOKEN)
  val outputAnnotatorType: AnnotatorType = CHUNK

  /**
   * takes a document and annotations and produces new annotations of this annotator's annotation type
   *
   * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
   * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
   */
  def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {

    if ($(sentenceMatch)) getAnnotationBySentence(annotations) else getAnnotationByToken(annotations)

  }

  private def getAnnotationByToken(annotations: Seq[Annotation]): Seq[Annotation] = {
    val tokenizedWithSentences = TokenizedWithSentence.unpack(annotations)
    var annotatedEntities: Seq[Annotation] = Seq()

    if ($(enablePatternRegex)) {
      val regexPatternsReader = if ($(useStorage))
        Some(getReader(Database.ENTITY_REGEX_PATTERNS).asInstanceOf[RegexPatternsReader]) else None
      annotatedEntities = annotateEntitiesFromRegexPatterns(tokenizedWithSentences, regexPatternsReader)
    } else {
      val patternsReader = if ($(useStorage)) Some(getReader(Database.ENTITY_PATTERNS).asInstanceOf[PatternsReader]) else None
      annotatedEntities = annotateEntitiesFromPatterns(tokenizedWithSentences, patternsReader)
    }

    annotatedEntities
  }

  private def getAnnotationBySentence(annotations: Seq[Annotation]): Seq[Annotation] = {
    val patternsReader = if ($(useStorage)) Some(getReader(Database.ENTITY_REGEX_PATTERNS).asInstanceOf[RegexPatternsReader]) else None
    val sentences = SentenceSplit.unpack(annotations)
    annotateEntitiesFromPatternsBySentence(sentences, patternsReader)
  }

  private def annotateEntitiesFromRegexPatterns(tokenizedWithSentences: Seq[TokenizedSentence],
                                                regexPatternsReader: Option[RegexPatternsReader]): Seq[Annotation] = {

    val annotatedEntities = tokenizedWithSentences.flatMap { tokenizedWithSentence =>
      tokenizedWithSentence.indexedTokens.flatMap { indexedToken =>
        val entity = getMatchedEntity(indexedToken.token, regexPatternsReader)
        if (entity.isDefined) {
          val entityMetadata = getEntityMetadata(entity)
          Some(Annotation(CHUNK, indexedToken.begin, indexedToken.end, indexedToken.token,
            entityMetadata ++ Map("sentence" -> tokenizedWithSentence.sentenceIndex.toString)))
        } else None
      }
    }

    annotatedEntities
  }

  private def getMatchedEntity(token: String, regexPatternsReader: Option[RegexPatternsReader]): Option[String] = {

    val matchesByEntity = $(regexEntities).flatMap { regexEntity =>
      val regexPatterns: Option[Seq[String]] = regexPatternsReader match {
        case Some(rpr) => rpr.lookup(regexEntity)
        case None => $$(entityRulerFeatures).regexPatterns.get(regexEntity)
      }
      if (regexPatterns.isDefined) {
        val matches = regexPatterns.get.flatMap(regexPattern => regexPattern.r.findFirstIn(token))
        if (matches.nonEmpty) Some(regexEntity) else None
      } else None
    }.toSeq

    if (matchesByEntity.size > 1) {
      logger.warn("More than one entity found. Sending the first element of the array")
    }

    matchesByEntity.headOption
  }

  private def getMatchedEntityBySentence(sentence: Sentence, regexPatternsReader: Option[RegexPatternsReader]):
  Array[(IndexedToken, String)] = {

    val matchesByEntity = $(regexEntities).flatMap { regexEntity =>
      val regexPatterns: Option[Seq[String]] = regexPatternsReader match {
        case Some(rpr) => rpr.lookup(regexEntity)
        case None => $$(entityRulerFeatures).regexPatterns.get(regexEntity)
      }
      if (regexPatterns.isDefined) {

        val resultMatches = regexPatterns.get.flatMap{ regexPattern =>
          val matchedResult = regexPattern.r.findFirstMatchIn(sentence.content)
          if (matchedResult.isDefined) {
            val begin = matchedResult.get.start + sentence.start
            val end = matchedResult.get.end + sentence.start - 1
            Some(matchedResult.get.toString(), begin, end, regexEntity)
          } else None
        }

        val filteredMatches = filterOverlappingMatches(resultMatches)
        if (filteredMatches.nonEmpty) Some(filteredMatches) else None
      } else None
    }.flatten.sortBy(_._2)

    matchesByEntity.map(matches => (IndexedToken(matches._1, matches._2, matches._3), matches._4))
  }

  private def filterOverlappingMatches(matches: Seq[(String, Int, Int, String)]): Seq[(String, Int, Int, String)] = {

    val groupByBegin = matches.groupBy(_._2).filter(_._2.length > 1)
    val groupByEnd = matches.groupBy(_._3).filter(_._2.length > 1)

    val overlappingBegin = groupByBegin.flatMap(ngram => ngram._2.sortBy(_._1.length)).dropRight(1)
    val overlappingEnd = groupByEnd.flatMap(ngram => ngram._2.sortBy(_._1.length)).dropRight(1)
    val overlappingMatches = (overlappingBegin ++ overlappingEnd).toSeq.distinct

    matches diff overlappingMatches
  }

  private def annotateEntitiesFromPatterns(tokenizedWithSentences: Seq[TokenizedSentence],
                                           patternsReader: Option[PatternsReader]): Seq[Annotation] = {

    val annotatedEntities = tokenizedWithSentences.flatMap { tokenizedWithSentence =>
      tokenizedWithSentence.indexedTokens.flatMap { indexedToken =>
        val labelData: Option[String] = patternsReader match {
          case Some(pr) => pr.lookup(indexedToken.token)
          case None => $$(entityRulerFeatures).patterns.get(indexedToken.token)
        }
        val annotation = if (labelData.isDefined) {
          val entityMetadata = getEntityMetadata(labelData)
          Some(Annotation(CHUNK, indexedToken.begin, indexedToken.end, indexedToken.token,
            entityMetadata ++ Map("sentence" -> tokenizedWithSentence.sentenceIndex.toString)))
        } else None
        annotation
      }
    }

    annotatedEntities
  }

  private def annotateEntitiesFromPatternsBySentence(sentences: Seq[Sentence],
                                                     patternsReader: Option[RegexPatternsReader]): Seq[Annotation] = {

    val annotatedEntities = sentences.flatMap{ sentence =>
      val matchedEntities = getMatchedEntityBySentence(sentence, patternsReader)
      matchedEntities.map{ case (indexedToken, label) =>
        val entityMetadata = getEntityMetadata(Some(label))
        Annotation(CHUNK, indexedToken.begin, indexedToken.end, indexedToken.token,
          entityMetadata ++ Map("sentence" -> sentence.index.toString))
      }
    }
    annotatedEntities
  }

  private def getEntityMetadata(labelData: Option[String]): Map[String, String] = {

    val entityMetadata = labelData.get.split(",").zipWithIndex.flatMap { case (metadata, index) =>
      if (index == 0) {
        Map("entity" -> metadata)
      } else Map("id" -> metadata)
    }.toMap

    entityMetadata
  }

  override def deserializeStorage(path: String, spark: SparkSession): Unit = {
    if ($(useStorage)) {
      super.deserializeStorage(path: String, spark: SparkSession)
    }
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    if ($(useStorage)) {
      super.onWrite(path, spark)
    }
  }

  protected val databases: Array[Name] = EntityRulerModel.databases

  protected def createReader(database: Name, connection: RocksDBConnection): StorageReader[_] = {
    database match {
      case Database.ENTITY_PATTERNS => new PatternsReader(connection)
      case Database.ENTITY_REGEX_PATTERNS => new RegexPatternsReader(connection)
    }
  }
}

trait ReadablePretrainedEntityRuler extends StorageReadable[EntityRulerModel] with HasPretrained[EntityRulerModel] {

  override val databases: Array[Name] = Array(ENTITY_PATTERNS, ENTITY_REGEX_PATTERNS)

  override val defaultModelName: Option[String] = None

  override def pretrained(): EntityRulerModel = super.pretrained()

  override def pretrained(name: String): EntityRulerModel = super.pretrained(name)

  override def pretrained(name: String, lang: String): EntityRulerModel = super.pretrained(name, lang)

  override def pretrained(name: String, lang: String, remoteLoc: String): EntityRulerModel = super.pretrained(name, lang, remoteLoc)

}

object EntityRulerModel extends ReadablePretrainedEntityRuler