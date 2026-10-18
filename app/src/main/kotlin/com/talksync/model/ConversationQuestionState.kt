package com.talksync.model

// holds the follow-up question that shows up after a summary gets made.

data class ConversationQuestionState(
    val question: String = "",
    val answer: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
