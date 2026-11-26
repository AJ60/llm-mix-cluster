package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.model.AlgorithmType
import com.example.model.CentroidState
import com.example.model.ClusterAnnotation
import com.example.model.ClusterSummary
import com.example.model.ClusteringStepFrame
import com.example.model.DataPoint
import com.example.model.DbscanConfig
import com.example.model.KMeansConfig
import com.example.model.PointRole
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

data class PdfReportInput(
    val datasetTitle: String,
    val algorithm: AlgorithmType,
    val kMeansConfig: KMeansConfig,
    val dbscanConfig: DbscanConfig,
    val points: List<DataPoint>,
    val centroids: List<CentroidState>,
    val frame: ClusteringStepFrame?,
    val showVoronoi: Boolean = true,
    val showTrails: Boolean = true,
    val showSpiderLines: Boolean = true,
    val showEpsilonRings: Boolean = true,
    val annotations: List<ClusterAnnotation> = emptyList()
)

data class LocalPdfReportInfo(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long
) {
    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(lastModifiedMillis))

    val formattedSize: String
        get() = if (sizeBytes < 1024) {
            "$sizeBytes B"
        } else {
            String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
        }
}

/**
 * Generates publication-grade A4 PDF summary reports of the active 2D clustering state,
 * including a vector snapshot of the clustering visualization (Voronoi regions / ε-rings,
 * spider lines, cluster-colored data points, and labeled centroids) plus key convergence
 * metrics and a per-cluster breakdown table.
 *
 * Uses zero-permission app-scoped local storage (`filesDir/pdf_reports`), SAF export
 * (`CreateDocument("application/pdf")`), and `FileProvider` sharing/opening.
 */
object PdfReportGenerator {

    private const val PDF_DIRECTORY_NAME = "pdf_reports"
    internal const val PAGE_WIDTH = 595 // A4 width in PostScript points (72 dpi)
    internal const val PAGE_HEIGHT = 842 // A4 height in PostScript points (72 dpi)

    // Matching the 10-color palette from Color.kt
    private val CLUSTER_HEX_COLORS = intArrayOf(
        0xFF3B82F6.toInt(), // 0: Cobalt Blue
        0xFF10B981.toInt(), // 1: Emerald Green
        0xFFF59E0B.toInt(), // 2: Warm Amber
        0xFFEF4444.toInt(), // 3: Coral Red
        0xFF8B5CF6.toInt(), // 4: Electric Violet
        0xFF06B6D4.toInt(), // 5: Cyan Teal
        0xFFEC4899.toInt(), // 6: Vivid Rose
        0xFF84CC16.toInt(), // 7: Lime
        0xFFF97316.toInt(), // 8: Bright Orange
        0xFF6366F1.toInt()  // 9: Indigo
    )

    fun getPdfStorageDirectory(context: Context): File {
        val dir = File(context.filesDir, PDF_DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sanitizePdfFileName(rawTitle: String): String {
        val base = rawTitle
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "clusterlab_report" }
        return if (base.endsWith(".pdf", ignoreCase = true)) {
            base
        } else {
            "${base}_report.pdf"
        }
    }

    /**
     * Generates the PDF report bytes using Android's native [PdfDocument] renderer, with a
     * pure-Kotlin PDF 1.4 vector fallback for headless JVM/Robolectric test environments where
     * native Skia PDF serialization may return an empty buffer.
     */
    fun generatePdfBytes(input: PdfReportInput): ByteArray {
        val skiaBytes = runCatching { renderWithAndroidPdfDocument(input) }.getOrNull()
        if (skiaBytes != null && skiaBytes.size >= 100 && hasPdfHeader(skiaBytes)) {
            return skiaBytes
        }
        return buildFallbackVectorPdf14(input)
    }

    /**
     * Generates and saves the PDF report into zero-permission local app storage (`filesDir/pdf_reports`).
     */
    fun savePdfReportToLocalDevice(
        context: Context,
        input: PdfReportInput,
        desiredName: String = input.datasetTitle
    ): LocalPdfReportInfo {
        val dir = getPdfStorageDirectory(context)
        val fileName = sanitizePdfFileName(desiredName)
        val targetFile = File(dir, fileName)
        val bytes = generatePdfBytes(input)
        targetFile.writeBytes(bytes)
        return inspectPdfFile(targetFile)
    }

    /**
     * Writes the generated PDF report directly into a caller-provided [OutputStream]
     * (e.g., from Android's zero-permission SAF `CreateDocument("application/pdf")` picker).
     */
    fun writePdfReportToStream(input: PdfReportInput, outputStream: OutputStream) {
        val bytes = generatePdfBytes(input)
        outputStream.write(bytes)
        outputStream.flush()
    }

    fun listLocalPdfReports(context: Context): List<LocalPdfReportInfo> {
        val dir = getPdfStorageDirectory(context)
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".pdf", ignoreCase = true)
        } ?: return emptyList()
        return files
            .map { inspectPdfFile(it) }
            .sortedByDescending { it.lastModifiedMillis }
    }

    fun deleteLocalPdfReport(context: Context, fileName: String): Boolean {
        val file = File(getPdfStorageDirectory(context), sanitizePdfFileName(fileName))
        return file.exists() && file.delete()
    }

    fun openLocalPdfReport(context: Context, fileName: String): Boolean {
        val file = File(getPdfStorageDirectory(context), sanitizePdfFileName(fileName))
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(viewIntent, "Open PDF Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    fun shareLocalPdfReport(context: Context, fileName: String): Boolean {
        val file = File(getPdfStorageDirectory(context), sanitizePdfFileName(fileName))
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$fileName — ClusterLab Summary Report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share PDF Report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    internal fun inspectPdfFile(file: File): LocalPdfReportInfo {
        return LocalPdfReportInfo(
            fileName = file.name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            lastModifiedMillis = file.lastModified()
        )
    }

    internal fun hasPdfHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 5 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4] == '-'.code.toByte()
    }

    internal fun clusterColorInt(clusterId: Int): Int {
        if (clusterId < 0) return 0xFF94A3B8.toInt()
        return CLUSTER_HEX_COLORS[clusterId % CLUSTER_HEX_COLORS.size]
    }

    internal fun silhouetteInterpretation(score: Float): String = when {
        score >= 0.70f -> "Strong separation"
        score >= 0.50f -> "Good structure"
        score >= 0.25f -> "Moderate overlap"
        score > 0.0f -> "Weak separation"
        else -> "Unclustered / sparse"
    }

    internal fun hyperparameterSummary(input: PdfReportInput): String {
        return when (input.algorithm) {
            AlgorithmType.KMEANS -> {
                val init = if (input.kMeansConfig.useKMeansPlusPlus) "K-Means++" else "Random Init"
                "K-Means  •  K = ${input.kMeansConfig.k}  •  $init"
            }
            AlgorithmType.DBSCAN -> {
                String.format(
                    Locale.US,
                    "DBSCAN  •  Epsilon = %.3f  •  MinPts = %d",
                    input.dbscanConfig.epsilon,
                    input.dbscanConfig.minPts
                )
            }
        }
    }

    // =========================================================================
    // 1. Native Android Skia PdfDocument Vector Renderer
    // =========================================================================
    private fun renderWithAndroidPdfDocument(input: PdfReportInput): ByteArray {
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create()
            val page = doc.startPage(pageInfo)
            drawReportPageOnCanvas(page.canvas, input)
            doc.finishPage(page)

            val baos = ByteArrayOutputStream()
            doc.writeTo(baos)
            return baos.toByteArray()
        } finally {
            doc.close()
        }
    }

    internal fun drawReportPageOnCanvas(
        canvas: Canvas,
        input: PdfReportInput,
        pageNumber: Int = 1,
        totalPages: Int = 1,
        batchRunLabel: String? = null
    ) {
        val margin = 36f
        val contentWidth = PAGE_WIDTH - margin * 2f
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        // Clean white page background
        val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), bgPaint)

        // --- HEADER BANNER ---
        val headerRect = RectF(0f, 0f, PAGE_WIDTH.toFloat(), 92f)
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        canvas.drawRect(headerRect, headerPaint)

        val accentBar = Paint().apply { color = 0xFF2563EB.toInt() }
        canvas.drawRect(0f, 88f, PAGE_WIDTH.toFloat(), 92f, accentBar)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 19f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF60A5FA.toInt()
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val headerTitle = if (batchRunLabel != null) {
            "ClusterLab — $batchRunLabel"
        } else {
            "ClusterLab — Clustering Analysis Report"
        }
        canvas.drawText(headerTitle, margin, 36f, titlePaint)
        canvas.drawText(
            "Dataset: ${input.datasetTitle}   |   ${hyperparameterSummary(input)}",
            margin,
            56f,
            subtitlePaint
        )
        canvas.drawText("Generated: $generatedAt", margin, 74f, badgePaint)

        // --- SECTION 1: VISUALIZATION SNAPSHOT ---
        var cursorY = 114f
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("1. Clustering Visualization Snapshot", margin, cursorY, sectionTitlePaint)
        cursorY += 10f

        val plotRect = RectF(margin, cursorY, margin + contentWidth, cursorY + 272f)
        drawClusterPlotSnapshot(canvas, plotRect, input)
        cursorY = plotRect.bottom + 20f

        // --- SECTION 2: KEY STATISTICS KPI GRID ---
        canvas.drawText("2. Key Convergence & Quality Statistics", margin, cursorY, sectionTitlePaint)
        cursorY += 10f

        val frame = input.frame
        val summaries = frame?.clusterSummaries.orEmpty()
        val clusterCount = if (summaries.isNotEmpty()) {
            summaries.size
        } else {
            input.points.map { it.clusterId }.filter { it >= 0 }.distinct().size
        }
        val silhouette = frame?.silhouetteScore ?: 0f
        val wcss = frame?.wcss ?: 0f
        val statusText = when {
            frame == null -> "Raw Points"
            frame.isConverged -> "Converged (Step ${frame.stepIndex})"
            else -> "In Progress (Step ${frame.stepIndex})"
        }

        val cardGap = 10f
        val cardWidth = (contentWidth - cardGap * 3f) / 4f
        val cardHeight = 62f

        val thirdMetricLabel = if (input.algorithm == AlgorithmType.KMEANS) "WCSS (INERTIA)" else "CORE / BORDER"
        val thirdMetricValue = if (input.algorithm == AlgorithmType.KMEANS) {
            String.format(Locale.US, "%.4f", wcss)
        } else {
            "${frame?.coreCount ?: 0} / ${frame?.borderCount ?: 0}"
        }
        val thirdMetricSub = if (input.algorithm == AlgorithmType.KMEANS) {
            "Lower is tighter"
        } else {
            "Noise: ${frame?.noiseCount ?: 0} pts"
        }

        val kpis = listOf(
            Triple("DATA POINTS", "${input.points.size}", "$clusterCount cluster${if (clusterCount == 1) "" else "s"} found"),
            Triple("SILHOUETTE", String.format(Locale.US, "%.3f", silhouette), silhouetteInterpretation(silhouette)),
            Triple(thirdMetricLabel, thirdMetricValue, thirdMetricSub),
            Triple("STATUS", if (frame?.isConverged == true) "Converged" else "Active", statusText)
        )

        kpis.forEachIndexed { idx, (label, value, sub) ->
            val left = margin + idx * (cardWidth + cardGap)
            val rect = RectF(left, cursorY, left + cardWidth, cursorY + cardHeight)
            drawKpiCard(canvas, rect, label, value, sub)
        }
        cursorY += cardHeight + 22f

        // --- SECTION 3: PER-CLUSTER BREAKDOWN TABLE ---
        canvas.drawText("3. Cluster Breakdown & Centroid Summary", margin, cursorY, sectionTitlePaint)
        cursorY += 10f
        val tableBottomY = drawClusterTable(
            canvas,
            margin,
            cursorY,
            contentWidth,
            summaries,
            frame?.noiseCount ?: 0,
            input.points.size
        )

        // --- SECTION 4: CLUSTER ANNOTATIONS (if user annotated any cluster area) ---
        if (input.annotations.isNotEmpty() && tableBottomY + 42f < PAGE_HEIGHT - 44f) {
            val annTopY = tableBottomY + 18f
            canvas.drawText("4. Cluster Annotations", margin, annTopY, sectionTitlePaint)
            drawAnnotationsSection(
                canvas = canvas,
                left = margin,
                top = annTopY + 8f,
                width = contentWidth,
                maxBottomY = PAGE_HEIGHT - 44f,
                annotations = input.annotations
            )
        }

        // --- FOOTER ---
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 8.5f
        }
        val dividerPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 1f
        }
        canvas.drawLine(margin, PAGE_HEIGHT - 36f, PAGE_WIDTH - margin, PAGE_HEIGHT - 36f, dividerPaint)
        canvas.drawText(
            "ClusterLab v1.0 (AJ60)  •  Local Device Analysis Report  •  Page $pageNumber of $totalPages",
            margin,
            PAGE_HEIGHT - 20f,
            footerPaint
        )
    }

    private fun drawClusterPlotSnapshot(canvas: Canvas, bounds: RectF, input: PdfReportInput) {
        val plotBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt() }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        canvas.drawRoundRect(bounds, 10f, 10f, plotBg)

        val padLeft = 34f
        val padRight = 18f
        val padTop = 16f
        val padBottom = 24f
        val inner = RectF(
            bounds.left + padLeft,
            bounds.top + padTop,
            bounds.right - padRight,
            bounds.bottom - padBottom
        )

        // Optional K-Means Voronoi soft background cells
        if (input.algorithm == AlgorithmType.KMEANS && input.showVoronoi && input.centroids.size >= 2) {
            val cols = 36
            val rows = 22
            val cellW = inner.width() / cols
            val cellH = inner.height() / rows
            val cellPaint = Paint().apply { style = Paint.Style.FILL }
            for (r in 0 until rows) {
                val wy = 1f - ((r + 0.5f) / rows)
                for (c in 0 until cols) {
                    val wx = (c + 0.5f) / cols
                    var bestCluster = 0
                    var bestDist = Float.MAX_VALUE
                    for (centroid in input.centroids) {
                        val dx = wx - centroid.x
                        val dy = wy - centroid.y
                        val d = dx * dx + dy * dy
                        if (d < bestDist) {
                            bestDist = d
                            bestCluster = centroid.clusterId
                        }
                    }
                    val baseColor = clusterColorInt(bestCluster)
                    cellPaint.color = Color.argb(
                        26,
                        Color.red(baseColor),
                        Color.green(baseColor),
                        Color.blue(baseColor)
                    )
                    val cx = inner.left + c * cellW
                    val cy = inner.top + r * cellH
                    canvas.drawRect(cx, cy, cx + cellW + 0.5f, cy + cellH + 0.5f, cellPaint)
                }
            }
        }

        // Coordinate grid lines & axis ticks
        val gridPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 0.75f
        }
        val axisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 8f
        }
        for (i in 0..4) {
            val frac = i / 4f
            val x = inner.left + frac * inner.width()
            val y = inner.bottom - frac * inner.height()
            canvas.drawLine(x, inner.top, x, inner.bottom, gridPaint)
            canvas.drawLine(inner.left, y, inner.right, y, gridPaint)
            val tickLabel = String.format(Locale.US, "%.2f", frac)
            canvas.drawText(tickLabel, x - 9f, inner.bottom + 13f, axisLabelPaint)
            canvas.drawText(tickLabel, bounds.left + 6f, y + 3f, axisLabelPaint)
        }

        fun toCanvasX(wx: Float): Float = inner.left + wx.coerceIn(0f, 1f) * inner.width()
        fun toCanvasY(wy: Float): Float = inner.bottom - wy.coerceIn(0f, 1f) * inner.height()

        // DBSCAN epsilon rings around core points
        if (input.algorithm == AlgorithmType.DBSCAN && input.showEpsilonRings) {
            val epsRadiusPx = input.dbscanConfig.epsilon * min(inner.width(), inner.height())
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.8f
            }
            input.points.filter { it.role == PointRole.CORE && it.clusterId >= 0 }.take(60).forEach { pt ->
                val c = clusterColorInt(pt.clusterId)
                ringPaint.color = Color.argb(45, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawCircle(toCanvasX(pt.x), toCanvasY(pt.y), epsRadiusPx, ringPaint)
            }
        }

        // K-Means spider lines connecting points to their assigned centroid
        if (input.algorithm == AlgorithmType.KMEANS && input.showSpiderLines && input.centroids.isNotEmpty()) {
            val centroidMap = input.centroids.associateBy { it.clusterId }
            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.8f }
            for (pt in input.points) {
                val centroid = centroidMap[pt.clusterId] ?: continue
                val c = clusterColorInt(pt.clusterId)
                linePaint.color = Color.argb(65, Color.red(c), Color.green(c), Color.blue(c))
                canvas.drawLine(
                    toCanvasX(pt.x),
                    toCanvasY(pt.y),
                    toCanvasX(centroid.x),
                    toCanvasY(centroid.y),
                    linePaint
                )
            }
        }

        // K-Means centroid movement trails
        if (input.algorithm == AlgorithmType.KMEANS && input.showTrails) {
            val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f)
            }
            for (centroid in input.centroids) {
                if (centroid.trail.size >= 2) {
                    val c = clusterColorInt(centroid.clusterId)
                    trailPaint.color = Color.argb(180, Color.red(c), Color.green(c), Color.blue(c))
                    val path = Path()
                    centroid.trail.forEachIndexed { idx, coord ->
                        val px = toCanvasX(coord.x)
                        val py = toCanvasY(coord.y)
                        if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }
                    canvas.drawPath(path, trailPaint)
                }
            }
        }

        // Data points
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.1f
        }
        for (pt in input.points) {
            val px = toCanvasX(pt.x)
            val py = toCanvasY(pt.y)
            if (pt.role == PointRole.NOISE || pt.clusterId < 0) {
                strokePaint.color = 0xFF64748B.toInt()
                canvas.drawLine(px - 2.6f, py - 2.6f, px + 2.6f, py + 2.6f, strokePaint)
                canvas.drawLine(px - 2.6f, py + 2.6f, px + 2.6f, py - 2.6f, strokePaint)
            } else {
                val c = clusterColorInt(pt.clusterId)
                if (pt.role == PointRole.BORDER) {
                    fillPaint.color = Color.WHITE
                    strokePaint.color = c
                    canvas.drawCircle(px, py, 3.6f, fillPaint)
                    canvas.drawCircle(px, py, 3.6f, strokePaint)
                } else {
                    fillPaint.color = c
                    strokePaint.color = Color.WHITE
                    canvas.drawCircle(px, py, 3.8f, fillPaint)
                    canvas.drawCircle(px, py, 3.8f, strokePaint)
                }
            }
        }

        // Centroid crosshairs & badges
        val centroidTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        for (centroid in input.centroids) {
            val cx = toCanvasX(centroid.x)
            val cy = toCanvasY(centroid.y)
            val c = clusterColorInt(centroid.clusterId)
            val diamond = Path().apply {
                moveTo(cx, cy - 7f)
                lineTo(cx + 7f, cy)
                lineTo(cx, cy + 7f)
                lineTo(cx - 7f, cy)
                close()
            }
            fillPaint.color = c
            strokePaint.color = 0xFF0F172A.toInt()
            strokePaint.strokeWidth = 1.5f
            canvas.drawPath(diamond, fillPaint)
            canvas.drawPath(diamond, strokePaint)
            canvas.drawText("C${centroid.clusterId + 1}", cx + 9f, cy + 3f, centroidTextPaint)
        }

        // User-authored cluster area annotation pins & callout tags on the plot
        if (input.annotations.isNotEmpty()) {
            val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xF2FFFFFF.toInt()
                style = Paint.Style.FILL
            }
            val tagBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.1f
            }
            val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF0F172A.toInt()
                textSize = 7.6f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val leaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.1f }

            input.annotations.take(12).forEach { ann ->
                val ax = toCanvasX(ann.x)
                val ay = toCanvasY(ann.y)
                val accent = clusterColorInt(ann.clusterId)
                val prefix = if (ann.clusterId >= 0) "C${ann.clusterId + 1}: " else "Note: "
                val shortText = if (ann.text.length > 26) ann.text.take(25) + "…" else ann.text
                val fullLabel = "$prefix$shortText"

                val textW = tagTextPaint.measureText(fullLabel)
                val pillW = textW + 12f
                val pillH = 14f
                val rawX = ax + 6f
                val rawY = ay - pillH - 6f
                val pillX = rawX.coerceIn(inner.left + 2f, (inner.right - pillW - 2f).coerceAtLeast(inner.left + 2f))
                val pillY = rawY.coerceIn(inner.top + 2f, (inner.bottom - pillH - 2f).coerceAtLeast(inner.top + 2f))

                leaderPaint.color = accent
                canvas.drawLine(ax, ay, pillX + pillW * 0.25f, pillY + pillH, leaderPaint)

                pinPaint.color = accent
                canvas.drawCircle(ax, ay, 3.6f, pinPaint)
                strokePaint.color = Color.WHITE
                strokePaint.strokeWidth = 1.0f
                canvas.drawCircle(ax, ay, 3.6f, strokePaint)

                val pillRect = RectF(pillX, pillY, pillX + pillW, pillY + pillH)
                tagBorderPaint.color = accent
                canvas.drawRoundRect(pillRect, 4f, 4f, tagBgPaint)
                canvas.drawRoundRect(pillRect, 4f, 4f, tagBorderPaint)
                canvas.drawText(fullLabel, pillX + 6f, pillY + 10f, tagTextPaint)
            }
        }

        canvas.drawRoundRect(bounds, 10f, 10f, borderPaint)
    }

    private fun drawKpiCard(canvas: Canvas, rect: RectF, label: String, value: String, subtext: String) {
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F5F9.toInt() }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
        }
        canvas.drawRoundRect(rect, 8f, 8f, bg)
        canvas.drawRoundRect(rect, 8f, 8f, border)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt()
            textSize = 7.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 14.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 8f
        }
        canvas.drawText(label, rect.left + 10f, rect.top + 16f, labelPaint)
        canvas.drawText(value, rect.left + 10f, rect.top + 37f, valuePaint)
        canvas.drawText(subtext, rect.left + 10f, rect.top + 52f, subPaint)
    }

    private fun drawClusterTable(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        summaries: List<ClusterSummary>,
        noiseCount: Int,
        totalPoints: Int
    ): Float {
        val headerBg = Paint().apply { color = 0xFF1E293B.toInt() }
        val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 9.2f
        }
        val altRowBg = Paint().apply { color = 0xFFF8FAFC.toInt() }
        val borderPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 0.8f
        }
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val rowHeight = 24f
        canvas.drawRect(left, top, left + width, top + rowHeight, headerBg)

        val colCluster = left + 12f
        val colCount = left + 145f
        val colShare = left + 225f
        val colCentroid = left + 315f
        val colSpread = left + 435f

        val hy = top + 15.5f
        canvas.drawText("Cluster", colCluster, hy, headerText)
        canvas.drawText("Points", colCount, hy, headerText)
        canvas.drawText("Share", colShare, hy, headerText)
        canvas.drawText("Centroid (X, Y)", colCentroid, hy, headerText)
        canvas.drawText("Avg Spread", colSpread, hy, headerText)

        var y = top + rowHeight
        val maxRows = 10
        summaries.take(maxRows).forEachIndexed { idx, summary ->
            if (idx % 2 == 1) {
                canvas.drawRect(left, y, left + width, y + rowHeight, altRowBg)
            }
            swatchPaint.color = clusterColorInt(summary.clusterId)
            canvas.drawCircle(colCluster + 5f, y + 12f, 4.5f, swatchPaint)
            canvas.drawText("Cluster ${summary.clusterId + 1}", colCluster + 16f, y + 15.5f, rowText)
            canvas.drawText("${summary.count}", colCount, y + 15.5f, rowText)
            canvas.drawText(String.format(Locale.US, "%.1f%%", summary.percentage), colShare, y + 15.5f, rowText)
            canvas.drawText(
                String.format(Locale.US, "(%.3f, %.3f)", summary.centroidX, summary.centroidY),
                colCentroid,
                y + 15.5f,
                rowText
            )
            canvas.drawText(
                String.format(Locale.US, "%.4f", summary.avgIntraDistance),
                colSpread,
                y + 15.5f,
                rowText
            )
            y += rowHeight
            canvas.drawLine(left, y, left + width, y, borderPaint)
        }

        if (noiseCount > 0) {
            val noisePct = if (totalPoints > 0) (noiseCount * 100f / totalPoints) else 0f
            swatchPaint.color = 0xFF94A3B8.toInt()
            canvas.drawCircle(colCluster + 5f, y + 12f, 4.5f, swatchPaint)
            canvas.drawText("Noise / Outliers", colCluster + 16f, y + 15.5f, rowText)
            canvas.drawText("$noiseCount", colCount, y + 15.5f, rowText)
            canvas.drawText(String.format(Locale.US, "%.1f%%", noisePct), colShare, y + 15.5f, rowText)
            canvas.drawText("—", colCentroid, y + 15.5f, rowText)
            canvas.drawText("—", colSpread, y + 15.5f, rowText)
            y += rowHeight
            canvas.drawLine(left, y, left + width, y, borderPaint)
        }

        if (summaries.isEmpty() && noiseCount == 0) {
            canvas.drawText(
                "No clusters computed yet. Add points on the canvas to generate cluster metrics.",
                colCluster,
                y + 16f,
                rowText
            )
            y += 22f
        }
        return y
    }

    private fun drawAnnotationsSection(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        maxBottomY: Float,
        annotations: List<ClusterAnnotation>
    ) {
        val rowBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt() }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
        }
        val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 8.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val coordTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 8.2f
        }
        val noteTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E293B.toInt()
            textSize = 9.0f
        }

        var y = top
        val rowHeight = 22f
        val rowGap = 4f
        var rendered = 0
        for (ann in annotations) {
            if (y + rowHeight > maxBottomY) {
                val remaining = annotations.size - rendered
                if (remaining > 0 && y + 10f <= maxBottomY) {
                    canvas.drawText(
                        "+ $remaining additional annotation${if (remaining == 1) "" else "s"} omitted to fit page",
                        left + 8f,
                        y + 9f,
                        coordTextPaint
                    )
                }
                break
            }
            val rect = RectF(left, y, left + width, y + rowHeight)
            canvas.drawRoundRect(rect, 5f, 5f, rowBg)
            canvas.drawRoundRect(rect, 5f, 5f, borderPaint)

            val accent = clusterColorInt(ann.clusterId)
            swatchPaint.color = accent
            canvas.drawCircle(left + 12f, y + rowHeight / 2f, 4f, swatchPaint)

            val label = if (ann.clusterId >= 0) "Cluster ${ann.clusterId + 1}" else "Canvas Note"
            canvas.drawText(label, left + 22f, y + 14.2f, badgeTextPaint)

            val coord = String.format(Locale.US, "(%.2f, %.2f)", ann.x, ann.y)
            canvas.drawText(coord, left + 88f, y + 14.2f, coordTextPaint)

            val noteStartX = left + 148f
            val maxNoteWidth = (left + width - 10f - noteStartX).coerceAtLeast(80f)
            val displayNote = ellipsizeToWidth(ann.text, noteTextPaint, maxNoteWidth)
            canvas.drawText(displayNote, noteStartX, y + 14.2f, noteTextPaint)

            y += rowHeight + rowGap
            rendered++
        }
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end.coerceAtLeast(1)) + "…"
    }

    // =========================================================================
    // 2. Pure-Kotlin Valid PDF 1.4 Vector Fallback (used in JVM/Robolectric tests)
    // =========================================================================
    internal fun buildFallbackPageStream(
        input: PdfReportInput,
        pageNumber: Int = 1,
        totalPages: Int = 1,
        batchRunLabel: String? = null
    ): ByteArray {
        val stream = StringBuilder()
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val frame = input.frame
        val summaries = frame?.clusterSummaries.orEmpty()
        val silhouette = frame?.silhouetteScore ?: 0f
        val wcss = frame?.wcss ?: 0f

        fun esc(text: String): String = text
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")

        // Header banner
        stream.append("0.06 0.09 0.16 rg 0 750 595 92 re f\n")
        stream.append("0.15 0.39 0.92 rg 0 750 595 4 re f\n")
        val titleHeader = batchRunLabel?.let { "ClusterLab - $it" }
            ?: "ClusterLab - Clustering Analysis Report"
        stream.append("1 1 1 rg BT /F1 18 Tf 36 806 Td (${esc(titleHeader)}) Tj ET\n")
        stream.append("0.8 0.84 0.88 rg BT /F1 10 Tf 36 786 Td (${esc("Dataset: ${input.datasetTitle} | ${hyperparameterSummary(input)}")}) Tj ET\n")
        stream.append("0.38 0.65 0.98 rg BT /F1 9 Tf 36 768 Td (${esc("Generated: $generatedAt | Page $pageNumber of $totalPages")}) Tj ET\n")

        // Plot container (y in PDF coords is bottom-up: y=445..715)
        stream.append("0.97 0.98 0.99 rg 36 445 523 270 re f\n")
        stream.append("0.8 0.84 0.88 RG 1 w 36 445 523 270 re S\n")
        val plotL = 62f
        val plotB = 468f
        val plotW = 478f
        val plotH = 228f

        // Vector points
        for (pt in input.points) {
            val c = clusterColorInt(pt.clusterId)
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val px = plotL + pt.x.coerceIn(0f, 1f) * plotW
            val py = plotB + pt.y.coerceIn(0f, 1f) * plotH
            stream.append(String.format(Locale.US, "%.2f %.2f %.2f rg %.1f %.1f 4 4 re f\n", r, g, b, px - 2f, py - 2f))
        }
        // Vector centroids
        for (centroid in input.centroids) {
            val cx = plotL + centroid.x.coerceIn(0f, 1f) * plotW
            val cy = plotB + centroid.y.coerceIn(0f, 1f) * plotH
            stream.append(String.format(Locale.US, "0.06 0.09 0.16 RG 1.5 w %.1f %.1f 8 8 re S\n", cx - 4f, cy - 4f))
        }
        // Vector annotation callout markers on the plot
        for (ann in input.annotations.take(12)) {
            val c = clusterColorInt(ann.clusterId)
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val ax = plotL + ann.x.coerceIn(0f, 1f) * plotW
            val ay = plotB + ann.y.coerceIn(0f, 1f) * plotH
            val tagPrefix = if (ann.clusterId >= 0) "C${ann.clusterId + 1}: " else "Note: "
            val tagShort = if (ann.text.length > 24) ann.text.take(23) + "..." else ann.text
            stream.append(String.format(Locale.US, "%.2f %.2f %.2f rg %.1f %.1f 6 6 re f\n", r, g, b, ax - 3f, ay - 3f))
            stream.append(
                String.format(
                    Locale.US,
                    "0.06 0.09 0.16 rg BT /F1 7.5 Tf %.1f %.1f Td (%s) Tj ET\n",
                    (ax + 6f).coerceAtMost(plotL + plotW - 72f),
                    (ay + 5f).coerceAtMost(plotB + plotH - 10f),
                    esc(tagPrefix + tagShort)
                )
            )
        }

        // Key statistics text block
        stream.append("0.06 0.09 0.16 rg BT /F1 12 Tf 36 418 Td (Key Statistics) Tj ET\n")
        val statsLine = String.format(
            Locale.US,
            "Points: %d | Clusters: %d | Silhouette: %.3f (%s) | WCSS: %.4f",
            input.points.size,
            summaries.size,
            silhouette,
            silhouetteInterpretation(silhouette),
            wcss
        )
        stream.append("0.2 0.25 0.33 rg BT /F1 10 Tf 36 398 Td (${esc(statsLine)}) Tj ET\n")

        var rowY = 368f
        summaries.take(10).forEach { s ->
            val line = String.format(
                Locale.US,
                "Cluster %d: %d pts (%.1f%%) | Centroid (%.3f, %.3f) | Spread %.4f",
                s.clusterId + 1,
                s.count,
                s.percentage,
                s.centroidX,
                s.centroidY,
                s.avgIntraDistance
            )
            stream.append(String.format(Locale.US, "0.1 0.15 0.22 rg BT /F1 9.5 Tf 36 %.1f Td (%s) Tj ET\n", rowY, esc(line)))
            rowY -= 18f
        }

        if (input.annotations.isNotEmpty() && rowY > 64f) {
            rowY -= 8f
            stream.append(
                String.format(
                    Locale.US,
                    "0.06 0.09 0.16 rg BT /F1 11.5 Tf 36 %.1f Td (4. Cluster Annotations) Tj ET\n",
                    rowY
                )
            )
            rowY -= 16f
            for (ann in input.annotations) {
                if (rowY < 42f) break
                val areaLabel = if (ann.clusterId >= 0) "Cluster ${ann.clusterId + 1}" else "Canvas Note"
                val annLine = String.format(
                    Locale.US,
                    "%s (%.2f, %.2f): %s",
                    areaLabel,
                    ann.x,
                    ann.y,
                    ann.text
                )
                stream.append(
                    String.format(
                        Locale.US,
                        "0.12 0.18 0.28 rg BT /F1 9.0 Tf 36 %.1f Td (%s) Tj ET\n",
                        rowY,
                        esc(annLine)
                    )
                )
                rowY -= 15f
            }
        }

        stream.append("0.39 0.45 0.55 rg BT /F1 8.5 Tf 36 24 Td (${esc("ClusterLab v1.0 (AJ60) | Page $pageNumber of $totalPages")}) Tj ET\n")
        return stream.toString().toByteArray(Charsets.ISO_8859_1)
    }

    internal fun assembleMultiPagePdf14(pageContentStreams: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        fun writeStr(s: String) {
            out.write(s.toByteArray(Charsets.ISO_8859_1))
        }

        val pageCount = pageContentStreams.size.coerceAtLeast(1)
        // Object layout:
        // 1: Catalog, 2: Pages, 3: Helvetica Font
        // For page index i (0..pageCount-1):
        //   pageObjId    = 4 + i * 2
        //   contentObjId = 5 + i * 2
        val kidsRefs = (0 until pageCount).joinToString(" ") { i -> "${4 + i * 2} 0 R" }

        writeStr("%PDF-1.4\n")

        offsets.add(out.size())
        writeStr("1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n")

        offsets.add(out.size())
        writeStr("2 0 obj << /Type /Pages /Kids [$kidsRefs] /Count $pageCount >> endobj\n")

        offsets.add(out.size())
        writeStr("3 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n")

        pageContentStreams.forEachIndexed { i, contentBytes ->
            val pageObjId = 4 + i * 2
            val contentObjId = 5 + i * 2
            offsets.add(out.size())
            writeStr(
                "$pageObjId 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] " +
                    "/Resources << /Font << /F1 3 0 R >> >> /Contents $contentObjId 0 R >> endobj\n"
            )
            offsets.add(out.size())
            writeStr("$contentObjId 0 obj << /Length ${contentBytes.size} >> stream\n")
            out.write(contentBytes)
            writeStr("\nendstream endobj\n")
        }

        val xrefOffset = out.size()
        writeStr("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) {
            writeStr(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        writeStr("trailer << /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        return out.toByteArray()
    }

    private fun buildFallbackVectorPdf14(input: PdfReportInput): ByteArray {
        return assembleMultiPagePdf14(listOf(buildFallbackPageStream(input, pageNumber = 1, totalPages = 1)))
    }
}
