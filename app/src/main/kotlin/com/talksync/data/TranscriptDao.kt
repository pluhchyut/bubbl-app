package com.talksync.data

// room queries for transcript turns. history does the big grouping work later.

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.talksync.model.TranscriptEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TranscriptEntry): Long

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TranscriptEntry>>

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<TranscriptEntry>

    @Query("SELECT * FROM transcripts ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEntries(limit: Int): List<TranscriptEntry>

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE transcripts SET text = :text WHERE timestamp = :timestamp")
    suspend fun updateTextByTimestamp(timestamp: Long, text: String)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}
