package com.example.clustering

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Facade in the `com.example.clustering` package providing unified synchronous (main-thread)
 * and asynchronous (coroutine-dispatched) execution entry points for K-Means and DBSCAN.
 *
 * - Datasets with `points.size <= smallDatasetThreshold` (default 250 points) execute
 *   synchronously without thread-switch overhead when using [runKMeansAdaptive] or [runDbscanAdaptive],
 *   or can be called directly from the main thread via [runKMeansSync] / [runDbscanSync].
 * - Datasets exceeding `smallDatasetThreshold` automatically offload to [largeDatasetDispatcher]
 *   (`Dispatchers.Default`) inside a coroutine with cooperative cancellation checks.
 */
class ClusteringRunner(
    val smallDatasetThreshold: Int = ClusteringAlgorithm.DEFAULT_SMALL_DATASET_THRESHOLD,
    private val largeDatasetDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val kMeansClusterer: KMeansClusterer = KMeansClusterer(),
    private val dbscanClusterer: DbscanClusterer = DbscanClusterer()
) {

    /**
     * Runs K-Means synchronously on the calling thread (ideal for small datasets on the main thread).
     */
    fun runKMeansSync(
        points: List<ClusterPoint>,
        parameters: KMeansParameters = KMeansParameters()
    ): ClusteringExecutionResult {
        return kMeansClusterer.fit(points, parameters)
    }

    /**
     * Runs K-Means adaptively: executes immediately on the current thread if
     * `points.size <= smallDatasetThreshold`, or dispatches to [largeDatasetDispatcher]
     * inside a coroutine for larger datasets.
     */
    suspend fun runKMeansAdaptive(
        points: List<ClusterPoint>,
        parameters: KMeansParameters = KMeansParameters()
    ): ClusteringExecutionResult {
        return kMeansClusterer.fitAsync(
            points = points,
            config = parameters,
            smallDatasetThreshold = smallDatasetThreshold,
            largeDatasetDispatcher = largeDatasetDispatcher
        )
    }

    /**
     * Runs DBSCAN synchronously on the calling thread (ideal for small datasets on the main thread).
     */
    fun runDbscanSync(
        points: List<ClusterPoint>,
        parameters: DbscanParameters = DbscanParameters()
    ): ClusteringExecutionResult {
        return dbscanClusterer.fit(points, parameters)
    }

    /**
     * Runs DBSCAN adaptively: executes immediately on the current thread if
     * `points.size <= smallDatasetThreshold`, or dispatches to [largeDatasetDispatcher]
     * inside a coroutine for larger datasets.
     */
    suspend fun runDbscanAdaptive(
        points: List<ClusterPoint>,
        parameters: DbscanParameters = DbscanParameters()
    ): ClusteringExecutionResult {
        return dbscanClusterer.fitAsync(
            points = points,
            config = parameters,
            smallDatasetThreshold = smallDatasetThreshold,
            largeDatasetDispatcher = largeDatasetDispatcher
        )
    }
}
