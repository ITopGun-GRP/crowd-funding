package com.johnsnowlabs.nlp.embeddings

import java.io.File

import com.johnsnowlabs.ml.tensorflow._
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.annotators.common._
import com.johnsnowlabs.storage.HasStorageRef
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.param.{IntArrayParam, IntParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.johnsnowlabs.ml.tensorflow.sentencepiece._

/** XlnetEmbeddings (XLNet): Generalized Autoregressive Pretraining for Language Understanding
  *
  * Note that this is a very computationally expensive module compared to word embedding modules that only perform embedding lookups.
  * The use of an accelerator is recommended.
  *
  */
class XlnetEmbeddings(override val uid: String) extends
  AnnotatorModel[XlnetEmbeddings]
  with WriteTensorflowModel
  with WriteSentencePieceModel
  with HasEmbeddingsProperties
  with HasStorageRef
  with HasCaseSensitiveProperties {

  /** Annotator reference id. Used to identify elements in metadata or to refer to this annotator type */
  override val inputAnnotatorTypes: Array[String] = Array(AnnotatorType.DOCUMENT, AnnotatorType.TOKEN)
  override val outputAnnotatorType: AnnotatorType = AnnotatorType.WORD_EMBEDDINGS

  val batchSize = new IntParam(this, "batchSize", "Batch size. Large values allows faster processing but requires more memory.")
  val configProtoBytes = new IntArrayParam(this, "configProtoBytes", "ConfigProto from tensorflow, serialized into byte array. Get with config_proto.SerializeToString()")
  val maxSentenceLength = new IntParam(this, "maxSentenceLength", "Max sentence length to process")

  private var _model: Option[Broadcast[TensorflowXlnet]] = None

  def this() = this(Identifiable.randomUID("XLNET_EMBEDDINGS"))

  def setBatchSize(size: Int): this.type = {
    if (get(batchSize).isEmpty)
      set(batchSize, size)
    this
  }

  override def setDimension(value: Int): this.type = {
    if (get(dimension).isEmpty)
      set(this.dimension, value)
    this

  }

  def setMaxSentenceLength(value: Int): this.type = {
    if(get(maxSentenceLength).isEmpty)
      set(maxSentenceLength, value)
    this
  }

  def getMaxSentenceLength: Int = $(maxSentenceLength)

  def setConfigProtoBytes(bytes: Array[Int]): XlnetEmbeddings.this.type = set(this.configProtoBytes, bytes)

  def getConfigProtoBytes: Option[Array[Byte]] = get(this.configProtoBytes).map(_.map(_.toByte))

  setDefault(
    batchSize -> 32,
    dimension -> 768,
    maxSentenceLength -> 128,
    caseSensitive -> true
  )

  def setModelIfNotSet(spark: SparkSession, tensorflow: TensorflowWrapper, spp: SentencePieceWrapper): this.type = {
    if (_model.isEmpty) {

      _model = Some(
        spark.sparkContext.broadcast(
          new TensorflowXlnet(
            tensorflow,
            spp,
            configProtoBytes = getConfigProtoBytes
          )
        )
      )
    }

    this
  }
  def getModelIfNotSet: TensorflowXlnet = _model.get.value

  /**
    * takes a document and annotations and produces new annotations of this annotator's annotation type
    *
    * @param annotations Annotations that correspond to inputAnnotationCols generated by previous annotators if any
    * @return any number of annotations processed for every input annotation. Not necessary one to one relationship
    */
  override def annotate(annotations: Seq[Annotation]): Seq[Annotation] = {
    val tokenizedSentences = TokenizedWithSentence.unpack(annotations)

    /*Return empty if the real tokens are empty*/
    if(tokenizedSentences.nonEmpty) {
      val embeddings = getModelIfNotSet.calculateEmbeddings(
        tokenizedSentences,
        $(batchSize),
        $(maxSentenceLength),
        $(caseSensitive)
      )
      WordpieceEmbeddingsSentence.pack(embeddings)
    } else {
      Seq.empty[Annotation]
    }
  }

  override def onWrite(path: String, spark: SparkSession): Unit = {
    super.onWrite(path, spark)
    writeTensorflowModel(path, spark, getModelIfNotSet.tensorflow, "_xlnet", XlnetEmbeddings.tfFile, configProtoBytes = getConfigProtoBytes)
    writeSentencePieceModel(path, spark, getModelIfNotSet.spp, "_xlnet",  XlnetEmbeddings.sppFile)

  }

  override protected def afterAnnotate(dataset: DataFrame): DataFrame = {
    dataset.withColumn(getOutputCol, wrapEmbeddingsMetadata(dataset.col(getOutputCol), $(dimension), Some($(storageRef))))
  }

}

trait ReadablePretrainedXlnetModel extends ParamsAndFeaturesReadable[XlnetEmbeddings] with HasPretrained[XlnetEmbeddings] {
  override val defaultModelName: Some[String] = Some("xlnet_base_cased")
  /** Java compliant-overrides */
  override def pretrained(): XlnetEmbeddings = super.pretrained()
  override def pretrained(name: String): XlnetEmbeddings = super.pretrained(name)
  override def pretrained(name: String, lang: String): XlnetEmbeddings = super.pretrained(name, lang)
  override def pretrained(name: String, lang: String, remoteLoc: String): XlnetEmbeddings = super.pretrained(name, lang, remoteLoc)
}

trait ReadXlnetTensorflowModel extends ReadTensorflowModel with ReadSentencePieceModel {
  this: ParamsAndFeaturesReadable[XlnetEmbeddings] =>

  override val tfFile: String = "xlnet_tensorflow"
  override val sppFile: String = "xlnet_spp"

  def readTensorflow(instance: XlnetEmbeddings, path: String, spark: SparkSession): Unit = {
    val tf = readTensorflowModel(path, spark, "_xlnet_tf", initAllTables = true)
    val spp = readSentencePieceModel(path, spark, "_xlnet_spp" )
    instance.setModelIfNotSet(spark, tf, spp)
  }

  addReader(readTensorflow)

  def loadSavedModel(folder: String, spark: SparkSession): XlnetEmbeddings = {

    val f = new File(folder)
    val sppModelPath = folder+"/assets"
    val savedModel = new File(folder, "saved_model.pb")
    val sppModel = new File(sppModelPath, "spiece.model")

    require(f.exists, s"Folder $folder not found")
    require(f.isDirectory, s"File $folder is not folder")
    require(
      savedModel.exists(),
      s"savedModel file saved_model.pb not found in folder $folder"
    )
    require(sppModel.exists(), s"SentencePiece model spiece.model not found in folder $sppModelPath")

    val wrapper = TensorflowWrapper.read(folder, zipped = false, useBundle = true, tags = Array("serve"), initAllTables = true)
    val spp = SentencePieceWrapper.read(sppModel.toString)

    val xlnet = new XlnetEmbeddings()
      .setModelIfNotSet(spark, wrapper, spp)
    xlnet
  }
}


object XlnetEmbeddings extends ReadablePretrainedXlnetModel with ReadXlnetTensorflowModel with ReadSentencePieceModel
