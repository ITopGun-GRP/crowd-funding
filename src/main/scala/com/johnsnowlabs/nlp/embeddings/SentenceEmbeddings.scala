package com.johnsnowlabs.nlp.embeddings

import com.johnsnowlabs.nlp.annotators.common.{SentenceSplit, WordpieceEmbeddingsSentence}
import com.johnsnowlabs.nlp.{Annotation, AnnotatorModel}
import org.apache.spark.ml.util.{DefaultParamsReadable, Identifiable}
import org.apache.spark.ml.param.Param

class SentenceEmbeddings(override val uid: String) extends AnnotatorModel[SentenceEmbeddings] {
  import com.johnsnowlabs.nlp.AnnotatorType._
  override val outputAnnotatorType: AnnotatorType = SENTENCE_EMBEDDINGS

  override val inputAnnotatorTypes: Array[AnnotatorType] = Array(DOCUMENT, WORD_EMBEDDINGS)

  val poolingStrategy = new Param[String](this, "poolingStrategy", "Choose how you would like to aggregate Word Embeddings to Sentence Embeddings: AVERAGE or SUM")

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
    poolingStrategy -> "AVERAGE"
  )

  /** Internal constructor to submit a random UID */
  def this() = this(Identifiable.randomUID("SENTENCE_EMBEDDINGS"))

  private def calculateSentenceEmbeddings(matrix : Array[Array[Float]]):Array[Float] = {
    val res = Array.ofDim[Float](matrix(0).length)
    matrix(0).indices.foreach {
      j =>
        matrix.indices.foreach {
          i =>
            res(j) += matrix(i)(j)
        }
        if($(poolingStrategy) == "AVERAGE")
          res(j) /= matrix.length
    }
    res.toArray.map(_.toFloat)
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

    sentences.zipWithIndex.map { case (sentence, idx) =>
      val sentenceEmbeddings = embeddingsSentences.map {
        case (tokenEmbedding) =>
          val allEmbeddings = tokenEmbedding.tokens.map { token =>
            token.embeddings
          }
          calculateSentenceEmbeddings(allEmbeddings)
      }

      Annotation(
        annotatorType = outputAnnotatorType,
        begin = sentence.start,
        end = sentence.end,
        result = sentence.content,
        metadata = Map.empty[String, String],
        embeddings = sentenceEmbeddings(idx)
      )
    }
  }

}

object SentenceEmbeddings extends DefaultParamsReadable[SentenceEmbeddings]
