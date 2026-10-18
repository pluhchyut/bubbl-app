package com.talksync.model

// which kind of input the convo is using, shared by settings, ui, and the vm.

enum class ConversationInputMode(
    val value: String,
    val label: String,
) {
    TRANSCRIPTION(
        value = "transcription",
        label = "Speech",
    ),
    ASL_TRANSLATION(
        value = "asl_translation",
        label = "ASL",
    ),
    ;
}
