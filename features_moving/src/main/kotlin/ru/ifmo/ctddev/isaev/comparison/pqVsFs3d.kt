package ru.ifmo.ctddev.isaev.comparison

import org.slf4j.LoggerFactory
import ru.ifmo.ctddev.isaev.*
import ru.ifmo.ctddev.isaev.feature.measure.SymmetricUncertainty
import ru.ifmo.ctddev.isaev.feature.measure.VDM
import ru.ifmo.ctddev.isaev.melif.impl.ParallelMeLiF
import ru.ifmo.ctddev.isaev.melif.impl.PriorityQueueMeLiF
import ru.ifmo.ctddev.isaev.point.Point
import ru.ifmo.ctddev.isaev.space.FullSpaceScanner
import ru.ifmo.ctddev.isaev.space.calculateTime
import java.io.File
import java.util.*

/**
 * @author iisaev
 */

private val LOGGER = LoggerFactory.getLogger("pqVsFs3d")

private val RANDOM = Random(0xBADDAD)

private data class ComparisonResult3d(
        val mPlusTime: Long,
        val mPlusScore: Double,
        val pqVisited: Long,
        val pqTime: Long,
        val pqScore: Double,
        val pqPoint: Point,
        val fsVisited: Long,
        val fsTime: Long,
        val fsScore: Double,
        val fsPoint: Point
)

fun main(args: Array<String>) {
    File("results3d_${System.currentTimeMillis()}.txt").printWriter().use { out ->
        KnownDatasets.values().forEach {
            try {
                val dataSet = it.read()
                val res = processDataSet(dataSet)
                out.println("$it, " +
                        "${res.mPlusTime}, ${res.mPlusScore}, " +
                        "${res.pqVisited}, ${res.pqTime}, ${res.pqScore}, ${res.pqPoint}, " +
                        "${res.fsVisited}, ${res.fsTime}, ${res.fsScore}, ${res.fsPoint}" +
                        "")
                out.flush()
            } catch (e: Exception) {
                LOGGER.error("Some error!", e)
            }
        }
    }
}

private fun processDataSet(dataSet: FeatureDataSet): ComparisonResult3d {
    val order = 0.until(dataSet.getInstanceCount()).shuffled(RANDOM)
    val algorithmConfig = AlgorithmConfig(
            0.001,
            SequentalEvaluator(
                    Classifiers.SVM,
                    PreferredSizeFilter(50),
                    OrderSplitter(10, order),
                    F1Score()
            ),
            arrayOf(VDM(), SpearmanRankCorrelation(), SymmetricUncertainty())
    )

    val (pqTime, pqStats) = calculateTime {
        PriorityQueueMeLiF(algorithmConfig, dataSet, 4)
                .run("PqMeLif", 100)
    }

    val (mPlusTime, mPlusStats) = calculateTime {
        ParallelMeLiF(algorithmConfig, dataSet, 4)
                .run("MeLif+", arrayOf(
                        Point(1.0, 0.0, 0.0),
                        Point(0.0, 1.0, 0.0),
                        Point(0.0, 0.0, 1.0)
                ), false)
    }

    val (fullSpaceTime, fullSpaceStats) = calculateTime {
        FullSpaceScanner(algorithmConfig, dataSet, 4)
                .run()
    }

    LOGGER.info("""
          PQ: processed ${pqStats.visitedPoints} points in ${pqTime / 1000} seconds, 
          best result is ${pqStats.bestResult.score} in point ${pqStats.bestResult.point}
          """)
    LOGGER.info("""
        FS: processed ${fullSpaceStats.visitedPoints} points in ${fullSpaceTime / 1000} seconds, 
        best result is ${fullSpaceStats.bestResult.score} in point ${fullSpaceStats.bestResult.point}
        """)
    return ComparisonResult3d(
            mPlusTime / 1000,
            mPlusStats.bestResult.score,
            pqStats.visitedPoints,
            pqTime / 1000,
            pqStats.bestResult.score,
            pqStats.bestResult.point,
            fullSpaceStats.visitedPoints,
            fullSpaceTime / 1000,
            fullSpaceStats.bestResult.score,
            fullSpaceStats.bestResult.point
    )
}