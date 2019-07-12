package com.johnsnowlabs.nlp.eval

import com.johnsnowlabs.nlp.annotator.{NerDLApproach, WordEmbeddings}
import com.johnsnowlabs.nlp.embeddings.WordEmbeddingsFormat
import org.scalatest.FlatSpec

class NerDLEvalTesSpec extends FlatSpec {

  "A NER DL Evaluation" should "display accuracy results" in {
    val trainFile = "./eng.train"
    val testFiles = "./eng.testa"
    val format = ""
    val modelPath = "./ner_dl"

    val glove = new WordEmbeddings()
      .setInputCols("sentence", "token")
      .setOutputCol("glove")
      .setEmbeddingsSource("/Users/dburbano/tmp/embeddings.100d.test.txt",
        100, WordEmbeddingsFormat.TEXT)
      .setCaseSensitive(true)

    val nerTagger = new NerDLApproach()
      .setInputCols(Array("sentence", "token", "glove"))
      .setLabelColumn("label")
      .setOutputCol("ner")
      .setMaxEpochs(10)
      .setRandomSeed(0)
      .setVerbose(2)

    val nerDLEvaluation = new NerDLEvaluation(testFiles, format)
    nerDLEvaluation.computeAccuracyAnnotator(modelPath, trainFile, nerTagger, glove)

  }

}