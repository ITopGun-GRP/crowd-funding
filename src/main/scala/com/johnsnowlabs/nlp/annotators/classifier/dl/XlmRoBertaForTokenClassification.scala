/*
 * Copyright 2017-2021 John Snow Labs
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

package com.johnsnowlabs.nlp.annotators.classifier.dl

import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.ml.tensorflow.sentencepiece.{ReadSentencePieceModel, SentencePieceWrapper, WriteSentencePieceModel}
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.nlp.serialization.MapFeature
import com.johnsnowlabs.nlp.util.io.{ExternalResource, ReadAs, ResourceHelper}

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{IntArrayParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.SparkSession

import java.io.File

/**
 * XlmRoBertaForTokenClassification can load XLM-RoBERTa Models with a token classification head on top (a linear layer on top of the hidden-states output)
 * e.g. for Named-Entity-Recognition (NER) tasks.
 *
 * Pretrained models can be loaded with `pretrained` of the companion object:
 * {{{
 * val tokenClassifier = XlmRoBertaForTokenClassification.pretrained()
 *   .setInputCols("token", "document")
 *   .setOutputCol("label")
 * }}}
 * The default model is `"xlm_roberta_base_token_classifier_conll03"`, if no name is provided.
 *
 * For available pretrained models please see the [[https://nlp.johnsnowlabs.com/models?task=Named+Entity+Recognition Models Hub]].
 *
 * and the [[https://github.com/JohnSnowLabs/spark-nlp/blob/master/src/test/scala/com/johnsnowlabs/nlp/annotators/classifier/dl/XlmRoBertaForTokenClassificationTestSpec.scala XlmRoBertaForTokenClassificationTestSpec]].
 * Models from the HuggingFace 🤗 Transformers library are also compatible with Spark NLP 🚀. The Spark NLP Workshop
 * example shows how to import them [[https://github.com/JohnSnowLabs/spark-nlp/discussions/5669]].
 *
 * ==Example==
 * {{{
 * import spark.implicits._
 * import com.johnsnowlabs.nlp.base._
 * import com.johnsnowlabs.nlp.annotator._
 * import org.apache.spark.ml.Pipeline
 *
 * val documentAssembler = new DocumentAssembler()
 *   .setInputCol("text")
 *   .setOutputCol("document")
 *
 * val tokenizer = new Tokenizer()
 *   .setInputCols("document")
 *   .setOutputCol("token")
 *
 * val tokenClassifier = XlmRoBertaForTokenClassification.pretrained()
 *   .setInputCols("token", "document")
 *   .setOutputCol("label")
 *   .setCaseSensitive(true)
 *
 * val pipeline = new Pipeline().setStages(Array(
 *   documentAssembler,
 *   tokenizer,
 *   tokenClassifier
 * ))
 *
 * val data = Seq("John Lenon was born in London and lived in Paris. My name is Sarah and I live in London").toDF("text")
 * val result = pipeline.fit(data).transform(data)
 *
 * result.select("label.result").show(false)
 * +------------------------------------------------------------------------------------+
 * |result                                                                              |
 * +------------------------------------------------------------------------------------+
 * |[B-PER, I-PER, O, O, O, B-LOC, O, O, O, B-LOC, O, O, O, O, B-PER, O, O, O, O, B-LOC]|
 * +------------------------------------------------------------------------------------+
 * }}}
 *
 * @see [[XlmRoBertaForTokenClassification]] for token-level classification
 * @see [[https://nlp.johnsnowlabs.com/docs/en/annotators Annotators Main Page]] for a list of transformer based classifiers
 * @param uid required uid for storing annotator to disk
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
 * */
class XlmRoBertaForTokenClassification(override val uid: String)
  extends AnnotatorModel[XlmRoBertaForTokenClassification]
    with HasBatchedAnnotate[XlmRoBertaForTokenClassification]
    with WriteTensorflowModel
    with WriteSentencePieceModel
    with HasCaseSensitiveProperties {

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  def this() = this(Identifiable.randomUID("XlmRoBertaForTokenClassification"))


  /**
   * Input Annotator Types: DOCUMENT, TOKEN
   *
   * @group anno
   */
  override val inputAnnotatorTypes: Array[String] = Array(AnnotatorType.DOCUMENT, AnnotatorType.TOKEN)

  /**
   * Output Annotator Types: WORD_EMBEDDINGS
   *
   * @group anno
   */
  override val outputAnnotatorType: AnnotatorType = AnnotatorType.NAMED_ENTITY

  /**
   * Labels used to decode predicted IDs back to string tags
   *
   * @group param
   * */
  val labels: MapFeature[String, Int] = new MapFeature(this, "labels")

  /** @group setParam */
  def setLabels(value: Map[String, Int]): this.type = set(labels, value)

  /**
   * Returns labels used to train this model
   */
  def getClasses: Array[String] = {
    $$(labels).keys.toArray
  }

  /** ConfigProto from tensorflow, serialized into byte array. Get with `config_proto.SerializeToString()`
   *
   * @group param
   * */
  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")

  /** @group setParam */
  def setConfigProtoBytes(bytes: Array[Int]): XlmRoBertaForTokenClassification.this.type = set(this.configProtoBytes, bytes)

  /** @group getParam */
  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  /** Max sentence length to process (Default: `128`)
   *
   * @group param
   * */
  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")

  /** @group setParam */
  def setMaxSentenceLength(value: Int): this.type = {
    require(value <= 512, "XLM-RoBERTa models do not support sequences longer than 512 because of trainable positional embeddings.")
    require(value >= 1, "The maxSentenceLength must be at least 1")
    set(maxSentenceLength, value)
    this
  }

  /** @group getParam */
  def getMaxSentenceLength: Int = $(maxSentenceLength)

  /**
   * It contains TF model signatures for the laded saved model
   *
   * @group param
   * */
  val signatures = new MapFeature[String, String](model = this, name = "signatures")

  /** @group setParam */
  def setSignatures(value: Map[String, String]): this.type = {
    if (get(signatures).isEmpty)
      set(signatures, value)
    this
  }

  /** @group getParam */
  def getSignatures: Option[Map[String, String]] = get(this.signatures)

  private var _model: Option[Broadcast[TensorflowXlmRoBertaClassification]] = None

  /** @group setParam */
  def setModelIfNotSet(spark: SparkSession, tensorflowWrapper: TensorflowWrapper, spp: SentencePieceWrapper): XlmRoBertaForTokenClassification = {
    if (_model.isEmpty) {
      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowXlmRoBertaClassification(
            tensorflowWrapper,
            spp,
            configProtoBytes = getConfigProtoBytes,
            tags = $$(labels),
            signatures = getSignatures
          )
        )
      )
    }

    this
  }

  /** @group getParam */
  def getModelIfNotSet: TensorflowXlmRoBertaClassification = _model.get.value


  /** Whether to lowercase tokens or not
   *
   * @group setParam
   * */
  override def setCaseSensitive(value: Boolean): this.type = {
    if (get(caseSensitive).isEmpty)
      set(this.caseSensitive, value)
    this
  }

  setDefault(
    batchSize -> 8,
    maxSentenceLength -> 128,
    caseSensitive -> true
  )

  /**
   * takes a document and annotations and produces new annotations of this annotator's annotation type
   *
   * @param batchedAnnotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
   * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
   */
  override def batchAnnotate(batchedAnnotations: Seq[Array[Annotation]]): Seq[Seq[Annotation]] = {
    val batchedTokenizedSentences: Array[Array[TokenizedSentence]] = batchedAnnotations.map(annotations =>
      TokenizedWithSentence.unpack(annotations).toArray
    ).toArray
    /*Return empty if the real tokens are empty*/
    if (batchedTokenizedSentences.nonEmpty) batchedTokenizedSentences.map(tokenizedSentences => {

      getModelIfNotSet.predict(
        tokenizedSentences,
        $(batchSize),
        $(maxSentenceLength),
        $(caseSensitive),
        $$(labels)
      )
    }) else {
      Seq(Seq.empty[Annotation])
    }
  }


  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModelV2(path, spark, getModelIfNotSet.tensorflowWrapper, "_xlm_roberta_classification", XlmRoBertaForTokenClassification.tfFile, configProtoBytes = getConfigProtoBytes)
    writeSentencePieceModel(path, spark, getModelIfNotSet.spp, "_xlmroberta", XlmRoBertaForTokenClassification.sppFile)
  }

}

trait ReadablePretrainedXlmRoBertaForTokenModel extends ParamsAndFeaturesReadable[XlmRoBertaForTokenClassification] with HasPretrained[XlmRoBertaForTokenClassification] {
  override val defaultModelName: Some[String] = Some("xlm_roberta_base_token_classifier_conll03")

  /** Java compliant-overrides */
  override def pretrained(): XlmRoBertaForTokenClassification = super.pretrained()

  override def pretrained(name: String): XlmRoBertaForTokenClassification = super.pretrained(name)

  override def pretrained(name: String, lang: String): XlmRoBertaForTokenClassification = super.pretrained(name, lang)

  override def pretrained(name: String, lang: String, remoteLoc: String): XlmRoBertaForTokenClassification = super.pretrained(name, lang, remoteLoc)
}

trait ReadXlmRoBertaForTokenTensorflowModel extends ReadTensorflowModel with ReadSentencePieceModel {
  this: ParamsAndFeaturesReadable[XlmRoBertaForTokenClassification] =>

  override val tfFile: String = "xlm_roberta_classification_tensorflow"
  override val sppFile: String = "xlmroberta_spp"

  def readTensorflow(instance: XlmRoBertaForTokenClassification, path: String, spark: SparkSession): Unit = {

    val tf = readTensorflowModel(path, spark, "_xlm_roberta_classification_tf", initAllTables = false)
    val spp = readSentencePieceModel(path, spark, "_xlmroberta_spp", sppFile)
    instance.setModelIfNotSet(spark, tf, spp)
  }

  addReader(readTensorflow)

  def loadSavedModel(tfModelPath: String, spark: SparkSession): XlmRoBertaForTokenClassification = {
    val f = new File(tfModelPath)
    val savedModel = new File(tfModelPath, "saved_model.pb")
    require(f.exists, s"Folder $tfModelPath not found")
    require(f.isDirectory, s"File $tfModelPath is not folder")
    require(
      savedModel.exists(),
      s"savedModel file saved_model.pb not found in folder $tfModelPath"
    )
    val sppModelPath = tfModelPath + "/assets"
    val sppModel = new File(sppModelPath, "sentencepiece.bpe.model")
    require(sppModel.exists(), s"SentencePiece model sentencepiece.bpe.model not found in folder $sppModelPath")

    val labelsPath = new File(tfModelPath + "/assets", "labels.txt")
    require(labelsPath.exists(), s"Labels file labels.txt not found in folder $tfModelPath/assets/")

    val labelsResource = new ExternalResource(labelsPath.getAbsolutePath, ReadAs.TEXT, Map("format" -> "text"))
    val labels = ResourceHelper.parseLines(labelsResource).zipWithIndex.toMap

    val (wrapper, signatures) = TensorflowWrapper.read(tfModelPath, zipped = false, useBundle = true)
    val spp = SentencePieceWrapper.read(sppModel.toString)

    val _signatures = signatures match {
      case Some(s) => s
      case None => throw new Exception("Cannot load signature definitions from model!")
    }

    /** the order of setSignatures is important if we use getSignatures inside setModelIfNotSet */
    new XlmRoBertaForTokenClassification()
      .setLabels(labels)
      .setSignatures(_signatures)
      .setModelIfNotSet(spark, wrapper, spp)
  }
}

/**
 * This is the companion object of [[XlmRoBertaForTokenClassification]]. Please refer to that class for the documentation.
 */
object XlmRoBertaForTokenClassification extends ReadablePretrainedXlmRoBertaForTokenModel with ReadXlmRoBertaForTokenTensorflowModel
