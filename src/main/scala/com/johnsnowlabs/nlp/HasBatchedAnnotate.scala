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

package com.johnsnowlabs.nlp

import org.apache.spark.ml.Model
import org.apache.spark.ml.param.IntParam
import org.apache.spark.sql.Row

trait HasBatchedAnnotate[M <: Model[M]] {

  this: RawAnnotator[M] =>

  /** Size of every batch (Default depends on model).
   *
   * @group param
   * */
  val batchSize = new IntParam(this, "batchSize", "Size of every batch.")

  /** Size of every batch.
   *
   * @group setParam
   * */
  def setBatchSize(size: Int): this.type = {
    val recommended = size
    require(recommended > 0, "batchSize must be greater than 0")
    set(this.batchSize, recommended)
  }

  /** Size of every batch.
   *
   * @group getParam
   * */
  def getBatchSize: Int = $(batchSize)

  def batchProcess(rows: Iterator[_]): Iterator[Row] = {
    // TODO remove the @unchecked annotation and create a type to handle different subtypes
    rows.grouped(getBatchSize).flatMap { case batchedRows: Seq[Row@unchecked] =>
      val inputAnnotations = batchedRows.map(row => {
        getInputCols.flatMap(inputCol => {
          row.getAs[Seq[Row]](inputCol).map(Annotation(_))
        })
      })
      val outputAnnotations = batchAnnotate(inputAnnotations)
      batchedRows.zip(outputAnnotations).map { case (row, annotations) =>
        row.toSeq ++ Array(annotations.map(a => Row(a.productIterator.toSeq: _*)))
      }
    }.map(Row.fromSeq)
  }

  /**
   * takes a document and annotations and produces new annotations of this annotator's annotation type
   *
   * @param batchedAnnotations Annotations in batches that correspond to inputAnnotationCols generated by previous annotators if any
   * @return any number of annotations processed for every batch of input annotations. Not necessary one to one relationship
   *
   *         IMPORTANT: !MUST! return sequences of equal lengths !!
   *         IMPORTANT: !MUST! return sentences that belong to the same original row !! (challenging)
   */
  def batchAnnotate(batchedAnnotations: Seq[Array[Annotation]]): Seq[Seq[Annotation]]

}
