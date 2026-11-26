package com.example.data

import com.example.model.AlgorithmType
import com.example.model.DataPoint
import kotlinx.coroutines.flow.Flow

class DatasetRepository(private val dao: SavedDatasetDao) {
    val savedDatasets: Flow<List<SavedDatasetEntity>> = dao.getAllDatasets()

    suspend fun saveSnapshot(
        title: String,
        algorithm: AlgorithmType,
        points: List<DataPoint>,
        kValue: Int,
        epsilonValue: Float,
        minPtsValue: Int
    ) {
        val serialized = points.joinToString(";") { pt ->
            "${"%.5f".format(pt.x)},${"%.5f".format(pt.y)}"
        }
        val entity = SavedDatasetEntity(
            title = title.ifBlank { "Snapshot (${points.size} pts)" },
            algorithm = algorithm.name,
            pointCount = points.size,
            kValue = kValue,
            epsilonValue = epsilonValue,
            minPtsValue = minPtsValue,
            pointsSerialized = serialized
        )
        dao.insertDataset(entity)
    }

    suspend fun deleteSnapshot(id: Int) {
        dao.deleteDatasetById(id)
    }

    fun deserializePoints(serialized: String): List<DataPoint> {
        if (serialized.isBlank()) return emptyList()
        return serialized.split(";").mapIndexedNotNull { index, token ->
            val parts = token.split(",")
            if (parts.size >= 2) {
                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                if (x != null && y != null) {
                    DataPoint(
                        id = index + 1,
                        x = x.coerceIn(0.02f, 0.98f),
                        y = y.coerceIn(0.02f, 0.98f)
                    )
                } else null
            } else null
        }
    }
}
