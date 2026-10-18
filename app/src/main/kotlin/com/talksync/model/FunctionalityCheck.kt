package com.talksync.model

// feature health report model for home/demo screens, like "does this part work rn".

enum class FunctionalityStatus {
    LIVE,
    READY,
    WAITING,
    WARNING,
    NEEDS_SETUP,
}

data class FunctionalityCheck(
    val title: String,
    val detail: String,
    val status: FunctionalityStatus,
)

data class FunctionalityReport(
    val checks: List<FunctionalityCheck> = emptyList(),
) {
    val readyCount: Int
        get() = checks.count {
            it.status == FunctionalityStatus.READY || it.status == FunctionalityStatus.LIVE
        }

    val liveCount: Int
        get() = checks.count { it.status == FunctionalityStatus.LIVE }

    val blockingCount: Int
        get() = checks.count {
            it.status == FunctionalityStatus.WARNING || it.status == FunctionalityStatus.NEEDS_SETUP
        }

    val blockingChecks: List<FunctionalityCheck>
        get() = checks.filter {
            it.status == FunctionalityStatus.WARNING || it.status == FunctionalityStatus.NEEDS_SETUP
        }

    val headline: String
        get() = when {
            checks.isEmpty() -> "Background checks unavailable"
            blockingCount > 0 -> "$readyCount/${checks.size} systems ready · $blockingCount need attention"
            liveCount > 0 -> "$liveCount live · $readyCount/${checks.size} systems ready"
            else -> "$readyCount/${checks.size} systems ready"
        }
}
