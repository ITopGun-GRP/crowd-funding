package com.johnsnowlabs.nlp

import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.functions.{col, udf, explode, array}
import scala.reflect.runtime.universe._

object functions {

  implicit class FilterAnnotations(dataset: DataFrame) {
    def filterByAnnotations(dataset: DataFrame, column: String, function: Seq[Annotation] => Boolean): DataFrame = {
      val meta = dataset.schema(column).metadata
      val func = udf {
        annotatorProperties: Seq[Row] =>
          function(annotatorProperties.map(Annotation(_)))
      }
      dataset.filter(func(col(column)).as(column, meta))
    }
  }

  implicit class MapAnnotations(dataset: DataFrame) {
    def mapAnnotations[T: TypeTag](dataset: DataFrame, column: String, outputCol: String, function: Seq[Annotation] => T): DataFrame = {
      val meta = dataset.schema(column).metadata
      val func = udf {
        annotatorProperties: Seq[Row] =>
          function(annotatorProperties.map(Annotation(_)))
      }
      dataset.withColumn(outputCol, func(col(column)).as(outputCol, meta))
    }
  }

  implicit class ExplodeAnnotations(dataset: DataFrame) {
    def explodeAnnotations[T: TypeTag](column: String, outputCol:String): DataFrame = {
      val meta = dataset.schema(column).metadata
      dataset.
        withColumn(column, explode(col(column))).
        withColumn(column, array(col(outputCol)).as(outputCol, meta))
      }
  }


}
