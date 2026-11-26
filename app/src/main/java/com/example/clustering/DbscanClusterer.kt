package com.example.clustering

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Pure Kotlin configuration for the DBSCAN (Density-Based Spatial Clustering of Applications with Noise) algorithm.
 */
data class DbscanParameters(
    val epsilon: Double = 0.085,
    val minPoints: Int = 5,
    val distanceMetric: DistanceMetric = DistanceMetric.EUCLIDEAN
) {
    init {
        require(epsilon > 0.0) { "Epsilon neighborhood radius must be > 0.0" }
        require(minPoints >= 1) { "minPoints must be >= 1" }
    }
}

/**
 * Pure Kotlin DBSCAN clustering implementation.
 * Completely decoupled from the UI layer:
 * - Call [fit] for synchronous execution on the main thread for small datasets.
 * - Call [fitAsync] to automatically run on the current thread for small datasets or
 *   offload to [Dispatchers.Default] with cooperative cancellation for larger datasets.
 */
class DbscanClusterer : ClusteringAlgorithm<DbscanParameters> {

    override fun fit(
        points: List<ClusterPoint>,
        config: DbscanParameters
    ): ClusteringExecutionResult {
        val steps = computeStepsInternal(points, config, checkCancellation = {})
        return ClusteringExecutionResult(
            algorithmName = "DBSCAN",
            steps = steps,
            executedAsync = false
        )
    }

    override suspend fun fitAsync(
        points: List<ClusterPoint>,
        config: DbscanParameters,
        smallDatasetThreshold: Int,
        largeDatasetDispatcher: CoroutineDispatcher
    ): ClusteringExecutionResult {
        return if (points.size <= smallDatasetThreshold) {
            coroutineContext.ensureActive()
            val steps = computeStepsInternal(points, config, checkCancellation = {})
            ClusteringExecutionResult(
                algorithmName = "DBSCAN",
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
                    algorithmName = "DBSCAN",
                    steps = steps,
                    executedAsync = true
                )
            }
        }
    }

    private inline fun computeStepsInternal(
        points: List<ClusterPoint>,
        config: DbscanParameters,
        checkCancellation: () -> Unit
    ): List<ClusteringIterationSnapshot> {
        if (points.isEmpty()) {
            return listOf(
                ClusteringIterationSnapshot(
                    stepIndex = 0,
                    summaryText = "Add or load points to run DBSCAN.",
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
        val eps = config.epsilon
        val minPts = config.minPoints
        val metric = config.distanceMetric
        val n = points.size

        // 1. Precompute epsilon-neighborhoods for all points
        val neighborhoods = Array(n) { i ->
            if (i % 64 == 0) checkCancellation()
            val p1 = points[i].coordinates
            val neighbors = IntArrayList()
            for (j in 0 until n) {
                if (metric.distance(p1, points[j].coordinates) <= eps) {
                    neighbors.add(j)
                }
            }
            neighbors.toIntArray()
        }

        val isCore = BooleanArray(n) { i -> neighborhoods[i].size >= minPts }
        val clusterIds = IntArray(n) { -1 }
        val roles = Array(n) { i ->
            if (isCore[i]) ClusterNodeRole.CORE else ClusterNodeRole.NOISE
        }
        val visited = BooleanArray(n)

        val snapshots = mutableListOf<ClusteringIterationSnapshot>()

        // Frame 0: Initial Core Point discovery
        val initialAssignments = points.mapIndexed { idx, pt ->
            ClusteredPoint(
                point = pt,
                clusterId = -1,
                role = if (isCore[idx]) ClusterNodeRole.CORE else ClusterNodeRole.STANDARD
            )
        }
        val initialCoreCount = isCore.count { it }
        snapshots.add(
            ClusteringIterationSnapshot(
                stepIndex = 0,
                summaryText = "Step 0: Detected $initialCoreCount core points (ε=${"%.3f".format(eps)}, MinPts=$minPts)",
                assignments = initialAssignments,
                centroids = emptyList(),
                isConverged = false,
                inertiaWcss = 0.0,
                silhouetteScore = 0.0,
                corePointCount = initialCoreCount,
                borderPointCount = 0,
                noisePointCount = n - initialCoreCount,
                clusterStats = emptyList()
            )
        )

        var currentClusterIndex = 0
        var stepCounter = 1

        // 2. Breadth-first density-reachable cluster expansion
        for (i in 0 until n) {
            checkCancellation()
            if (visited[i]) continue
            visited[i] = true

            if (!isCore[i]) {
                roles[i] = ClusterNodeRole.NOISE
                continue
            }

            val assignedClusterId = currentClusterIndex
            currentClusterIndex++
            clusterIds[i] = assignedClusterId
            roles[i] = ClusterNodeRole.CORE

            val queue = ArrayDeque<Int>()
            for (nb in neighborhoods[i]) {
                if (nb != i) queue.addLast(nb)
            }

            var expandedCount = 1
            while (queue.isNotEmpty()) {
                val curr = queue.removeFirst()
                if (!visited[curr]) {
                    visited[curr] = true
                    if (isCore[curr]) {
                        roles[curr] = ClusterNodeRole.CORE
                        for (nb in neighborhoods[curr]) {
                            if (clusterIds[nb] == -1) {
                                queue.addLast(nb)
                            }
                        }
                    }
                }
                if (clusterIds[curr] == -1) {
                    clusterIds[curr] = assignedClusterId
                    roles[curr] = if (isCore[curr]) {
                        ClusterNodeRole.CORE
                    } else {
                        ClusterNodeRole.BORDER
                    }
                    expandedCount++
                }
            }

            val waveAssignments = points.mapIndexed { idx, pt ->
                ClusteredPoint(
                    point = pt,
                    clusterId = clusterIds[idx],
                    role = roles[idx]
                )
            }
            val centroids = ClusteringEvaluation.computeClusterCentroids(waveAssignments)
            val eval = ClusteringEvaluation.evaluate(waveAssignments, centroids, metric)
            val waveCoreCount = waveAssignments.count {
                it.role == ClusterNodeRole.CORE && it.clusterId >= 0
            }
            val waveBorderCount = waveAssignments.count { it.role == ClusterNodeRole.BORDER }
            val waveNoiseCount = waveAssignments.count { it.clusterId == -1 }

            snapshots.add(
                ClusteringIterationSnapshot(
                    stepIndex = stepCounter++,
                    summaryText = "Step ${stepCounter - 1}: Expanded Cluster #$currentClusterIndex ($expandedCount density-reachable pts)",
                    assignments = waveAssignments,
                    centroids = centroids,
                    isConverged = false,
                    inertiaWcss = eval.inertiaWcss,
                    silhouetteScore = eval.silhouetteScore,
                    corePointCount = waveCoreCount,
                    borderPointCount = waveBorderCount,
                    noisePointCount = waveNoiseCount,
                    clusterStats = eval.clusterStats
                )
            )
        }

        // 3. Final converged summary snapshot
        val finalAssignments = points.mapIndexed { idx, pt ->
            val cid = clusterIds[idx]
            val finalRole = when {
                cid == -1 -> ClusterNodeRole.NOISE
                isCore[idx] -> ClusterNodeRole.CORE
                else -> ClusterNodeRole.BORDER
            }
            ClusteredPoint(point = pt, clusterId = cid, role = finalRole)
        }
        val finalCentroids = ClusteringEvaluation.computeClusterCentroids(finalAssignments)
        val finalEval = ClusteringEvaluation.evaluate(finalAssignments, finalCentroids, metric)
        val finalCoreCount = finalAssignments.count { it.role == ClusterNodeRole.CORE }
        val finalBorderCount = finalAssignments.count { it.role == ClusterNodeRole.BORDER }
        val finalNoiseCount = finalAssignments.count { it.role == ClusterNodeRole.NOISE }

        snapshots.add(
            ClusteringIterationSnapshot(
                stepIndex = stepCounter,
                summaryText = "Complete: $currentClusterIndex clusters found ($finalCoreCount core, $finalBorderCount border, $finalNoiseCount noise)",
                assignments = finalAssignments,
                centroids = finalCentroids,
                isConverged = true,
                inertiaWcss = finalEval.inertiaWcss,
                silhouetteScore = finalEval.silhouetteScore,
                corePointCount = finalCoreCount,
                borderPointCount = finalBorderCount,
                noisePointCount = finalNoiseCount,
                clusterStats = finalEval.clusterStats
            )
        )

        return snapshots
    }

    /**
     * Compact primitive int buffer to avoid boxing overhead during neighborhood construction.
     */
    private class IntArrayList(initialCapacity: Int = 16) {
        private var data = IntArray(initialCapacity)
        var size: Int = 0
            private set

        fun add(element: Int) {
            if (size == data.size) {
                data = data.copyOf(data.size * 2)
            }
            data[size++] = element
        }

        fun toIntArray(): IntArray = data.copyOf(size)
    }
}
