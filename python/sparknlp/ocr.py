from sparknlp.internal import ExtendedJavaWrapper
from pyspark.sql import SparkSession, DataFrame


class OcrHelper(ExtendedJavaWrapper):

    def __init__(self):
        super(OcrHelper, self).__init__("com.johnsnowlabs.nlp.util.io.OcrHelper")

    def createDataset(self, spark, input_path):
        if type(spark) != SparkSession:
            raise Exception("spark must be SparkSession")
        return DataFrame(self._java_obj.createDataset(spark._jsparkSession, input_path), spark)

    def createMap(self, input_path):
        return self._java_obj.createMap(input_path)

    def setPreferredMethod(self, value):
        return self._java_obj.setPreferredMethod(value)

    def getPreferredMethod(self):
        return self._java_obj.getPreferredMethod()

    def setFallbackMethod(self, value):
        return self._java_obj.setFallbackMethod(value)

    def getFallbackMethod(self):
        return self._java_obj.getFallbackMethod()

    def setMinSizeBeforeFallback(self, value):
        return self._java_obj.setMinSizeBeforeFallback(value)

    def getMinSizeBeforeFallback(self):
        return self._java_obj.getMinSizeBeforeFallback()

    def setEngineMode(self, mode):
        return self._java_obj.setEngineMode(mode)

    def getEngineMode(self):
        return self._java_obj.getEngineMode()

    def setPageSegMode(self, mode):
        return self._java_obj.setPageSegMode(mode)

    def getPageSegMode(self):
        return self._java_obj.getPageSegMode()

    def setPageIteratorLevel(self, level):
        return self._java_obj.setPageIteratorLevel(level)

    def getPageIteratorLevel(self):
        return self._java_obj.getPageIteratorLevel()

    def setScalingFactor(self, factor):
        return self._java_obj.setScalingFactor(factor)

    def setSplitPages(self, value):
        return self._java_obj.setSplitPages(value)

    def getSplitPages(self):
        return self._java_obj.getSplitPages()

    def setSplitRegions(self, value):
        return self._java_obj.setSplitRegions(value)

    def getSplitRegions(self):
        return self._java_obj.getSplitRegions()

    def useErosion(self, use, k_size=2, k_shape=0):
        return self._java_obj.useErosion(use, k_size, k_shape)

    def drawRectangle(self, spark, path, coordinates):
        jcoords = list(map(lambda c: c.java_obj, coordinates))
        return self._java_obj.drawRectangle(spark._jsparkSession, path, jcoords)


#
# @param i  Chunk index.
# @param p  Page number.
# @param x  The lower left x coordinate.
# @param y  The lower left y coordinate.
# @param w  The width of the rectangle.
# @param h  The height of the rectangle.
#
class Coordinate(ExtendedJavaWrapper):
    def __init__(self, i, p, x, y, w, h):
        super(Coordinate, self).__init__("com.johnsnowlabs.nlp.util.io.schema.Coordinate", i, p, x, y, w, h)
        self.i = i
        self.p = p
        self.x = x
        self.y = y
        self.w = w
        self.h = h
