package com.talksync.data

// stores generated recap/question text as lightweight app state instead of room rows.

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

class ConversationSummaryStore(context: Context) {

    private val file = File(context.filesDir, "conversation_summaries.json")
    private val mutex = Mutex()

    private val _summaries = MutableStateFlow(loadFromDisk())
    val summaries: StateFlow<Map<String, String>> = _summaries.asStateFlow()

    suspend fun put(
        conversationId: String,
        summary: String,
    ) {
        val trimmedSummary = summary.trim()
        if (conversationId.isBlank() || trimmedSummary.isBlank()) {
            return
        }

        mutex.withLock {
            val updated = _summaries.value.toMutableMap().apply {
                put(conversationId, trimmedSummary)
            }
            persist(updated)
            _summaries.value = updated
        }
    }

    suspend fun clear() {
        mutex.withLock {
            if (file.exists()) {
                file.delete()
            }
            _summaries.value = emptyMap()
        }
    }

    private fun loadFromDisk(): Map<String, String> {
        if (!file.exists()) {
            return emptyMap()
        }

        return runCatching {
            val json = JSONObject(file.readText())
            buildMap {
                json.keys().forEach { key ->
                    val value = json.optString(key).trim()
                    if (value.isNotBlank()) {
                        put(key, value)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun persist(
        summaries: Map<String, String>,
    ) {
        val json = JSONObject()
        summaries.forEach { (conversationId, summary) ->
            json.put(conversationId, summary)
        }
        file.writeText(json.toString())
    }
}
