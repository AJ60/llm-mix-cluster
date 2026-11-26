package com.example.domain

import com.example.clustering.ClusterCentroid
import com.example.clustering.ClusterNodeRole
import com.example.clustering.ClusterPoint
import com.example.clustering.ClusteringIterationSnapshot
import com.example.clustering.ClusteringRunner
import com.example.clustering.DbscanParameters
import com.example.clustering.DistanceMetric
import com.example.clustering.KMeansParameters
import com.example.model.CentroidState
import com.example.model.ClusterSummary
import com.example.model.ClusteringStepFrame
import com.example.model.Coordinate
import com.example.model.DataPoint
import com.example.model.DbscanConfig
import com.example.model.KMeansConfig
import com.example.model.PointRole
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Adapter bridging the UI-facing domain models ([DataPoint], [ClusteringStepFrame])
 * with the pure Kotlin, UI-decoupled `com.example.clustering` package ([KMeansClusterer], [DbscanClusterer]).
 */
object ClusteringEngine {

    private val defaultRunner = ClusteringRunner(
        smallDatasetThreshold = 200,
        largeDatasetDispatcher = Dispatchers.Default
    )

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return DistanceMetric.EUCLIDEAN.distance(
            doubleArrayOf(x1.toDouble(), y1.toDouble()),
            doubleArrayOf(x2.toDouble(), y2.toDouble())
        ).toFloat()
    }

    /**
     * Synchronous K-Means execution (suitable for small datasets on the main thread or unit tests).
     */
    fun computeKMeansFrames(
        rawPoints: List<DataPoint>,
        config: KMeansConfig,
        seed: Long = 42L
    ): List<ClusteringStepFrame> {
        val purePoints = rawPoints.map { it.toClusterPoint() }
        val params = KMeansParameters(
            k = config.k,
            useKMeansPlusPlus = config.useKMeansPlusPlus,
            maxIterations = config.maxIterations,
            randomSeed = seed
        )
        return defaultRunner.runKMeansSync(purePoints, params).steps.map { it.toStepFrame() }
    }

    /**
     * Adaptive coroutine K-Means execution: runs directly on the calling thread for small
     * datasets (`<= smallDatasetThreshold`) and dispatches to [dispatcher] for larger datasets.
     */
    suspend fun computeKMeansFramesAdaptive(
        rawPoints: List<DataPoint>,
        config: KMeansConfig,
        seed: Long = 42L,
        smallDatasetThreshold: Int = 200,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<ClusteringStepFrame> {
        val runner = ClusteringRunner(
            smallDatasetThreshold = smallDatasetThreshold,
            largeDatasetDispatcher = dispatcher
        )
        val purePoints = rawPoints.map { it.toClusterPoint() }
        val params = KMeansParameters(
            k = config.k,
            useKMeansPlusPlus = config.useKMeansPlusPlus,
            maxIterations = config.maxIterations,
            randomSeed = seed
        )
        return runner.runKMeansAdaptive(purePoints, params).steps.map { it.toStepFrame() }
    }

    /**
     * Synchronous DBSCAN execution (suitable for small datasets on the main thread or unit tests).
     */
    fun computeDbscanFrames(
        rawPoints: List<DataPoint>,
        config: DbscanConfig
    ): List<ClusteringStepFrame> {
        val purePoints = rawPoints.map { it.toClusterPoint() }
        val params = DbscanParameters(
            epsilon = config.epsilon.toDouble(),
            minPoints = config.minPts
        )
        return defaultRunner.runDbscanSync(purePoints, params).steps.map { it.toStepFrame() }
    }

    /**
     * Adaptive coroutine DBSCAN execution: runs directly on the calling thread for small
     * datasets (`<= smallDatasetThreshold`) and dispatches to [dispatcher] for larger datasets.
     */
    suspend fun computeDbscanFramesAdaptive(
        rawPoints: List<DataPoint>,
        config: DbscanConfig,
        smallDatasetThreshold: Int = 200,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): List<ClusteringStepFrame> {
        val runner = ClusteringRunner(
            smallDatasetThreshold = smallDatasetThreshold,
            largeDatasetDispatcher = dispatcher
        )
        val purePoints = rawPoints.map { it.toClusterPoint() }
        val params = DbscanParameters(
            epsilon = config.epsilon.toDouble(),
            minPoints = config.minPts
        )
        return runner.runDbscanAdaptive(purePoints, params).steps.map { it.toStepFrame() }
    }

    private fun DataPoint.toClusterPoint(): ClusterPoint = ClusterPoint(
        id = id,
        x = x.toDouble(),
        y = y.toDouble()
    )

    private fun ClusteringIterationSnapshot.toStepFrame(): ClusteringStepFrame {
        return ClusteringStepFrame(
            stepIndex = stepIndex,
            description = summaryText,
            points = assignments.map { clustered ->
                DataPoint(
                    id = clustered.point.id,
                    x = clustered.point.x.toFloat(),
                    y = clustered.point.y.toFloat(),
                    clusterId = clustered.clusterId,
                    role = clustered.role.toPointRole()
                )
            },
            centroids = centroids.map { it.toCentroidState() },
            isConverged = isConverged,
            wcss = inertiaWcss.toFloat(),
            silhouetteScore = silhouetteScore.toFloat(),
            noiseCount = noisePointCount,
            coreCount = corePointCount,
            borderCount = borderPointCount,
            clusterSummaries = clusterStats.map { stat ->
                ClusterSummary(
                    clusterId = stat.clusterId,
                    count = stat.memberCount,
                    percentage = stat.percentage.toFloat(),
                    centroidX = stat.centroid.getOrElse(0) { 0.0 }.toFloat(),
                    centroidY = stat.centroid.getOrElse(1) { 0.0 }.toFloat(),
                    avgIntraDistance = stat.averageIntraClusterDistance.toFloat()
                )
            }
        )
    }

    private fun ClusterCentroid.toCentroidState(): CentroidState {
        return CentroidState(
            clusterId = clusterId,
            x = x.toFloat(),
            y = y.toFloat(),
            trail = history.map { coords ->
                Coordinate(
                    x = coords.getOrElse(0) { 0.0 }.toFloat(),
                    y = coords.getOrElse(1) { 0.0 }.toFloat()
                )
            }
        )
    }

    private fun ClusterNodeRole.toPointRole(): PointRole = when (this) {
        ClusterNodeRole.STANDARD -> PointRole.STANDARD
        ClusterNodeRole.CORE -> PointRole.CORE
        ClusterNodeRole.BORDER -> PointRole.BORDER
        ClusterNodeRole.NOISE -> PointRole.NOISE
    }
}
