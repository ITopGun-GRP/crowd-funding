package com.johnsnowlabs.storage

import org.apache.spark.ml.param.{BooleanParam, Param, Params}
import org.apache.spark.sql.Dataset

trait HasStorage[A] extends Params {

  val includeStorage = new BooleanParam(this, "includeStorage", "whether or not to save indexed storage along this annotator")
  val storageRef = new Param[String](this, "storageRef", "unique reference name for identification")

  @transient private var retriever: RocksDBReader[A] = _
  @transient private var loaded: Boolean = false

  protected val storageHelper: StorageHelper[A, RocksDBReader[A]]

  protected var preloadedConnection: Option[RocksDBReader[A]] = None

  setDefault(includeStorage, true)

  def setIncludeStorage(value: Boolean): this.type = set(includeStorage, value)
  def getIncludeStorage: Boolean = $(includeStorage)

  protected def setAsLoaded(): Unit = loaded = true
  protected def isLoaded(): Boolean = loaded

  def setStorageRef(value: String): this.type = {
    if (get(storageRef).nonEmpty)
      throw new UnsupportedOperationException(s"Cannot override storage ref on $this. " +
        s"Please re-use current ref: $getStorageRef")
    set(this.storageRef, value)
  }
  def getStorageRef: String = $(storageRef)

  def validateStorageRef(dataset: Dataset[_], inputCols: Array[String], annotatorType: String): Unit = {
    val storageCol = dataset.schema.fields
      .find(f => inputCols.contains(f.name) && f.metadata.getString("annotatorType") == annotatorType)
      .getOrElse(throw new Exception(s"Could not find a valid storage column, make sure the storage is loaded " +
        s"and has the following ref: ${$(storageRef)}")).name

    val storage_meta = dataset.select(storageCol).schema.fields.head.metadata

    require(storage_meta.contains("ref"), "Cannot find storage ref in column schema metadata")

    require(storage_meta.getString("ref") == $(storageRef),
      s"Found storage column, but ref does not match to the ref this model was trained with. " +
        s"Make sure you are using the right storage in your pipeline, with ref: ${$(storageRef)}")
  }

  protected def getRetriever(caseSensitive: Boolean): RocksDBReader[A] = {
    if (Option(retriever).isDefined)
      retriever
    else {
      retriever = getStorageConnection(caseSensitive)
      retriever
    }
  }

  protected def getStorageConnection(caseSensitive: Boolean): RocksDBReader[A] = {
    if (preloadedConnection.isDefined && preloadedConnection.get.fileName == $(storageRef))
      return preloadedConnection.get
    else {
      preloadedConnection.foreach(_.findLocalDb.close())
      preloadedConnection = Some(storageHelper.load(
        storageHelper.getClusterFilename($(storageRef)),
        caseSensitive
      ))
    }
    preloadedConnection.get
  }

}
