package com.example.clustering

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.random.Random

/**
 * Pure Kotlin configuration for the K-Means clustering algorithm.
 */
data class KMeansParameters(
    val k: Int = 3,
    val useKMeansPlusPlus: Boolean = true,
    val maxIterations: Int = 30,
    val convergenceTolerance: Double = 1e-4,
    val randomSeed: Long = 42L,
    val distanceMetric: DistanceMetric = DistanceMetric.EUCLIDEAN
) {
    init {
        require(k >= 1) { "Number of clusters k must be >= 1" }
        require(maxIterations >= 1) { "maxIterations must be >= 1" }
        require(convergenceTolerance >= 0.0) { "convergenceTolerance must be non-negative" }
    }
}

/**
 * Pure Kotlin K-Means clustering implementation (Lloyd's iterations + K-Means++ initialization).
 * Completely decoupled from the UI layer:
 * - Call [fit] for synchronous execution on the main thread for small datasets.
 * - Call [fitAsync] to automatically run on the current thread for small datasets or
 *   offload to [Dispatchers.Default] with cooperative cancellation for larger datasets.
 */
class KMeansClusterer : ClusteringAlgorithm<KMeansParameters> {

    override fun fit(
        points: List<ClusterPoint>,
        config: KMeansParameters
    ): ClusteringExecutionResult {
        val steps = computeStepsInternal(points, config, checkCancellation = {})
        return ClusteringExecutionResult(
            algorithmName = "K-Means",
            steps = steps,
            executedAsync = false
        )
    }

    override suspend fun fitAsync(
        points: List<ClusterPoint>,
        config: KMeansParameters,
        smallDatasetThreshold: Int,
        largeDatasetDispatcher: CoroutineDispatcher
    ): ClusteringExecutionResult {
        return if (points.size <= smallDatasetThreshold) {
            coroutineContext.ensureActive()
            val steps = computeStepsInternal(points, config, checkCancellation = {})
            ClusteringExecutionResult(
                algorithmName = "K-Means",
                steps = steps,
                executedAsync = false
            )
        } else {
            withContext(largeDatasetDispatcher) {
                val steps = computeStepsInternal(
                    points = points,
                    config = config,
                    checkCancellation = { coroutineContext.ensureActive() }
                )
                ClusteringExecutionResult(
                    algorithmName = "K-Means",
                    steps = steps,
                    executedAsync = true
                )
            }
        }
    }

    private inline fun computeStepsInternal(
        points: List<ClusterPoint>,
        config: KMeansParameters,
        checkCancellation: () -> Unit
    ): List<ClusteringIterationSnapshot> {
        if (points.isEmpty()) {
            return listOf(
                ClusteringIterationSnapshot(
                    stepIndex = 0,
                    summaryText = "Add or load points to run K-Means.",
                    assignments = emptyList(),
                    centroids = emptyList(),
                    isConverged = true,
                    inertiaWcss = 0.0,
                    silhouetteScore = 0.0,
                    corePointCount = 0,
                    borderPointCount = 0,
                    noisePointCount = 0,
                    clusterStats = emptyList()
                )
            )
        }

        checkCancellation()
        val effectiveK = config.k.coerceIn(1, min(10, points.size))
        val rng = Random(config.randomSeed)
        val metric = config.distanceMetric

        // Step 0: Initialize centroids via K-Means++ or uniform random sampling
        var centroids = if (config.useKMeansPlusPlus) {
            initializeKMeansPlusPlus(points, effectiveK, rng, metric)
        } else {
            points.shuffled(rng).take(effectiveK).mapIndexed { idx, pt ->
                ClusterCentroid(
                    clusterId = idx,
                    coordinates = pt.coordinates.copyOf(),
                    history = listOf(pt.coordinates.copyOf())
                )
            }
        }

        val snapshots = mutableListOf<ClusteringIterationSnapshot>()
        var currentAssignments = assignPointsToNearestCentroids(points, centroids, metric)
        val initEval = ClusteringEvaluation.evaluate(currentAssignments, centroids, metric)

        snapshots.add(
            ClusteringIterationSnapshot(
                stepIndex = 0,
                summaryText = if (config.useKMeansPlusPlus) {
                    "Step 0: K-Means++ seeded $effectiveK centroids & initial Voronoi assignment"
                } else {
                    "Step 0: Random seeded $effectiveK centroids & initial assignment"
                },
                assignments = currentAssignments,
                centroids = centroids,
                isConverged = false,
                inertiaWcss = initEval.inertiaWcss,
                silhouetteScore = initEval.silhouetteScore,
                corePointCount = currentAssignments.size,
                borderPointCount = 0,
                noisePointCount = 0,
                clusterStats = initEval.clusterStats
            )
        )

        var iteration = 1
        while (iteration <= config.maxIterations) {
            checkCancellation()
            var maxCentroidShift = 0.0

            val updatedCentroids = centroids.map { centroid ->
                val members = currentAssignments.filter { it.clusterId == centroid.clusterId }
                if (members.isEmpty()) {
                    centroid
                } else {
                    val dim = centroid.coordinates.size
                    val newCoords = DoubleArray(dim)
                    for (m in members) {
                        for (d in 0 until minOf(dim, m.point.coordinates.size)) {
                            newCoords[d] += m.point.coordinates[d]
                        }
                    }
                    for (d in 0 until dim) {
                        newCoords[d] /= members.size.toDouble()
                    }
                    val shift = metric.distance(centroid.coordinates, newCoords)
                    if (shift > maxCentroidShift) {
                        maxCentroidShift = shift
                    }
                    centroid.copy(
                        coordinates = newCoords,
                        history = centroid.history + listOf(newCoords.copyOf())
                    )
                }
            }

            val nextAssignments = assignPointsToNearestCentroids(points, updatedCentroids, metric)
            val anyReassigned = nextAssignments.indices.any { i ->
                nextAssignments[i].clusterId != currentAssignments[i].clusterId
            }

            centroids = updatedCentroids
            currentAssignments = nextAssignments
            val converged = !anyReassigned || maxCentroidShift <= config.convergenceTolerance
            val eval = ClusteringEvaluation.evaluate(currentAssignments, centroids, metric)

            snapshots.add(
                ClusteringIterationSnapshot(
                    stepIndex = iteration,
                    summaryText = if (converged) {
                        "Step $iteration: Converged (Δ centroid = ${"%.4f".format(maxCentroidShift)})"
                    } else {
                        "Step $iteration: Updated centroids & reassigned points (Δ = ${"%.4f".format(maxCentroidShift)})"
                    },
                    assignments = currentAssignments,
                    centroids = centroids,
                    isConverged = converged,
                    inertiaWcss = eval.inertiaWcss,
                    silhouetteScore = eval.silhouetteScore,
                    corePointCount = currentAssignments.size,
                    borderPointCount = 0,
                    noisePointCount = 0,
                    clusterStats = eval.clusterStats
                )
            )

            if (converged) break
            iteration++
        }

        return snapshots
    }

    private fun initializeKMeansPlusPlus(
        points: List<ClusterPoint>,
        k: Int,
        rng: Random,
        metric: DistanceMetric
    ): List<ClusterCentroid> {
        val chosen = mutableListOf<ClusterCentroid>()
        val first = points[rng.nextInt(points.size)]
        chosen.add(
            ClusterCentroid(
                clusterId = 0,
                coordinates = first.coordinates.copyOf(),
                history = listOf(first.coordinates.copyOf())
            )
        )

        val distSquared = DoubleArray(points.size)
        for (clusterIdx in 1 until k) {
            var sumSq = 0.0
            for (i in points.indices) {
                val pt = points[i]
                var minD2 = Double.MAX_VALUE
                for (c in chosen) {
                    val d = metric.distance(pt.coordinates, c.coordinates)
                    val d2 = d * d
                    if (d2 < minD2) minD2 = d2
                }
                distSquared[i] = minD2
                sumSq += minD2
            }

            val selectedPoint = if (sumSq <= 1e-12) {
                points[rng.nextInt(points.size)]
            } else {
                val target = rng.nextDouble() * sumSq
                var cumulative = 0.0
                var picked = points.last()
                for (i in points.indices) {
                    cumulative += distSquared[i]
                    if (cumulative >= target) {
                        picked = points[i]
                        break
                    }
                }
                picked
            }

            chosen.add(
                ClusterCentroid(
                    clusterId = clusterIdx,
                    coordinates = selectedPoint.coordinates.copyOf(),
                    history = listOf(selectedPoint.coordinates.copyOf())
                )
            )
        }
        return chosen
    }

    private fun assignPointsToNearestCentroids(
        points: List<ClusterPoint>,
        centroids: List<ClusterCentroid>,
        metric: DistanceMetric
    ): List<ClusteredPoint> {
        if (centroids.isEmpty()) {
            return points.map { ClusteredPoint(it, clusterId = -1, role = ClusterNodeRole.STANDARD) }
        }
        return points.map { pt ->
            var bestCluster = centroids.first().clusterId
            var bestDistance = Double.MAX_VALUE
            for (c in centroids) {
                val d = metric.distance(pt.coordinates, c.coordinates)
                if (d < bestDistance) {
                    bestDistance = d
                    bestCluster = c.clusterId
                }
            }
            ClusteredPoint(
                point = pt,
                clusterId = bestCluster,
                role = ClusterNodeRole.STANDARD
            )
        }
    }
}
