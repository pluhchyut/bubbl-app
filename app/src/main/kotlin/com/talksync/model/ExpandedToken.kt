package com.talksync.model

// one slang expansion result: what was heard, plus what it probably means.

data class ExpandedToken(
    val original: String,
    val expanded: String?,
)
