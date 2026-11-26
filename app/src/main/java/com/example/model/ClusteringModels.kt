package com.example.model

enum class AlgorithmType(val displayName: String) {
    KMEANS("K-Means"),
    DBSCAN("DBSCAN")
}

enum class PointRole {
    STANDARD,
    CORE,
    BORDER,
    NOISE
}

enum class CanvasToolMode(val label: String) {
    ADD("Tap Add"),
    BRUSH("Brush"),
    ERASE("Erase"),
    PAN("Pan/Zoom")
}

enum class PresetDatasetType(val title: String, val subtitle: String) {
    TWO_MOONS("Two Moons", "Non-convex interleaving crescents"),
    GAUSSIAN_BLOBS("Gaussian Blobs", "Four well-separated isotropic clusters"),
    CONCENTRIC_RINGS("Concentric Rings", "Nested inner ring and outer annulus"),
    ANISOTROPIC("Anisotropic Streaks", "Elongated diagonal covariance clusters"),
    SPIRAL_GALAXY("Twin Spirals", "Entangled spiral arms with background noise"),
    NOISY_CLUSTERS("Dense + Outliers", "High-density cores with uniform scatter noise")
}

data class DataPoint(
    val id: Int,
    val x: Float, // Normalized world space [0f, 1f]
    val y: Float, // Normalized world space [0f, 1f]
    val clusterId: Int = -1, // -1 = unassigned or noise
    val role: PointRole = PointRole.STANDARD
)

data class Coordinate(
    val x: Float,
    val y: Float
)

data class CentroidState(
    val clusterId: Int,
    val x: Float,
    val y: Float,
    val trail: List<Coordinate> = emptyList()
)

/**
 * User-authored text annotation anchored to a cluster area on the 2D canvas.
 * Rendered on the live canvas and exported into generated PDF summary reports.
 */
data class ClusterAnnotation(
    val id: Int,
    val clusterId: Int, // >= 0 for a specific cluster, -1 for noise/unclustered canvas area
    val x: Float,       // Normalized world coordinate [0f, 1f]
    val y: Float,       // Normalized world coordinate [0f, 1f]
    val text: String
)

data class KMeansConfig(
    val k: Int = 3,
    val useKMeansPlusPlus: Boolean = true,
    val maxIterations: Int = 25,
    val showVoronoi: Boolean = true,
    val showTrails: Boolean = true
)

data class DbscanConfig(
    val epsilon: Float = 0.085f,
    val minPts: Int = 5,
    val showEpsilonRings: Boolean = true
)

data class ClusterSummary(
    val clusterId: Int,
    val count: Int,
    val percentage: Float,
    val centroidX: Float,
    val centroidY: Float,
    val avgIntraDistance: Float
)

data class ClusteringStepFrame(
    val stepIndex: Int,
    val description: String,
    val points: List<DataPoint>,
    val centroids: List<CentroidState>,
    val isConverged: Boolean,
    val wcss: Float,
    val silhouetteScore: Float,
    val noiseCount: Int,
    val coreCount: Int,
    val borderCount: Int,
    val clusterSummaries: List<ClusterSummary>
)

/**
 * Snapshot of a completed clustering run stored in the rolling Session History buffer
 * (last 10 runs), capturing inertia (WCSS), silhouette score, parameters, and full
 * point/frame state for 1-tap jump-back restoration.
 */
data class ClusteringSessionRun(
    val runId: Int,
    val timestampMillis: Long,
    val datasetTitle: String,
    val algorithm: AlgorithmType,
    val kMeansConfig: KMeansConfig,
    val dbscanConfig: DbscanConfig,
    val rawPoints: List<DataPoint>,
    val frames: List<ClusteringStepFrame>,
    val inertiaWcss: Float,
    val silhouetteScore: Float,
    val clusterCount: Int,
    val noiseCount: Int,
    val randomSeed: Long,
    val annotations: List<ClusterAnnotation> = emptyList()
) {
    val parameterSummary: String
        get() = when (algorithm) {
            AlgorithmType.KMEANS -> "K-Means • K=${kMeansConfig.k}"
            AlgorithmType.DBSCAN -> "DBSCAN • ε=${"%.3f".format(dbscanConfig.epsilon)}"
        }
}

enum class BatchJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

/**
 * Single dataset entry queued for sequential batch clustering and inclusion in a
 * combined multi-page PDF summary report.
 */
data class BatchQueueItem(
    val jobId: Int,
    val datasetTitle: String,
    val sourceLabel: String,
    val algorithm: AlgorithmType,
    val kMeansConfig: KMeansConfig,
    val dbscanConfig: DbscanConfig,
    val rawPoints: List<DataPoint>,
    val status: BatchJobStatus = BatchJobStatus.QUEUED,
    val resultFrame: ClusteringStepFrame? = null,
    val durationMs: Long = 0L,
    val errorMessage: String? = null,
    val annotations: List<ClusterAnnotation> = emptyList()
) {
    val parameterSummary: String
        get() = when (algorithm) {
            AlgorithmType.KMEANS -> "K-Means • K=${kMeansConfig.k}"
            AlgorithmType.DBSCAN -> "DBSCAN • ε=${"%.3f".format(dbscanConfig.epsilon)} • MinPts=${dbscanConfig.minPts}"
        }
}

data class BatchProcessingState(
    val queue: List<BatchQueueItem> = emptyList(),
    val isRunning: Boolean = false,
    val currentJobIndex: Int = -1,
    val lastCombinedPdfFileName: String? = null,
    val lastCombinedPdfSizeLabel: String? = null,
    val lastCompletedAtLabel: String? = null
) {
    val completedCount: Int
        get() = queue.count { it.status == BatchJobStatus.COMPLETED }

    val progressFraction: Float
        get() = if (queue.isEmpty()) 0f else completedCount.toFloat() / queue.size.toFloat()
}
