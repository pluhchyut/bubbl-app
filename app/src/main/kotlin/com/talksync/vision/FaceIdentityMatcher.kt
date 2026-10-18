package com.talksync.vision

// nearest-neighbor face matcher for "hey, i have seen this speaker before" moments.

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates the cluster-on-the-fly identity assignment loop.
 *
 * Workflow per camera frame (called at ~5Hz, not per frame, to stay cheap):
 *   1. Caller hands a fresh feature vector + the live tracker's faceId.
 *   2. Matcher finds the persisted [FaceIdentity] with the highest cosine
 *      similarity above [matchThreshold]. On a hit it merges the new sample
 *      into that centroid and returns the identity id.
 *   3. On a miss it allocates a new [FaceIdentity] (no display name yet) so
 *      the user can rename it later from the conversation history.
 *
 * The matcher keeps an in-memory mirror of the persisted identities in
 * [identities]. The DAO is the source of truth — call [refresh] after the
 * user renames or deletes an identity from the UI.
 */
class FaceIdentityMatcher(
    private val dao: FaceIdentityDao,
    private val matchThreshold: Float = 0.86f,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val cache = mutableMapOf<String, Cached>()
    private val _identities = MutableStateFlow<List<FaceIdentity>>(emptyList())
    val identities: StateFlow<List<FaceIdentity>> = _identities.asStateFlow()

    private data class Cached(
        val row: FaceIdentity,
        val centroid: FloatArray,
    )

    suspend fun refresh() {
        cache.clear()
        val rows = dao.getAllOnce()
        rows.forEach { row ->
            FaceFeatureExtractor.decode(row.featureCentroid)?.let { centroid ->
                cache[row.id] = Cached(row, centroid)
            }
        }
        _identities.value = rows
    }

    /**
     * Match [feature] against the existing identities. Allocates a new
     * identity on miss. Returns the resolved identity id or null if the
     * feature vector was too short.
     */
    suspend fun matchOrCreate(feature: FloatArray): String? {
        if (feature.isEmpty()) return null

        var bestId: String? = null
        var bestScore = -1f
        for ((id, cached) in cache) {
            val score = FaceFeatureExtractor.cosineSimilarity(feature, cached.centroid)
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }

        val timestamp = now()
        if (bestId != null && bestScore >= matchThreshold) {
            val cached = cache[bestId]!!
            val merged = FaceFeatureExtractor.mergeIntoCentroid(
                currentCentroid = cached.centroid,
                currentSampleCount = cached.row.sampleCount,
                newSample = feature,
            )
            val updated = cached.row.copy(
                featureCentroid = FaceFeatureExtractor.encode(merged),
                sampleCount = cached.row.sampleCount + 1,
                lastSeenAt = timestamp,
            )
            cache[bestId] = Cached(updated, merged)
            dao.update(updated)
            _identities.value = cache.values.map { it.row }.sortedByDescending { it.lastSeenAt }
            return bestId
        }

        val newId = UUID.randomUUID().toString()
        val row = FaceIdentity(
            id = newId,
            displayName = null,
            featureCentroid = FaceFeatureExtractor.encode(feature),
            sampleCount = 1,
            createdAt = timestamp,
            lastSeenAt = timestamp,
        )
        cache[newId] = Cached(row, feature)
        dao.upsert(row)
        _identities.value = cache.values.map { it.row }.sortedByDescending { it.lastSeenAt }
        return newId
    }

    suspend fun rename(identityId: String, displayName: String?) {
        val cached = cache[identityId] ?: return
        val updated = cached.row.copy(displayName = displayName?.takeIf { it.isNotBlank() })
        cache[identityId] = cached.copy(row = updated)
        dao.update(updated)
        _identities.value = cache.values.map { it.row }.sortedByDescending { it.lastSeenAt }
    }

    fun displayName(identityId: String?): String? {
        if (identityId == null) return null
        return cache[identityId]?.row?.displayName
    }
}
