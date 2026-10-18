package com.talksync.data

// room db singleton for transcripts and known faces. migrations matter here, sadly.

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.talksync.model.TranscriptEntry
import com.talksync.vision.FaceIdentity
import com.talksync.vision.FaceIdentityDao

@Database(
    entities = [TranscriptEntry::class, FaceIdentity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun faceIdentityDao(): FaceIdentityDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "talksync.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
