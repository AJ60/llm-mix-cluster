package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedDatasetDao {
    @Query("SELECT * FROM saved_datasets ORDER BY createdAt DESC")
    fun getAllDatasets(): Flow<List<SavedDatasetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDataset(dataset: SavedDatasetEntity)

    @Query("DELETE FROM saved_datasets WHERE id = :id")
    suspend fun deleteDatasetById(id: Int)
}
