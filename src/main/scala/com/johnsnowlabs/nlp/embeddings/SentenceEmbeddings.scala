package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.AnnotatorType.{DOCUMENT, SENTENCE_EMBEDDINGS, WORD_EMBEDDINGS}
import com.johnsnowlabs.nlp.annotators.common.{SentenceSplit, WordpieceEmbeddingsSentence}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.ml.param.{IntParam, Param}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.sql.DataFrame

/** This annotator converts the results from WordEmbeddings, BertEmbeddings, or ElmoEmbeddings into sentence or document embeddings by either summing up or averaging all the word embeddings in a sentence or a document (depending on the inputCols).
  *
  * See [[https://github.com/JohnSnowLabs/spark-nlp/blob/master/src/test/scala/com/johnsnowlabs/nlp/embeddings/SentenceEmbeddingsTestSpec.scala]] for further reference on how to use this API.
  *
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
  * @groupdesc Parameters A list of (hyper-)parameter keys this annotator can take. Users can set and get the parameter values through setters and getters, respectively.
  **/
class SentenceEmbeddings(override val uid: String)
  extends AnnotatorModel[SentenceEmbeddings]
    with HasEmbeddingsProperties
    with HasStorageRef {

  /** Output annotator type : SENTENCE_EMBEDDINGS
    *
    * @group anno
    **/
  override val outputAnnotatorType: AnnotatorType = SENTENCE_EMBEDDINGS
  /** Input annotator type : DOCUMENT, WORD_EMBEDDINGS
    *
    * @group anno
    **/
  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT, WORD_EMBEDDINGS)
  /** Number of embedding dimensions
    *
    * @group param
    **/
  override val dimension = new IntParam(this, "dimension", "Number of embedding dimensions")

  /** Number of embedding dimensions
    *
    * @group getParam
    **/
  override def getDimension: Int = $(dimension)

  /** Choose how you would like to aggregate Word Embeddings to Sentence Embeddings: AVERAGE or SUM
    *
    * @group param
    **/
  val poolingStrategy = new Param[String](this, "poolingStrategy", "Choose how you would like to aggregate Word Embeddings to Sentence Embeddings: AVERAGE or SUM")

  /** Choose how you would like to aggregate Word Embeddings to Sentence Embeddings: AVERAGE or SUM
    *
    * @group setParam
    **/
  def setPoolingStrategy(strategy: String): this.type = {
    strategy.toLowerCase() match {
      case "average" => set(poolingStrategy, "AVERAGE")
      case "sum" => set(poolingStrategy, "SUM")
      case _ => throw new MatchError("poolingStrategy must be either AVERAGE or SUM")
    }
  }

  setDefault(
    inputCols -> Array(DOCUMENT, WORD_EMBEDDINGS),
    outputCol -> "sentence_embeddings",
    poolingStrategy -> "AVERAGE",
    dimension -> 100
  )

  /** Internal constructor to submit a random UID */
  def this() = this(Identifiable.randomUID("SENTENCE_EMBEDDINGS"))

  private def calculateSentenceEmbeddings(matrix : Array[Array[Float]]):Array[Float] = {
    val res = Array.ofDim[Float](matrix(0).length)
    setDimension(matrix(0).length)

    matrix(0).indices.foreach {
      j =>
        matrix.indices.foreach {
          i =>
            res(j) += matrix(i)(j)
        }
        if($(poolingStrategy) == "AVERAGE")
          res(j) /= matrix.length
    }
    res
  }

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val sentences = SentenceSplit.unpack(annotations)

    val embeddingsSentences = WordpieceEmbeddingsSentence.unpack(annotations)

    sentences.map { sentence =>

      val sentenceEmbeddings = embeddingsSentences.flatMap {
        case (tokenEmbedding) =>
          val allEmbeddings = tokenEmbedding.tokens.map { token =>
            token.embeddings
          }
          calculateSentenceEmbeddings(allEmbeddings)
      }.toArray

      Annotation(
        annotatorType = outputAnnotatorType,
        begin = sentence.start,
        end = sentence.end,
        result = sentence.content,
        metadata = Map("sentence" -> sentence.index.toString,
          "token" -> sentence.content,
          "pieceId" -> "-1",
          "isWordStart" -> "true"
        ),
        embeddings = sentenceEmbeddings
      )
    }
  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapSentenceEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension), Some($(storageRef))))
  }
}

object SentenceEmbeddings extends DefaultParamsReadable[SentenceEmbeddings]
