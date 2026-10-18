package com.talksync.vision

// room entity/dao for remembered faces, so speaker labels can stick over time.

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * A persistent face identity, similar in spirit to Google Photos' "people"
 * clusters. Each row stores a centroid feature vector built from MediaPipe's
 * geometric landmarks. New face observations are matched against these
 * centroids; on a hit the centroid is rolled into a running average so the
 * identity gradually adapts to lighting / angle drift.
 *
 * Feature vectors are persisted as a comma-separated string of floats to
 * avoid pulling in a binary serialization dependency. Vectors are short
 * (<200 floats) so the storage cost is negligible.
 */
@Entity(tableName = "face_identities")
data class FaceIdentity(
    @PrimaryKey val id: String,
    /** User-supplied name. Null until the user names this identity. */
    val displayName: String? = null,
    /** Comma-separated float centroid; see [FaceFeatureExtractor]. */
    val featureCentroid: String,
    /** Number of observations folded into the centroid so far. */
    val sampleCount: Int = 1,
    val createdAt: Long,
    val lastSeenAt: Long,
)

@Dao
interface FaceIdentityDao {
    @Query("SELECT * FROM face_identities ORDER BY lastSeenAt DESC")
    fun getAll(): Flow<List<FaceIdentity>>

    @Query("SELECT * FROM face_identities ORDER BY lastSeenAt DESC")
    suspend fun getAllOnce(): List<FaceIdentity>

    @Query("SELECT * FROM face_identities WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): FaceIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: FaceIdentity)

    @Update
    suspend fun update(identity: FaceIdentity)

    @Query("UPDATE face_identities SET displayName = :displayName WHERE id = :id")
    suspend fun rename(id: String, displayName: String?)

    @Query("DELETE FROM face_identities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM face_identities")
    suspend fun deleteAll()
}
