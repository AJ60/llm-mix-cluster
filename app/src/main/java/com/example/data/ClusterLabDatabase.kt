package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedDatasetEntity::class,
        ChatSessionEntity::class,
        ChatMessageEntity::class,
        SavedPeerEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ClusterLabDatabase : RoomDatabase() {
    abstract fun savedDatasetDao(): SavedDatasetDao
    abstract fun silicaChatDao(): SilicaChatDao
    abstract fun savedPeerDao(): SavedPeerDao

    companion object {
        @Volatile
        private var INSTANCE: ClusterLabDatabase? = null

        fun getInstance(context: Context): ClusterLabDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClusterLabDatabase::class.java,
                    "clusterlab_studio.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
