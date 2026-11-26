package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_datasets")
data class SavedDatasetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val algorithm: String,
    val pointCount: Int,
    val kValue: Int,
    val epsilonValue: Float,
    val minPtsValue: Int,
    val pointsSerialized: String, // "x1,y1;x2,y2;..."
    val createdAt: Long = System.currentTimeMillis()
)
