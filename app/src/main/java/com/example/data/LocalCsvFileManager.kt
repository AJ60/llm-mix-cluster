package com.example.data

import android.content.Context
import android.content.Intent
import com.example.domain.DatasetGenerator
import com.example.model.CentroidState
import com.example.model.DataPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalCsvFileInfo(
    val fileName: String,
    val absolutePath: String,
    val pointCount: Int,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val previewHeader: String
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
 * Manages reading, writing, listing, deleting, and sharing `.csv` clustering dataset files
 * in zero-permission app-scoped local device storage (`filesDir/csv_datasets`).
 */
object LocalCsvFileManager {

    private const val CSV_DIRECTORY_NAME = "csv_datasets"

    fun getCsvStorageDirectory(context: Context): File {
        val dir = File(context.filesDir, CSV_DIRECTORY_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun sanitizeFileName(rawTitle: String): String {
        val base = rawTitle
            .trim()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "cluster_dataset" }
        return if (base.endsWith(".csv", ignoreCase = true)) {
            base
        } else {
            "$base.csv"
        }
    }

    /**
     * Saves the given [points] and [centroids] as a `.csv` file in local device storage.
     */
    fun saveCsvToLocalDevice(
        context: Context,
        desiredName: String,
        points: List<DataPoint>,
        centroids: List<CentroidState> = emptyList()
    ): LocalCsvFileInfo {
        val dir = getCsvStorageDirectory(context)
        val fileName = sanitizeFileName(desiredName)
        val targetFile = File(dir, fileName)
        val csvContent = DatasetGenerator.exportPointsToCsv(
            points = points,
            centroids = centroids,
            datasetTitle = desiredName.removeSuffix(".csv")
        )
        targetFile.writeText(csvContent, Charsets.UTF_8)
        return inspectCsvFile(targetFile)
    }

    /**
     * Lists all `.csv` dataset files saved in local device storage, newest first.
     */
    fun listLocalCsvFiles(context: Context): List<LocalCsvFileInfo> {
        val dir = getCsvStorageDirectory(context)
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".csv", ignoreCase = true)
        } ?: return emptyList()

        return files
            .map { inspectCsvFile(it) }
            .sortedByDescending { it.lastModifiedMillis }
    }

    /**
     * Reads raw CSV text from a local `.csv` file on device storage.
     */
    fun readLocalCsvText(context: Context, fileName: String): String? {
        val file = File(getCsvStorageDirectory(context), sanitizeFileName(fileName))
        if (!file.exists() || !file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * Loads and parses 2D [DataPoint]s from a local `.csv` file on device storage.
     */
    fun loadPointsFromLocalCsv(context: Context, fileName: String): List<DataPoint> {
        val raw = readLocalCsvText(context, fileName) ?: return emptyList()
        return DatasetGenerator.parseRawTextToPoints(raw)
    }

    /**
     * Deletes a `.csv` file from local device storage.
     */
    fun deleteLocalCsvFile(context: Context, fileName: String): Boolean {
        val file = File(getCsvStorageDirectory(context), sanitizeFileName(fileName))
        return file.exists() && file.delete()
    }

    /**
     * Launches the Android system share sheet (`Intent.ACTION_SEND`) with the CSV text payload
     * so users can share their point configuration with other apps or nodes.
     */
    fun shareCsvDatasetText(
        context: Context,
        datasetTitle: String,
        csvContent: String
    ) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "$datasetTitle — ClusterLab CSV Dataset")
            putExtra(Intent.EXTRA_TEXT, csvContent)
        }
        val chooser = Intent.createChooser(sendIntent, "Share CSV Dataset").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(chooser)
        }
    }

    private fun inspectCsvFile(file: File): LocalCsvFileInfo {
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
        val dataLines = lines.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() &&
                !trimmed.startsWith("#") &&
                !trimmed.startsWith("id,", ignoreCase = true) &&
                !trimmed.startsWith("x,", ignoreCase = true)
        }
        val preview = lines
            .filter { it.isNotBlank() }
            .take(3)
            .joinToString(" | ")
        return LocalCsvFileInfo(
            fileName = file.name,
            absolutePath = file.absolutePath,
            pointCount = dataLines.size,
            sizeBytes = file.length(),
            lastModifiedMillis = file.lastModified(),
            previewHeader = preview
        )
    }
}
