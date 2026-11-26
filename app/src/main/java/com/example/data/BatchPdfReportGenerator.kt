package com.example.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.model.AlgorithmType
import com.example.model.BatchJobStatus
import com.example.model.BatchQueueItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BatchPdfReportInput(
    val batchTitle: String,
    val completedItems: List<BatchQueueItem>,
    val showVoronoi: Boolean = true,
    val showTrails: Boolean = true,
    val showSpiderLines: Boolean = true,
    val showEpsilonRings: Boolean = true
)

/**
 * Composes a combined multi-page A4 PDF summary report for a completed batch of sequential
 * clustering runs:
 *  - Page 1: Executive Batch Comparison Summary (aggregate KPIs, best Silhouette & lowest
 *    Inertia winners, and a run-by-run comparison table).
 *  - Pages 2..N+1: Individual vector visualization snapshot, quality metrics, and per-cluster
 *    centroid breakdown for each dataset run in the batch.
 */
object BatchPdfReportGenerator {

    fun generateCombinedPdfBytes(input: BatchPdfReportInput): ByteArray {
        val runs = input.completedItems.filter { it.status == BatchJobStatus.COMPLETED && it.resultFrame != null }
        val skiaBytes = runCatching { renderMultiPageWithAndroidPdfDocument(input, runs) }.getOrNull()
        if (skiaBytes != null && skiaBytes.size >= 160 && PdfReportGenerator.hasPdfHeader(skiaBytes)) {
            return skiaBytes
        }
        return buildFallbackCombinedPdf14(input, runs)
    }

    fun saveCombinedBatchPdfToLocalDevice(
        context: Context,
        input: BatchPdfReportInput,
        desiredName: String = input.batchTitle
    ): LocalPdfReportInfo {
        val dir = PdfReportGenerator.getPdfStorageDirectory(context)
        val fileName = PdfReportGenerator.sanitizePdfFileName(desiredName)
        val targetFile = File(dir, fileName)
        val bytes = generateCombinedPdfBytes(input)
        targetFile.writeBytes(bytes)
        return PdfReportGenerator.inspectPdfFile(targetFile)
    }

    fun writeCombinedBatchPdfToStream(input: BatchPdfReportInput, outputStream: OutputStream) {
        val bytes = generateCombinedPdfBytes(input)
        outputStream.write(bytes)
        outputStream.flush()
    }

    private fun toSingleRunPdfInput(
        item: BatchQueueItem,
        batchInput: BatchPdfReportInput
    ): PdfReportInput {
        val frame = item.resultFrame
        return PdfReportInput(
            datasetTitle = item.datasetTitle,
            algorithm = item.algorithm,
            kMeansConfig = item.kMeansConfig,
            dbscanConfig = item.dbscanConfig,
            points = frame?.points ?: item.rawPoints,
            centroids = frame?.centroids.orEmpty(),
            frame = frame,
            showVoronoi = batchInput.showVoronoi,
            showTrails = batchInput.showTrails,
            showSpiderLines = batchInput.showSpiderLines,
            showEpsilonRings = batchInput.showEpsilonRings,
            annotations = item.annotations
        )
    }

    // =========================================================================
    // 1. Native Android Multi-Page PdfDocument Renderer
    // =========================================================================
    private fun renderMultiPageWithAndroidPdfDocument(
        input: BatchPdfReportInput,
        runs: List<BatchQueueItem>
    ): ByteArray {
        val doc = PdfDocument()
        val totalPages = 1 + runs.size
        try {
            // Page 1: Executive Batch Summary & Comparison Table
            val coverInfo = PdfDocument.PageInfo.Builder(
                PdfReportGenerator.PAGE_WIDTH,
                PdfReportGenerator.PAGE_HEIGHT,
                1
            ).create()
            val coverPage = doc.startPage(coverInfo)
            drawBatchExecutiveSummaryPage(coverPage.canvas, input, runs, totalPages)
            doc.finishPage(coverPage)

            // Pages 2..N+1: Individual Run Snapshot Pages
            runs.forEachIndexed { idx, item ->
                val pageNum = idx + 2
                val runInfo = PdfDocument.PageInfo.Builder(
                    PdfReportGenerator.PAGE_WIDTH,
                    PdfReportGenerator.PAGE_HEIGHT,
                    pageNum
                ).create()
                val runPage = doc.startPage(runInfo)
                PdfReportGenerator.drawReportPageOnCanvas(
                    canvas = runPage.canvas,
                    input = toSingleRunPdfInput(item, input),
                    pageNumber = pageNum,
                    totalPages = totalPages,
                    batchRunLabel = "Batch Run ${idx + 1} of ${runs.size}"
                )
                doc.finishPage(runPage)
            }

            val baos = ByteArrayOutputStream()
            doc.writeTo(baos)
            return baos.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun drawBatchExecutiveSummaryPage(
        canvas: Canvas,
        input: BatchPdfReportInput,
        runs: List<BatchQueueItem>,
        totalPages: Int
    ) {
        val pageWidth = PdfReportGenerator.PAGE_WIDTH.toFloat()
        val pageHeight = PdfReportGenerator.PAGE_HEIGHT.toFloat()
        val margin = 36f
        val contentWidth = pageWidth - margin * 2f
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        val bgPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, pageWidth, pageHeight, bgPaint)

        // Header banner
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt() }
        canvas.drawRect(0f, 0f, pageWidth, 96f, headerPaint)
        val accentBar = Paint().apply { color = 0xFF2563EB.toInt() }
        canvas.drawRect(0f, 92f, pageWidth, 96f, accentBar)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 19f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textSize = 10.5f
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF60A5FA.toInt()
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        canvas.drawText("ClusterLab — Combined Batch Summary Report", margin, 36f, titlePaint)
        canvas.drawText(
            "Batch: ${input.batchTitle}   |   ${runs.size} Sequential Runs   |   $totalPages Pages",
            margin,
            56f,
            subtitlePaint
        )
        canvas.drawText("Generated: $generatedAt", margin, 76f, badgePaint)

        // Aggregate KPI Cards
        var cursorY = 118f
        val sectionTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("1. Batch Overview & Top Performers", margin, cursorY, sectionTitlePaint)
        cursorY += 12f

        val totalPoints = runs.sumOf { it.rawPoints.size }
        val totalDurationMs = runs.sumOf { it.durationMs }
        val bestSilRun = runs.maxByOrNull { it.resultFrame?.silhouetteScore ?: Float.NEGATIVE_INFINITY }
        val bestInertiaRun = runs.minByOrNull { it.resultFrame?.wcss ?: Float.POSITIVE_INFINITY }

        val cardGap = 10f
        val cardW = (contentWidth - cardGap * 3f) / 4f
        val cardH = 64f
        val kpis = listOf(
            Triple("QUEUED RUNS", "${runs.size}", "${totalDurationMs} ms total"),
            Triple("TOTAL POINTS", "$totalPoints", "Across all runs"),
            Triple(
                "BEST SILHOUETTE",
                bestSilRun?.resultFrame?.let { String.format(Locale.US, "%.3f", it.silhouetteScore) } ?: "—",
                bestSilRun?.datasetTitle?.take(18) ?: "No runs"
            ),
            Triple(
                "LOWEST INERTIA",
                bestInertiaRun?.resultFrame?.let { String.format(Locale.US, "%.3f", it.wcss) } ?: "—",
                bestInertiaRun?.datasetTitle?.take(18) ?: "No runs"
            )
        )

        kpis.forEachIndexed { idx, (label, value, sub) ->
            val left = margin + idx * (cardW + cardGap)
            drawSummaryKpiBox(canvas, RectF(left, cursorY, left + cardW, cursorY + cardH), label, value, sub)
        }
        cursorY += cardH + 24f

        // Comparison Table across all sequential runs
        canvas.drawText("2. Sequential Run Comparison Table", margin, cursorY, sectionTitlePaint)
        cursorY += 12f

        val headerBg = Paint().apply { color = 0xFF1E293B.toInt() }
        val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 8.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val rowText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 8.8f
        }
        val altRowBg = Paint().apply { color = 0xFFF8FAFC.toInt() }
        val borderPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 0.8f
        }

        val rowHeight = 26f
        canvas.drawRect(margin, cursorY, margin + contentWidth, cursorY + rowHeight, headerBg)

        val colIdx = margin + 8f
        val colDataset = margin + 34f
        val colAlgo = margin + 175f
        val colPts = margin + 292f
        val colClusters = margin + 338f
        val colSil = margin + 390f
        val colInertia = margin + 450f

        val hy = cursorY + 16.5f
        canvas.drawText("#", colIdx, hy, headerText)
        canvas.drawText("Dataset", colDataset, hy, headerText)
        canvas.drawText("Algorithm & Config", colAlgo, hy, headerText)
        canvas.drawText("Pts", colPts, hy, headerText)
        canvas.drawText("Groups", colClusters, hy, headerText)
        canvas.drawText("Silhouette", colSil, hy, headerText)
        canvas.drawText("Inertia", colInertia, hy, headerText)

        var y = cursorY + rowHeight
        runs.take(18).forEachIndexed { idx, item ->
            val frame = item.resultFrame
            if (idx % 2 == 1) {
                canvas.drawRect(margin, y, margin + contentWidth, y + rowHeight, altRowBg)
            }
            val cfgShort = when (item.algorithm) {
                AlgorithmType.KMEANS -> "K-Means (K=${item.kMeansConfig.k})"
                AlgorithmType.DBSCAN -> String.format(Locale.US, "DBSCAN (e=%.2f)", item.dbscanConfig.epsilon)
            }
            val ry = y + 16.5f
            canvas.drawText("${idx + 1}", colIdx, ry, rowText)
            canvas.drawText(item.datasetTitle.take(24), colDataset, ry, rowText)
            canvas.drawText(cfgShort, colAlgo, ry, rowText)
            canvas.drawText("${item.rawPoints.size}", colPts, ry, rowText)
            canvas.drawText("${frame?.clusterSummaries?.size ?: 0}", colClusters, ry, rowText)
            canvas.drawText(String.format(Locale.US, "%.3f", frame?.silhouetteScore ?: 0f), colSil, ry, rowText)
            canvas.drawText(String.format(Locale.US, "%.4f", frame?.wcss ?: 0f), colInertia, ry, rowText)
            y += rowHeight
            canvas.drawLine(margin, y, margin + contentWidth, y, borderPaint)
        }

        // Note pointing reader to detailed snapshot pages
        val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF475569.toInt()
            textSize = 9.5f
        }
        canvas.drawText(
            "Detailed 2D visualization snapshots and per-cluster tables follow on Pages 2–$totalPages.",
            margin,
            (y + 24f).coerceAtMost(pageHeight - 54f),
            notePaint
        )

        // Footer
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 8.5f
        }
        val dividerPaint = Paint().apply {
            color = 0xFFE2E8F0.toInt()
            strokeWidth = 1f
        }
        canvas.drawLine(margin, pageHeight - 36f, pageWidth - margin, pageHeight - 36f, dividerPaint)
        canvas.drawText(
            "ClusterLab v1.0 (AJ60)  •  Combined Batch Analysis Report  •  Page 1 of $totalPages",
            margin,
            pageHeight - 20f,
            footerPaint
        )
    }

    private fun drawSummaryKpiBox(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        subtext: String
    ) {
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
            textSize = 7.6f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0F172A.toInt()
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF64748B.toInt()
            textSize = 7.8f
        }
        canvas.drawText(label, rect.left + 9f, rect.top + 16f, labelPaint)
        canvas.drawText(value, rect.left + 9f, rect.top + 37f, valuePaint)
        canvas.drawText(subtext, rect.left + 9f, rect.top + 53f, subPaint)
    }

    // =========================================================================
    // 2. Pure-Kotlin Multi-Page Valid PDF 1.4 Fallback (JVM / Robolectric)
    // =========================================================================
    private fun buildFallbackCombinedPdf14(
        input: BatchPdfReportInput,
        runs: List<BatchQueueItem>
    ): ByteArray {
        val totalPages = 1 + runs.size
        val streams = mutableListOf<ByteArray>()
        streams.add(buildFallbackExecutiveCoverStream(input, runs, totalPages))
        runs.forEachIndexed { idx, item ->
            streams.add(
                PdfReportGenerator.buildFallbackPageStream(
                    input = toSingleRunPdfInput(item, input),
                    pageNumber = idx + 2,
                    totalPages = totalPages,
                    batchRunLabel = "Batch Run ${idx + 1} of ${runs.size}"
                )
            )
        }
        return PdfReportGenerator.assembleMultiPagePdf14(streams)
    }

    private fun buildFallbackExecutiveCoverStream(
        input: BatchPdfReportInput,
        runs: List<BatchQueueItem>,
        totalPages: Int
    ): ByteArray {
        val sb = StringBuilder()
        val generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        fun esc(text: String): String = text
            .replace("\\", "\\\\")
            .replace("(", "\\(")
            .replace(")", "\\)")

        sb.append("0.06 0.09 0.16 rg 0 746 595 96 re f\n")
        sb.append("0.15 0.39 0.92 rg 0 746 595 4 re f\n")
        sb.append("1 1 1 rg BT /F1 18 Tf 36 806 Td (${esc("ClusterLab - Combined Batch Summary Report")}) Tj ET\n")
        sb.append("0.8 0.84 0.88 rg BT /F1 10 Tf 36 786 Td (${esc("Batch: ${input.batchTitle} | ${runs.size} Runs | $totalPages Pages")}) Tj ET\n")
        sb.append("0.38 0.65 0.98 rg BT /F1 9 Tf 36 766 Td (${esc("Generated: $generatedAt")}) Tj ET\n")

        val totalPoints = runs.sumOf { it.rawPoints.size }
        sb.append("0.06 0.09 0.16 rg BT /F1 12 Tf 36 716 Td (${esc("Batch Summary (${runs.size} Datasets, $totalPoints Total Points)")}) Tj ET\n")

        var rowY = 688f
        runs.forEachIndexed { idx, item ->
            val frame = item.resultFrame
            val line = String.format(
                Locale.US,
                "%d. %s | %s | %d pts | %d clusters | Sil %.3f | Inertia %.4f",
                idx + 1,
                item.datasetTitle,
                item.algorithm.displayName,
                item.rawPoints.size,
                frame?.clusterSummaries?.size ?: 0,
                frame?.silhouetteScore ?: 0f,
                frame?.wcss ?: 0f
            )
            sb.append(String.format(Locale.US, "0.1 0.15 0.22 rg BT /F1 9.5 Tf 36 %.1f Td (%s) Tj ET\n", rowY, esc(line)))
            rowY -= 20f
        }

        sb.append("0.39 0.45 0.55 rg BT /F1 8.5 Tf 36 24 Td (${esc("ClusterLab v1.0 (AJ60) | Combined Batch Summary | Page 1 of $totalPages")}) Tj ET\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
