package com.example.clustering

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure Kotlin N-dimensional vector point representation, completely decoupled from UI/Android APIs.
 */
data class ClusterPoint(
    val id: Int,
    val coordinates: DoubleArray,
    val label: String? = null
) {
    val dimension: Int get() = coordinates.size

    val x: Double get() = if (coordinates.isNotEmpty()) coordinates[0] else 0.0
    val y: Double get() = if (coordinates.size > 1) coordinates[1] else 0.0

    constructor(id: Int, x: Double, y: Double, label: String? = null) : this(
        id = id,
        coordinates = doubleArrayOf(x, y),
        label = label
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClusterPoint) return false
        return id == other.id &&
            label == other.label &&
            coordinates.contentEquals(other.coordinates)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + coordinates.contentHashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        return result
    }
}

/**
 * Pluggable distance metric contract for K-Means, DBSCAN, and Silhouette evaluation.
 */
fun interface DistanceMetric {
    fun distance(a: DoubleArray, b: DoubleArray): Double

    companion object {
        val EUCLIDEAN = DistanceMetric { a, b ->
            val len = minOf(a.size, b.size)
            var sumSq = 0.0
            for (i in 0 until len) {
                val diff = a[i] - b[i]
                sumSq += diff * diff
            }
            sqrt(sumSq)
        }

        val MANHATTAN = DistanceMetric { a, b ->
            val len = minOf(a.size, b.size)
            var sum = 0.0
            for (i in 0 until len) {
                sum += abs(a[i] - b[i])
            }
            sum
        }

        val COSINE = DistanceMetric { a, b ->
            val len = minOf(a.size, b.size)
            var dot = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in 0 until len) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            if (denom <= 1e-12) 1.0 else (1.0 - (dot / denom)).coerceIn(0.0, 2.0)
        }

        fun minkowski(p: Double): DistanceMetric {
            require(p >= 1.0) { "Minkowski order p must be >= 1.0" }
            return DistanceMetric { a, b ->
                val len = minOf(a.size, b.size)
                var sum = 0.0
                for (i in 0 until len) {
                    sum += abs(a[i] - b[i]).pow(p)
                }
                sum.pow(1.0 / p)
            }
        }
    }
}

/**
 * Classification of points in density-based and centroid-based clustering.
 */
enum class ClusterNodeRole {
    STANDARD,
    CORE,
    BORDER,
    NOISE
}

/**
 * Immutable assignment of a single [ClusterPoint] to a cluster (`-1` for noise/unassigned).
 */
data class ClusteredPoint(
    val point: ClusterPoint,
    val clusterId: Int,
    val role: ClusterNodeRole = ClusterNodeRole.STANDARD
)

/**
 * Immutable N-dimensional centroid state with its movement history across iterations.
 */
data class ClusterCentroid(
    val clusterId: Int,
    val coordinates: DoubleArray,
    val history: List<DoubleArray> = listOf(coordinates.copyOf())
) {
    val x: Double get() = if (coordinates.isNotEmpty()) coordinates[0] else 0.0
    val y: Double get() = if (coordinates.size > 1) coordinates[1] else 0.0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClusterCentroid) return false
        return clusterId == other.clusterId && coordinates.contentEquals(other.coordinates)
    }

    override fun hashCode(): Int {
        return 31 * clusterId + coordinates.contentHashCode()
    }
}

/**
 * Per-cluster statistical summary.
 */
data class ClusterStatistics(
    val clusterId: Int,
    val memberCount: Int,
    val percentage: Double,
    val centroid: DoubleArray,
    val averageIntraClusterDistance: Double
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClusterStatistics) return false
        return clusterId == other.clusterId &&
            memberCount == other.memberCount &&
            centroid.contentEquals(other.centroid)
    }

    override fun hashCode(): Int {
        var res = clusterId
        res = 31 * res + memberCount
        res = 31 * res + centroid.contentHashCode()
        return res
    }
}

/**
 * Snapshot of a single iteration or expansion wave in a clustering run.
 */
data class ClusteringIterationSnapshot(
    val stepIndex: Int,
    val summaryText: String,
    val assignments: List<ClusteredPoint>,
    val centroids: List<ClusterCentroid>,
    val isConverged: Boolean,
    val inertiaWcss: Double,
    val silhouetteScore: Double,
    val corePointCount: Int,
    val borderPointCount: Int,
    val noisePointCount: Int,
    val clusterStats: List<ClusterStatistics>
)

/**
 * Complete result of a clustering run including the final converged state and all intermediate steps.
 */
data class ClusteringExecutionResult(
    val algorithmName: String,
    val steps: List<ClusteringIterationSnapshot>,
    val executedAsync: Boolean
) {
    val finalSnapshot: ClusteringIterationSnapshot
        get() = steps.last()
}

/**
 * Generic contract for pure Kotlin clustering algorithms that can execute synchronously
 * on the calling/main thread for small datasets or asynchronously inside a coroutine
 * for larger datasets.
 */
interface ClusteringAlgorithm<C> {

    /**
     * Synchronous execution suitable for small datasets on the main thread.
     */
    fun fit(points: List<ClusterPoint>, config: C): ClusteringExecutionResult

    /**
     * Coroutine-friendly execution that runs synchronously on the current thread when
     * `points.size <= smallDatasetThreshold` or dispatches to [largeDatasetDispatcher]
     * (`Dispatchers.Default` by default) for larger datasets with cooperative cancellation.
     */
    suspend fun fitAsync(
        points: List<ClusterPoint>,
        config: C,
        smallDatasetThreshold: Int = DEFAULT_SMALL_DATASET_THRESHOLD,
        largeDatasetDispatcher: CoroutineDispatcher = Dispatchers.Default
    ): ClusteringExecutionResult = if (points.size <= smallDatasetThreshold) {
        fit(points, config).copy(executedAsync = false)
    } else {
        withContext(largeDatasetDispatcher) {
            fit(points, config).copy(executedAsync = true)
        }
    }

    companion object {
        const val DEFAULT_SMALL_DATASET_THRESHOLD: Int = 250
    }
}

/**
 * Shared pure-Kotlin evaluation utilities (WCSS inertia, cluster centroids, and Silhouette coefficient).
 */
object ClusteringEvaluation {

    fun computeClusterCentroids(
        assignments: List<ClusteredPoint>,
        dimension: Int = 2
    ): List<ClusterCentroid> {
        return assignments
            .filter { it.clusterId >= 0 }
            .groupBy { it.clusterId }
            .entries
            .sortedBy { it.key }
            .map { (clusterId, members) ->
                val dim = members.firstOrNull()?.point?.dimension ?: dimension
                val mean = DoubleArray(dim)
                for (m in members) {
                    for (d in 0 until minOf(dim, m.point.coordinates.size)) {
                        mean[d] += m.point.coordinates[d]
                    }
                }
                if (members.isNotEmpty()) {
                    for (d in 0 until dim) {
                        mean[d] /= members.size.toDouble()
                    }
                }
                ClusterCentroid(
                    clusterId = clusterId,
                    coordinates = mean,
                    history = listOf(mean.copyOf())
                )
            }
    }

    data class EvaluationMetrics(
        val inertiaWcss: Double,
        val silhouetteScore: Double,
        val clusterStats: List<ClusterStatistics>
    )

    fun evaluate(
        assignments: List<ClusteredPoint>,
        centroids: List<ClusterCentroid>,
        metric: DistanceMetric = DistanceMetric.EUCLIDEAN,
        maxSilhouetteSample: Int = 100
    ): EvaluationMetrics {
        if (assignments.isEmpty()) {
            return EvaluationMetrics(0.0, 0.0, emptyList())
        }

        val centroidMap = centroids.associateBy { it.clusterId }
        val grouped = assignments.filter { it.clusterId >= 0 }.groupBy { it.clusterId }
        var totalWcss = 0.0

        val stats = grouped.entries.sortedBy { it.key }.map { (clusterId, members) ->
            val dim = members.first().point.dimension
            val centerCoords = centroidMap[clusterId]?.coordinates ?: run {
                val avg = DoubleArray(dim)
                for (m in members) {
                    for (d in 0 until dim) avg[d] += m.point.coordinates[d]
                }
                for (d in 0 until dim) avg[d] /= members.size.toDouble()
                avg
            }

            var distSum = 0.0
            for (m in members) {
                val dist = metric.distance(m.point.coordinates, centerCoords)
                totalWcss += dist * dist
                distSum += dist
            }

            ClusterStatistics(
                clusterId = clusterId,
                memberCount = members.size,
                percentage = (members.size.toDouble() / assignments.size.toDouble()) * 100.0,
                centroid = centerCoords.copyOf(),
                averageIntraClusterDistance = if (members.isNotEmpty()) distSum / members.size else 0.0
            )
        }

        val clustered = assignments.filter { it.clusterId >= 0 }
        val silhouette = if (grouped.keys.size < 2 || clustered.size < 3) {
            0.0
        } else {
            val stride = max(1, clustered.size / maxSilhouetteSample)
            val sampled = clustered.filterIndexed { idx, _ -> idx % stride == 0 }
            var silSum = 0.0
            var validCount = 0

            for (item in sampled) {
                val ownGroup = grouped[item.clusterId].orEmpty()
                val a = if (ownGroup.size <= 1) {
                    0.0
                } else {
                    var sum = 0.0
                    for (other in ownGroup) {
                        if (other.point.id != item.point.id) {
                            sum += metric.distance(item.point.coordinates, other.point.coordinates)
                        }
                    }
                    sum / (ownGroup.size - 1).toDouble()
                }

                var b = Double.MAX_VALUE
                for ((otherClusterId, otherMembers) in grouped) {
                    if (otherClusterId == item.clusterId || otherMembers.isEmpty()) continue
                    var sum = 0.0
                    for (other in otherMembers) {
                        sum += metric.distance(item.point.coordinates, other.point.coordinates)
                    }
                    val avgDist = sum / otherMembers.size.toDouble()
                    if (avgDist < b) b = avgDist
                }

                val denom = max(a, b)
                if (denom > 1e-9 && b < Double.MAX_VALUE) {
                    silSum += (b - a) / denom
                    validCount++
                }
            }
            if (validCount > 0) (silSum / validCount.toDouble()).coerceIn(-1.0, 1.0) else 0.0
        }

        return EvaluationMetrics(
            inertiaWcss = totalWcss,
            silhouetteScore = silhouette,
            clusterStats = stats
        )
    }
}
