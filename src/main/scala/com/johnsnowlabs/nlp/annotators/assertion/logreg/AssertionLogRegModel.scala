package com.johnsnowlabs.nlp.annotators.assertion.logreg

import com.johnsnowlabs.nlp.AnnotatorType.{ASSERTION, DOCUMENT}
import com.johnsnowlabs.nlp._
import com.johnsnowlabs.nlp.embeddings.WordEmbeddings
import com.johnsnowlabs.nlp.serialization.MapFeature
import org.apache.spark.ml.classification.LogisticRegressionModel
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable, MLReader, MLWriter}
import org.apache.spark.sql.{DataFrame, Dataset}
import org.apache.hadoop.fs.Path
import org.apache.spark.ml.param.{IntParam, Param, ParamMap}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.functions._

import scala.collection.immutable.Map
import scala.collection.mutable

/**
  * Created by jose on 22/11/17.
  */

class AssertionLogRegModel(override val uid: String = Identifiable.randomUID("ASSERTION")) extends RawAnnotator[AssertionLogRegModel]
    with Windowing with Serializable with TransformModelSchema with HasWordEmbeddings  {

  override val tokenizer: Tokenizer = new SimpleTokenizer
  override val annotatorType: AnnotatorType = ASSERTION
  override val requiredAnnotatorTypes = Array(DOCUMENT)
  override lazy val wordVectors: Option[WordEmbeddings] = embeddings

  val beforeParam = new IntParam(this, "beforeParam", "Length of the context before the target")
  val afterParam = new IntParam(this, "afterParam", "Length of the context after the target")

  // the document where we're extracting the assertion
  val document = new Param[String](this, "document", "Column with the text to be analyzed")
  // the target term, that must appear capitalized in the document, e.g., 'diabetes'
  val target = new Param[String](this, "target", "Column with the target to analyze")
  val startParam = new Param[String](this, "startParam", "Column that contains the token number for the start of the target")
  val endParam = new Param[String](this, "endParam", "Column that contains the token number for the end of the target")

  var model: Param[LogisticRegressionModel] = new Param[LogisticRegressionModel](this, "logistic regression", "trained lr for prediction")
  var labelMap: MapFeature[Double, String] = new MapFeature[Double, String](this, "labels")

  override lazy val (before, after) = (getOrDefault(beforeParam), getOrDefault(afterParam))

  setDefault(
     beforeParam -> 11,
     afterParam -> 13
    )

  def setBefore(before: Int) = set(beforeParam, before)
  def setAfter(after: Int) = set(afterParam, after)
  def setStart(start: String) = set(startParam, start)
  def setEnd(end: String) = set(endParam, end)
  def setTargetCol(target: String) = set(target, target)

  override final def transform(dataset: Dataset[_]): DataFrame = {
    require(validate(dataset.schema), s"Missing annotators in pipeline. Make sure the following are present: " +
      s"${requiredAnnotatorTypes.mkString(", ")}")

    import dataset.sqlContext.implicits._

    /* apply UDF to fix the length of each document */
    val processed = dataset.toDF.
      withColumn("text", extractTextUdf(col(getOrDefault(document)))).
      withColumn("features", applyWindowUdf($"text",
        col(getOrDefault(target)),
        col(getOrDefault(startParam)),
        col(getOrDefault(endParam))))

    $(model).transform(processed).withColumn(getOutputCol, packAnnotations($"text", $"target", $"start", $"end", $"prediction"))
  }

  private def packAnnotations = udf { (text: String, s: Int, e: Int, prediction: Double) =>
    val tokens = text.split(" ").filter(_!="")

    /* convert start and end are indexes in the doc string */
    val start = tokens.slice(0, s).map(_.length).sum +
      tokens.slice(0, s).size // account for spaces
    val end = start + tokens.slice(s, e + 1).map(_.length).sum +
      tokens.slice(s, e + 1).size  - 2 // account for spaces

    val annotation = Annotation("assertion", start, end, $$(labelMap)(prediction), Map())
    Seq(annotation)
  }

  def setModel(m: LogisticRegressionModel): this.type = set(model, m)

  def setLabelMap(labelMappings: Map[String, Double]): this.type = set(labelMap, labelMappings.map(_.swap))

  /* send this to common place */
  def extractTextUdf = udf { document:mutable.WrappedArray[GenericRowWithSchema] =>
    document.head.getString(3)
  }

  /** requirement for annotators copies */
  override def copy(extra: ParamMap): AssertionLogRegModel = defaultCopy(extra)
}

object AssertionLogRegModel extends DefaultParamsReadable[AssertionLogRegModel] {
  def apply(): AssertionLogRegModel = new AssertionLogRegModel()
  override def read: MLReader[AssertionLogRegModel] = new AssertionModelReader(super.read)

  class AssertionModelReader(baseReader: MLReader[AssertionLogRegModel]) extends MLReader[AssertionLogRegModel] {
    override def load(path: String): AssertionLogRegModel = {
      val instance = baseReader.load(path)
      val modelPath = new Path(path, "model").toString
      val loaded = LogisticRegressionModel.read.load(modelPath)

      val labelsPath = new Path(path, "labels").toString
      val labelsLoaded = sparkSession.sqlContext.read.format("parquet")
        .load(labelsPath)
        .collect
        .map(_.toString)

      val dict = labelsLoaded
        .map {line =>
          val items = line.split(":")
          (items(0).drop(1).toDouble, items(1).dropRight(1))
        }
        .toMap

      instance
        .setLabelMap(dict.map(_.swap))
        .setModel(loaded)
      instance.deserializeEmbeddings(path, sparkSession.sparkContext)
      instance
    }
  }

  class AssertionModelWriter(model: AssertionLogRegModel, baseWriter: MLWriter) extends MLWriter {

    override protected def saveImpl(path: String): Unit = {

      model.serializeEmbeddings(path, sparkSession.sparkContext)
    }
  }
}
