package ru.ifmo.ctddev.isaev.space

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Triangle
import org.locationtech.jts.triangulate.IncrementalDelaunayTriangulator
import org.locationtech.jts.triangulate.quadedge.QuadEdgeSubdivision
import org.locationtech.jts.triangulate.quadedge.Vertex
import org.roaringbitmap.RoaringBitmap
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ru.ifmo.ctddev.isaev.*
import ru.ifmo.ctddev.isaev.ensemble.EnsembleMeasure
import ru.ifmo.ctddev.isaev.point.Point
import ru.ifmo.ctddev.isaev.results.RunStats
import java.text.DecimalFormat
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

/**
 * @author iisaev
 */
class FullSpaceScanner(val config: AlgorithmConfig, val dataSet: DataSet, threads: Int) {

    private val logger: Logger = LoggerFactory.getLogger(FullSpaceScanner::class.java)

    private val foldsEvaluator = config.foldsEvaluator

    private val executorService = Executors.newFixedThreadPool(threads)

    private val featureDataSet = dataSet.toFeatureSet()

    fun run(): RunStats {
        val runStats = RunStats(config, dataSet, javaClass.simpleName)
        logger.info("Started {} at {}", javaClass.simpleName, runStats.startTime)
        val scoreFutures = ArrayList<Future<SelectionResult>>()
        val cutSize = (config.foldsEvaluator.dataSetFilter as? PreferredSizeFilter)?.preferredSize ?: 50
        when (config.measures.size) {
            2 -> {
                val evaluatedDs = evaluateDataSet(featureDataSet, config.measures)
                val (_, _, _, points, _, _) =
                        calculateAllPointsWithEnrichment2d(2, evaluatedDs, cutSize, 2)
                logger.info("Found ${points.size} points to process")
                scoreFutures.addAll(
                        points.map { p ->
                            executorService.submit(Callable<SelectionResult> { foldsEvaluator.getSelectionResult(dataSet, p, runStats, config.measures) })
                        }
                )
            }
            3 -> {
                val evaluatedDsOnTwoMeasures = evaluateDataSet(featureDataSet, config.measures.take(2).toTypedArray())
                val (_, _, _, points2d, _, _) =
                        calculateAllPointsWithEnrichment2d(2, evaluatedDsOnTwoMeasures, cutSize, 2)
                val points = ArrayList<List<Point>>()
                val measures = ArrayList<Array<RelevanceMeasure>>()
                var pointsCount = 0
                logToConsole { "Found ${points2d.size} points to process in 2D" }
                points2d.forEach {
                    val measuresFor3d = arrayOf(
                            EnsembleMeasure(
                                    it.coordinates[0],
                                    config.measures[0],
                                    it.coordinates[1],
                                    config.measures[1]
                            ),
                            config.measures[2]
                    )
                    val evaluatedDsFor3Measures = evaluateDataSet(featureDataSet, measuresFor3d)
                    val (_, _, _, points3d, _, _) =
                            calculateAllPointsWithEnrichment2d(2, evaluatedDsFor3Measures, cutSize, 2)
                    points.add(points3d)
                    pointsCount += points3d.size
                    measures.add(measuresFor3d)
                    logToConsole { "Found ${points3d.size} points to process in 3D for point $it" }
                }
                logToConsole { "Found $pointsCount points to process in 3d space" }
                points.indices.forEach {
                    val curPoints = points[it]
                    val curMeasures = measures[it]
                    scoreFutures.addAll(
                            curPoints.map { p ->
                                executorService.submit(Callable<SelectionResult> { foldsEvaluator.getSelectionResult(dataSet, p, runStats, curMeasures) })
                            }
                    )
                }
            }
            else -> throw IllegalStateException("Unable to perform full space scan by ${config.measures.size} measures")
        }

        val scores: List<SelectionResult> = scoreFutures.map { it.get() }

        logger.info("Total scores: ")
        scores.forEach { println("Score ${it.score} at point ${it.point}") }
        logger.info("Max score: {} at point {}",
                runStats.bestResult.score,
                runStats.bestResult.point
        )
        runStats.finishTime = LocalDateTime.now()
        logger.info("Finished {} at {}", javaClass.simpleName, runStats.finishTime)
        logger.info("Working time: {} seconds", runStats.workTime)
        executorService.shutdown()
        return runStats
    }
}

fun getCenter(p0: Coordinate, p1: Coordinate, p2: Coordinate): Coordinate {
    return Coordinate(
            (p0.x + p1.x + p2.x) / 3,
            (p0.y + p1.y + p2.y) / 3
    )
}

interface CutCache {

    fun compute(point: Point, function: (Point) -> RoaringBitmap): RoaringBitmap

    fun getAll(): Collection<Point>
}

class MapCache : CutCache {
    private val cache = TreeMap<Point, RoaringBitmap>()

    override fun getAll(): Collection<Point> = cache.keys
    override fun compute(point: Point, function: (Point) -> RoaringBitmap): RoaringBitmap {
        return cache.computeIfAbsent(point, function)
    }
}

class NopCache : CutCache {
    override fun getAll(): Collection<Point> = emptyList()

    override fun compute(point: Point, function: (Point) -> RoaringBitmap): RoaringBitmap {
        return function(point)
    }
}

fun performDelaunayEnrichment(evaluatedDs: EvaluatedDataSet,
                              delaunay: IncrementalDelaunayTriangulator,
                              cache: CutCache,
                              geomFact: GeometryFactory,
                              range: Array<Int>,
                              subDiv: QuadEdgeSubdivision,
                              cutSize: Int): Boolean {

    fun processInHomo(coord: Coordinate): RoaringBitmap {
        val point = Point(coord.x, coord.y, 1.0) //conversion from homogeneous to euclidean
        return cache.compute(point, { processPoint(it, evaluatedDs, cutSize, range) })
    }

    val trianglesGeom = subDiv.getTriangles(geomFact) as GeometryCollection
    val allTriangles = 0.until(trianglesGeom.numGeometries)
            .map { trianglesGeom.getGeometryN(it) }
            .map { Triangle(it.coordinates[0], it.coordinates[1], it.coordinates[2]) }
    var isChanged = false
    allTriangles.onEach {
        val p0 = it.p0
        val cut0 = processInHomo(p0)
        val p1 = it.p1
        val cut1 = processInHomo(p1)
        val p2 = it.p2
        val cut2 = processInHomo(p2)

        val center = getCenter(p0, p1, p2)
        val centerCut = processInHomo(center)
        if (centerCut != cut0 && centerCut != cut1 && centerCut != cut2) {
            delaunay.insertSite(Vertex(center.x, center.y))
            isChanged = true
        }
    }
    logToConsole { "Finished iteration: found ${allTriangles.size} polygons" }
    return isChanged
}

private data class PointProcessingResult2d(
        val evaluatedData: List<DoubleArray>,
        val cutsForAllPoints: List<RoaringBitmap>,
        val cutChangePositions: List<Int>,
        val lastFeatureInAllCuts: IntArray
)

data class PointProcessingFinalResult2d(
        val evaluatedData: List<DoubleArray>,
        val cutsForAllPoints: List<RoaringBitmap>,
        val cutChangePositions: List<Int>,
        val pointsToTry: List<Point>,
        val angles: List<Double>,
        val lastFeatureInAllCuts: IntArray
)

val twoDecimalPlaces = DecimalFormat(".##")
fun calculateAllPointsWithEnrichment2d(startingEpsilon: Int,
                                       dataSet: EvaluatedDataSet,
                                       cutSize: Int,
                                       zoom: Int): PointProcessingFinalResult2d {
    var prevEpsilon = startingEpsilon
    var prevPositions = calculateBitMap(0..prevEpsilon)
    var (evaluatedData, cutsForAllPoints, currCutChangePositions, lastFeatureInAllCuts) = processAllPoints(prevPositions, prevEpsilon, dataSet, cutSize)
    var prevCutChangePositions: List<Int>
    var prevLastFeatureInAllCuts = lastFeatureInAllCuts
    logToConsole { "Cut change positions: $currCutChangePositions" }

    // after enrichment
    do {
        prevCutChangePositions = currCutChangePositions

        val (newEpsilon, newPositions) = getNewPositions(prevEpsilon, prevPositions, prevCutChangePositions, zoom)
        val newResult = processAllPoints(newPositions, newEpsilon, dataSet, cutSize)

        evaluatedData = newResult.evaluatedData
        cutsForAllPoints = newResult.cutsForAllPoints
        currCutChangePositions = newResult.cutChangePositions
        logToConsole { "Cut change positions: ${currCutChangePositions.map { twoDecimalPlaces.format(it.toDouble() / (newEpsilon / startingEpsilon)) }}" }
        prevEpsilon = newEpsilon
        prevPositions = newPositions
        prevLastFeatureInAllCuts = newResult.lastFeatureInAllCuts
    } while (currCutChangePositions.size != prevCutChangePositions.size)
    val anglesToTry = currCutChangePositions.map { getAngle(prevEpsilon, it) }
    val pointsToTry = anglesToTry.map { getPointOnUnitSphere(it) }

    return PointProcessingFinalResult2d(
            evaluatedData,
            cutsForAllPoints,
            currCutChangePositions,
            pointsToTry,
            prevPositions.map { getAngle(prevEpsilon, it) },
            prevLastFeatureInAllCuts
    )
}

private fun getNewPositions(prevEpsilon: Int, prevPositions: RoaringBitmap, prevCutChangePositions: List<Int>, zoom: Int): Pair<Int, RoaringBitmap> {
    val newEpsilon = prevEpsilon * zoom
    val newPositions = calculateBitMap(prevPositions.map { it * zoom })
    prevCutChangePositions.forEach {
        val prev = it - 1
        newPositions.add(((prev * zoom) + 1).toLong(), it * zoom.toLong())
    }
    return Pair(newEpsilon, newPositions)
}

private fun processAllPoints(positions: RoaringBitmap,
                             epsilon: Int,
                             dataSet: EvaluatedDataSet,
                             cutSize: Int
): PointProcessingResult2d {
    // begin first stage (before enrichment)
    logToConsole { "Started the processing" }
    logToConsole { "${positions.cardinality} points to calculate measures on" }
    val points = positions.map {
        val firstCoord = it.toDouble() / epsilon
        val secondCoord = 1 - firstCoord
        return@map Point(firstCoord, secondCoord)
    }
    val chunkSize = getChunkSize(dataSet)
    val cutsForAllPoints = ArrayList<RoaringBitmap>(points.size)
    val evaluatedData = ArrayList<DoubleArray>(points.size)
    val lastFeatureInAllCuts = IntArray(points.size)
    points.chunked(chunkSize)
            .forEach { chunk ->
                logToConsole { "Processing chunk of ${chunk.size} points" }
                val (evaluatedDataChunk, cutsForAllPointsChunk, lastFeatureInAllCutsChunk) = processAllPointsChunk(chunk, dataSet, cutSize)
                System.arraycopy(lastFeatureInAllCutsChunk, 0, lastFeatureInAllCuts, evaluatedData.size, lastFeatureInAllCutsChunk.size)
                cutsForAllPoints.addAll(cutsForAllPointsChunk)
                evaluatedData.addAll(evaluatedDataChunk)
            }
    logToConsole { "Evaluated data, calculated cutting line and cuts for all points" }

    val cutChangePositions = positions
            .filterIndexed { i, _ -> i != 0 && cutsForAllPoints[i] != cutsForAllPoints[i - 1] }
    logToConsole { "Found ${cutChangePositions.size} points to try" }

    // end first stage (before enrichment)
    return PointProcessingResult2d(evaluatedData, cutsForAllPoints, cutChangePositions, lastFeatureInAllCuts)
}

fun calculateBitMap(bitsToSet: Iterable<Int>): RoaringBitmap {
    val result = RoaringBitmap()
    bitsToSet.forEach {
        result.add(it)
    }
    result.runOptimize()
    return result
}

fun processAllPointsChunk(xData: List<Point>,
                          dataSet: EvaluatedDataSet,
                          cutSize: Int)
        : Triple<List<DoubleArray>, List<RoaringBitmap>, IntArray> {
    if (xData[0].coordinates.size != 2) {
        throw IllegalArgumentException("Only two-dimensioned points are supported")
    }
    val evaluatedData = evaluatePoints(xData, dataSet)
    val range = Array(evaluatedData[0].size, { it })
    val lastFeatureInAllCuts = IntArray(evaluatedData.size)
    val cutsForAllPoints = evaluatedData
            .mapIndexed { i, featureMeasures ->
                val comparator = kotlin.Comparator<Int> { o1, o2 -> compareValuesBy(o1, o2, { -featureMeasures[it] }) }
                Arrays.sort(range, comparator)
                lastFeatureInAllCuts[i] = range[cutSize - 1]
                return@mapIndexed calculateBitMap(range.take(cutSize))
            }
    return Triple(evaluatedData, cutsForAllPoints, lastFeatureInAllCuts)
}

fun processPoint(point: Point,
                 dataSet: EvaluatedDataSet,
                 cutSize: Int)
        : RoaringBitmap {
    return processPoint(point, dataSet, cutSize, Array(dataSet[0].size, { it }))
}

fun processPoint(point: Point,
                 dataSet: EvaluatedDataSet,
                 cutSize: Int,
                 range: Array<Int>)
        : RoaringBitmap {
    return calculateBitMap(processPointGetWholeCut(point, dataSet, cutSize, range))
}

fun processPointGetWholeCut(point: Point,
                            dataSet: EvaluatedDataSet,
                            cutSize: Int)
        : List<Int> {
    return processPointGetWholeCut(point, dataSet, cutSize, Array(dataSet[0].size, { it }))
}

fun processPointGetWholeCut(point: Point,
                            dataSet: EvaluatedDataSet,
                            cutSize: Int,
                            range: Array<Int>)
        : List<Int> {
    val featureMeasures = evaluatePoint(point, dataSet)
    val comparator = kotlin.Comparator<Int> { o1, o2 -> compareValuesBy(o1, o2, { -featureMeasures[it] }) }
    Arrays.sort(range, comparator)
    return range.take(cutSize)
}

fun processAllPointsFastOld(xData: List<Point>,
                            dataSet: FeatureDataSet,
                            measures: Array<out RelevanceMeasure>,
                            cutSize: Int)
        : Triple<List<DoubleArray>, List<Int>, List<RoaringBitmap>> {
    if (xData[0].coordinates.size != 2) {
        throw IllegalArgumentException("Only two-dimensioned points are supported")
    }
    val evaluatedData = evaluatePoints(xData, dataSet, measures)
    val range = Array(evaluatedData[0].size, { it })
    val evaluatedDataWithNumbers = evaluatedData.map { Pair(it, range.clone()) }
    val rawCutsForAllPoints = evaluatedDataWithNumbers
            .map {
                val featureNumbers = it.second
                val featureMeasures = it.first
                val comparator = kotlin.Comparator<Int> { o1, o2 -> compareValuesBy(o1, o2, { -featureMeasures[it] }) }
                Arrays.sort(featureNumbers, comparator)
                return@map Pair(featureMeasures, featureNumbers)
            } //max first
    val cuttingLineY = rawCutsForAllPoints
            .map {
                it.second[cutSize - 1]
            }
    val cutsForAllPoints = rawCutsForAllPoints
            .map { calculateBitMap(it.second.take(cutSize)) }
    return Triple(evaluatedData, cuttingLineY, cutsForAllPoints)
}


fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
fun lcm(a: Int, b: Int): Int {
    val result = a.toLong() / gcd(a, b) * b
    if (result < 0) {
        throw IllegalStateException("Overflow!")
    }
    return result.toInt()
}

fun getDiff(prevCut: RoaringBitmap, currCut: RoaringBitmap, tempMap: RoaringBitmap): RoaringBitmap {
    tempMap.clear()
    tempMap.or(prevCut)
    tempMap.andNot(currCut)
    return tempMap
}

fun getDiff(prevCut: RoaringBitmap, currCut: RoaringBitmap) = getDiff(prevCut, currCut, RoaringBitmap())
fun getDiff(prevCut: List<Int>, currCut: List<Int>) = prevCut.filter { !currCut.contains(it) }

data class Coord(private val num: Int,
                 private val denom: Int) : Comparable<Coord> {
    override fun compareTo(other: Coord): Int {
        val commonDenom = lcm(denom, other.denom)
        return (num * (commonDenom / denom)).compareTo(other.num * (commonDenom / other.denom))
    }

    companion object {
        fun from(rawNum: Int, rawDenom: Int): Coord {
            val gcd = gcd(rawNum, rawDenom)
            return Coord(rawNum / gcd, rawDenom / gcd)
        }
    }

    override fun toString(): String {
        return "$num/$denom"
    }
}

data class SpacePoint(val point: IntArray,
                      val delta: Int) : Comparable<SpacePoint> {
    val eqToPrev = BooleanArray(point.size)

    // wrapper for int array for proper hashcode and compareTo implementation
    override fun compareTo(other: SpacePoint): Int {
        if (point.size != other.point.size) {
            throw IllegalStateException("Trying to compare invalid points")
        }
        for (i in point.indices) {
            if (Coord.from(point[i], delta) < Coord.from(other.point[i], other.delta)) {
                return -1
            }
            if (Coord.from(point[i], delta) > Coord.from(other.point[i], other.delta)) {
                return 1
            }
        }
        return 0
    }


    constructor(size: Int) : this(IntArray(size), 1)

    fun clone(): SpacePoint = SpacePoint(point.clone(), delta)
    val size: Int = point.size
    override fun toString(): String = Arrays.toString(point) + "/" + delta
    fun indices(): IntRange = point.indices
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SpacePoint

        if (!Arrays.equals(point, other.point)) return false
        if (delta != other.delta) return false

        return true
    }

    override fun hashCode(): Int {
        var result = Arrays.hashCode(point)
        result = 31 * result + delta
        return result
    }
}

const val DOUBLE_SIZE = 8

private fun getChunkSize(dataSet: EvaluatedDataSet): Int {
    val numberOfFeatures = dataSet[0].size
    val numberOfMeasures = dataSet.size
    logToConsole { "Number of features: $numberOfFeatures" }
    val allocatedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
    val presumableFreeMemory = Runtime.getRuntime().maxMemory() - allocatedMemory

    logToConsole { "Available memory: ${presumableFreeMemory / 1024 / 1024} mb" }
    val calculatedChunkSize = (presumableFreeMemory / DOUBLE_SIZE / numberOfFeatures / numberOfMeasures).toInt()
    logToConsole { "Calculated chunk size: $calculatedChunkSize" }
    logToConsole { "Chunk size to use: $calculatedChunkSize" }
    return calculatedChunkSize
}


fun processAllPointsHdChinked(xDataRaw: List<SpacePoint>,
                              dataSet: EvaluatedDataSet,
                              epsilon: Int,
                              cutSize: Int)
        : Map<SpacePoint, RoaringBitmap> {
    val cutsForAllPoints = HashMap<SpacePoint, RoaringBitmap>(xDataRaw.size)
    val chunkSize = getChunkSize(dataSet) // to ensure computation fits inti memory
    xDataRaw.chunked(chunkSize)
            .forEach {
                logToConsole { "Processing chunk of ${it.size} points" }
                processAllPointsHd(it, dataSet, cutSize)
                        .forEachIndexed { index, set ->
                            cutsForAllPoints[it[index]] = set
                        }
            }
    return cutsForAllPoints
}

fun processAllPointsHd(xDataRaw: List<SpacePoint>,
                       dataSet: EvaluatedDataSet,
                       cutSize: Int)
        : List<RoaringBitmap> {
    val angles = xDataRaw.map { getAngle(it) }
    //val xData = angles.map { getPointOnUnitSphere(it) }
    val xData = xDataRaw.map { Point(it.point[0].toDouble() / it.delta, it.point[1].toDouble() / it.delta, 1.0) }

    val evaluatedData = evaluatePoints(xData, dataSet)
    return evaluatedData
            .map { featureMeasures ->
                val featureNumbers = Array(evaluatedData[0].size, { it })
                val comparator = kotlin.Comparator<Int> { o1, o2 -> compareValuesBy(o1, o2, { -featureMeasures[it] }) }
                Arrays.sort(featureNumbers, comparator)
                val cut = featureNumbers.take(cutSize)
                return@map calculateBitMap(cut)
            } //max first
}


const val MAX_ANGLE = Math.PI / 2 //TODO: investigate why GDS4901 does not work with processing from PI to -PI/2

const val MIN_ANGLE = 0 //-Math.PI / 2

const val ANGLE_RANGE = MAX_ANGLE - MIN_ANGLE

fun getAngle(delta: Int, x: Int): Double {
    val fractionOfPi = ANGLE_RANGE / delta
    return MIN_ANGLE + (fractionOfPi * x)
}

fun getAngle(xs: SpacePoint): DoubleArray {
    val result = DoubleArray(xs.size)
    xs.point.forEachIndexed { i, x ->
        result[i] = getAngle(xs.delta, x)
    }
    return result
}

val startTime = LocalDateTime.now()!!

inline fun logToConsole(msgGetter: () -> String) {
    println("${Duration.between(startTime, LocalDateTime.now()).toMillis()} ms: " + msgGetter())
}

fun inBetween(prev: SpacePoint, point: SpacePoint): SpacePoint {
    val curArr = point.point
    val curDelta = point.delta
    val prevArr = prev.point
    val prevDelta = prev.delta

    val newArr = curArr.clone()
    val newDelta: Int

    val commonDivisor = lcm(curDelta, prevDelta)
    val mulForCurr = commonDivisor / curDelta // all deltas are powers of 2
    val mulForPrev = commonDivisor / prevDelta // all deltas are powers of 2

    newArr.indices.forEach { newArr[it] = curArr[it] * mulForCurr + prevArr[it] * mulForPrev }
    if (newArr.all { it % 2 == 0 }) {
        newArr.indices.forEach { newArr[it] /= 2 }
        val commonGcd = newArr.map { gcd(it, commonDivisor) }.reduce { o1, o2 -> gcd(o1, o2) }
        newArr.indices.forEach { newArr[it] /= commonGcd }
        newDelta = commonDivisor / commonGcd
    } else {
        newDelta = commonDivisor * 2
    }
    return SpacePoint(newArr, newDelta)
}