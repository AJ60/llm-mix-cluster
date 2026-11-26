package com.example.domain

import com.example.model.CentroidState
import com.example.model.DataPoint
import com.example.model.PointRole
import com.example.model.PresetDatasetType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object DatasetGenerator {

    fun generate(
        type: PresetDatasetType,
        count: Int = 150,
        noiseSpread: Float = 0.035f,
        seed: Long = System.currentTimeMillis()
    ): List<DataPoint> {
        val rng = Random(seed)
        return when (type) {
            PresetDatasetType.TWO_MOONS -> generateTwoMoons(count, noiseSpread, rng)
            PresetDatasetType.GAUSSIAN_BLOBS -> generateGaussianBlobs(count, noiseSpread, rng)
            PresetDatasetType.CONCENTRIC_RINGS -> generateConcentricRings(count, noiseSpread, rng)
            PresetDatasetType.ANISOTROPIC -> generateAnisotropic(count, noiseSpread, rng)
            PresetDatasetType.SPIRAL_GALAXY -> generateTwinSpirals(count, noiseSpread, rng)
            PresetDatasetType.NOISY_CLUSTERS -> generateNoisyClusters(count, noiseSpread, rng)
        }
    }

    private fun generateTwoMoons(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val points = mutableListOf<DataPoint>()
        val half = count / 2
        for (i in 0 until half) {
            val angle = (i.toFloat() / half.toFloat()) * PI.toFloat()
            val x = 0.36f + 0.24f * cos(angle) + nextGaussian(rng) * noise
            val y = 0.44f - 0.22f * sin(angle) + nextGaussian(rng) * noise
            points.add(DataPoint(id = points.size + 1, x = x.clampCoord(), y = y.clampCoord()))
        }
        for (i in 0 until (count - half)) {
            val angle = (i.toFloat() / (count - half).toFloat()) * PI.toFloat()
            val x = 0.62f - 0.24f * cos(angle) + nextGaussian(rng) * noise
            val y = 0.56f + 0.22f * sin(angle) + nextGaussian(rng) * noise
            points.add(DataPoint(id = points.size + 1, x = x.clampCoord(), y = y.clampCoord()))
        }
        return points
    }

    private fun generateGaussianBlobs(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val centers = listOf(
            0.26f to 0.28f,
            0.74f to 0.27f,
            0.29f to 0.73f,
            0.72f to 0.72f
        )
        val spread = (noise * 1.65f).coerceIn(0.03f, 0.12f)
        return List(count) { idx ->
            val (cx, cy) = centers[idx % centers.size]
            val x = cx + nextGaussian(rng) * spread
            val y = cy + nextGaussian(rng) * spread
            DataPoint(id = idx + 1, x = x.clampCoord(), y = y.clampCoord())
        }
    }

    private fun generateConcentricRings(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val points = mutableListOf<DataPoint>()
        val innerCount = (count * 0.36f).toInt()
        val outerCount = count - innerCount
        val ringNoise = (noise * 0.75f).coerceIn(0.012f, 0.045f)

        for (i in 0 until innerCount) {
            val theta = (2f * PI.toFloat() * i) / innerCount
            val r = 0.14f + nextGaussian(rng) * ringNoise
            val x = 0.5f + r * cos(theta)
            val y = 0.5f + r * sin(theta)
            points.add(DataPoint(id = points.size + 1, x = x.clampCoord(), y = y.clampCoord()))
        }

        for (i in 0 until outerCount) {
            val theta = (2f * PI.toFloat() * i) / outerCount
            val r = 0.34f + nextGaussian(rng) * ringNoise
            val x = 0.5f + r * cos(theta)
            val y = 0.5f + r * sin(theta)
            points.add(DataPoint(id = points.size + 1, x = x.clampCoord(), y = y.clampCoord()))
        }
        return points
    }

    private fun generateAnisotropic(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val centers = listOf(
            0.28f to 0.34f,
            0.52f to 0.50f,
            0.74f to 0.64f
        )
        return List(count) { idx ->
            val (cx, cy) = centers[idx % centers.size]
            val u = nextGaussian(rng) * 0.11f
            val v = nextGaussian(rng) * (noise * 0.75f).coerceAtLeast(0.02f)
            val x = cx + u * 0.78f - v * 0.62f
            val y = cy + u * 0.62f + v * 0.78f
            DataPoint(id = idx + 1, x = x.clampCoord(), y = y.clampCoord())
        }
    }

    private fun generateTwinSpirals(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val points = mutableListOf<DataPoint>()
        val armCount = count / 2
        val spiralNoise = (noise * 0.55f).coerceIn(0.008f, 0.03f)
        for (i in 0 until armCount) {
            val t = i.toFloat() / armCount.toFloat()
            val angle = t * 2.4f * PI.toFloat()
            val radius = 0.06f + t * 0.34f
            val x1 = 0.5f + radius * cos(angle) + nextGaussian(rng) * spiralNoise
            val y1 = 0.5f + radius * sin(angle) + nextGaussian(rng) * spiralNoise
            points.add(DataPoint(id = points.size + 1, x = x1.clampCoord(), y = y1.clampCoord()))

            val x2 = 0.5f - radius * cos(angle) + nextGaussian(rng) * spiralNoise
            val y2 = 0.5f - radius * sin(angle) + nextGaussian(rng) * spiralNoise
            points.add(DataPoint(id = points.size + 1, x = x2.clampCoord(), y = y2.clampCoord()))
        }
        return points
    }

    private fun generateNoisyClusters(count: Int, noise: Float, rng: Random): List<DataPoint> {
        val points = mutableListOf<DataPoint>()
        val centers = listOf(
            0.26f to 0.32f,
            0.73f to 0.30f,
            0.50f to 0.72f
        )
        val outlierCount = (count * 0.14f).toInt().coerceAtLeast(10)
        val clusterCount = count - outlierCount

        for (i in 0 until clusterCount) {
            val (cx, cy) = centers[i % centers.size]
            val x = cx + nextGaussian(rng) * (noise * 1.2f).coerceIn(0.025f, 0.07f)
            val y = cy + nextGaussian(rng) * (noise * 1.2f).coerceIn(0.025f, 0.07f)
            points.add(DataPoint(id = points.size + 1, x = x.clampCoord(), y = y.clampCoord()))
        }
        for (i in 0 until outlierCount) {
            val x = 0.08f + rng.nextFloat() * 0.84f
            val y = 0.08f + rng.nextFloat() * 0.84f
            points.add(DataPoint(id = points.size + 1, x = x, y = y))
        }
        return points
    }

    private data class ParsedRawRow(
        val id: Int?,
        val x: Float,
        val y: Float,
        val clusterId: Int,
        val role: PointRole
    )

    /**
     * Parses CSV rows (`id,x,y,cluster_id,point_role`, `x,y,cluster_id`, or `x,y`),
     * TSV/whitespace numbers, or JSON arrays into normalized `[0.04f, 0.96f]` 2D DataPoints.
     */
    fun parseRawTextToPoints(rawText: String): List<DataPoint> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val parsedRows = mutableListOf<ParsedRawRow>()
        val numberRegex = Regex("""[-+]?\d*\.?\d+(?:[eE][-+]?\d+)?""")

        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            val objRegex = Regex("""\{[^}]*\}|\[[^\[\]]*\]""")
            val blocks = objRegex.findAll(trimmed).map { it.value }.toList()
            val candidateBlocks = if (blocks.isNotEmpty()) blocks else trimmed.lines()
            for (block in candidateBlocks) {
                val nums = numberRegex.findAll(block).mapNotNull { it.value.toFloatOrNull() }.toList()
                if (nums.size >= 2) {
                    parsedRows.add(
                        ParsedRawRow(
                            id = null,
                            x = nums[0],
                            y = nums[1],
                            clusterId = -1,
                            role = PointRole.STANDARD
                        )
                    )
                }
            }
        } else {
            var hasIdColumnHeader = false
            var xColIndex = 0
            var yColIndex = 1
            var clusterColIndex = -1
            var roleColIndex = -1
            var headerParsed = false

            for (line in trimmed.lines()) {
                val clean = line.trim()
                if (clean.isEmpty() || clean.startsWith("#") || clean.startsWith("//")) continue

                val tokens = clean.split(',', ';', '\t').map { it.trim() }
                val lowerTokens = tokens.map { it.lowercase(Locale.US) }

                // Check if this line is a CSV header row (e.g., "id,x,y,cluster_id,point_role" or "x,y")
                if (!headerParsed && lowerTokens.any { it == "x" || it == "y" || it == "cluster_id" || it == "point_role" }) {
                    headerParsed = true
                    hasIdColumnHeader = lowerTokens.firstOrNull() == "id"
                    val foundX = lowerTokens.indexOf("x")
                    val foundY = lowerTokens.indexOf("y")
                    if (foundX >= 0 && foundY >= 0) {
                        xColIndex = foundX
                        yColIndex = foundY
                    } else if (hasIdColumnHeader) {
                        xColIndex = 1
                        yColIndex = 2
                    }
                    clusterColIndex = lowerTokens.indexOfFirst { it == "cluster_id" || it == "cluster" }
                    roleColIndex = lowerTokens.indexOfFirst { it == "point_role" || it == "role" }
                    continue
                }

                if (headerParsed && tokens.size > maxOf(xColIndex, yColIndex)) {
                    val xVal = tokens.getOrNull(xColIndex)?.toFloatOrNull()
                    val yVal = tokens.getOrNull(yColIndex)?.toFloatOrNull()
                    if (xVal != null && yVal != null) {
                        val idVal = if (hasIdColumnHeader) tokens.getOrNull(0)?.toIntOrNull() else null
                        val clusterVal = if (clusterColIndex >= 0) {
                            tokens.getOrNull(clusterColIndex)?.toIntOrNull() ?: -1
                        } else -1
                        val roleVal = if (roleColIndex >= 0) {
                            tokens.getOrNull(roleColIndex)?.let { roleName ->
                                runCatching { PointRole.valueOf(roleName.uppercase(Locale.US)) }.getOrNull()
                            } ?: PointRole.STANDARD
                        } else PointRole.STANDARD

                        parsedRows.add(
                            ParsedRawRow(
                                id = idVal,
                                x = xVal,
                                y = yVal,
                                clusterId = clusterVal,
                                role = roleVal
                            )
                        )
                        continue
                    }
                }

                // Fallback for headerless CSV / whitespace lines:
                // Detect 5-column exported format: id,x,y,cluster_id,point_role
                if (tokens.size >= 4 &&
                    tokens[0].toIntOrNull() != null &&
                    tokens[1].toFloatOrNull() != null &&
                    tokens[2].toFloatOrNull() != null &&
                    tokens[3].toIntOrNull() != null
                ) {
                    val idVal = tokens[0].toIntOrNull()
                    val xVal = tokens[1].toFloat()
                    val yVal = tokens[2].toFloat()
                    val clusterVal = tokens[3].toIntOrNull() ?: -1
                    val roleVal = tokens.getOrNull(4)?.let { roleName ->
                        runCatching { PointRole.valueOf(roleName.uppercase(Locale.US)) }.getOrNull()
                    } ?: PointRole.STANDARD
                    parsedRows.add(
                        ParsedRawRow(
                            id = idVal,
                            x = xVal,
                            y = yVal,
                            clusterId = clusterVal,
                            role = roleVal
                        )
                    )
                    continue
                }

                val nums = numberRegex.findAll(clean).mapNotNull { it.value.toFloatOrNull() }.toList()
                if (nums.size >= 2) {
                    parsedRows.add(
                        ParsedRawRow(
                            id = null,
                            x = nums[0],
                            y = nums[1],
                            clusterId = nums.getOrNull(2)?.toInt() ?: -1,
                            role = PointRole.STANDARD
                        )
                    )
                }
            }
        }

        if (parsedRows.isEmpty()) return emptyList()

        val minX = parsedRows.minOf { it.x }
        val maxX = parsedRows.maxOf { it.x }
        val minY = parsedRows.minOf { it.y }
        val maxY = parsedRows.maxOf { it.y }

        val alreadyNormalized = minX >= 0f && maxX <= 1f && minY >= 0f && maxY <= 1f
        val rangeX = (maxX - minX).takeIf { it > 1e-6f } ?: 1f
        val rangeY = (maxY - minY).takeIf { it > 1e-6f } ?: 1f

        return parsedRows.mapIndexed { index, row ->
            val nx = if (alreadyNormalized) {
                row.x.clampCoord()
            } else {
                (0.08f + ((row.x - minX) / rangeX) * 0.84f).clampCoord()
            }
            val ny = if (alreadyNormalized) {
                row.y.clampCoord()
            } else {
                (0.08f + ((row.y - minY) / rangeY) * 0.84f).clampCoord()
            }
            DataPoint(
                id = row.id ?: (index + 1),
                x = nx,
                y = ny,
                clusterId = row.clusterId,
                role = row.role
            )
        }
    }

    /**
     * Exports a list of 2D [DataPoint]s (and optional [centroids] metadata comments)
     * into a standard locale-safe CSV string (`id,x,y,cluster_id,point_role`).
     */
    fun exportPointsToCsv(
        points: List<DataPoint>,
        centroids: List<CentroidState> = emptyList(),
        datasetTitle: String? = null
    ): String {
        val sb = StringBuilder()
        if (!datasetTitle.isNullOrBlank()) {
            sb.appendLine("# dataset: $datasetTitle")
        }
        if (centroids.isNotEmpty()) {
            centroids.forEach { c ->
                val cx = String.format(Locale.US, "%.5f", c.x)
                val cy = String.format(Locale.US, "%.5f", c.y)
                sb.appendLine("# centroid_${c.clusterId}: $cx,$cy")
            }
        }
        sb.appendLine("id,x,y,cluster_id,point_role")
        points.forEach { pt ->
            val fx = String.format(Locale.US, "%.5f", pt.x)
            val fy = String.format(Locale.US, "%.5f", pt.y)
            sb.appendLine("${pt.id},$fx,$fy,${pt.clusterId},${pt.role.name}")
        }
        return sb.toString()
    }

    private fun nextGaussian(rng: Random): Float {
        var u1 = rng.nextDouble()
        val u2 = rng.nextDouble()
        if (u1 < 1e-7) u1 = 1e-7
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    private fun Float.clampCoord(): Float = this.coerceIn(0.04f, 0.96f)
}
