package com.talksync.model

// plain little summary request payload. boring on purpose so tests can make it easily.

data class ConversationSummaryRequest(
    val conversationId: String,
    val transcript: String,
)
