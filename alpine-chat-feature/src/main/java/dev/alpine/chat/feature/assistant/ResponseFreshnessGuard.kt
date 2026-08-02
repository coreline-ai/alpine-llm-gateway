package dev.alpine.chat.feature.assistant

/**
 * A narrow safety boundary for prompts that require live or externally verified information.
 *
 * This does not judge factual correctness. It only prevents the model from claiming that this app
 * used a web/search tool which the current chat runtime does not provide.
 */
data class ResponseFreshnessGuard(
    val requiresExternalVerification: Boolean = false,
) {
    val isActive: Boolean
        get() = requiresExternalVerification

    fun validate(response: String): ResponseFreshnessViolation? {
        if (!isActive || response.isBlank()) return null
        return if (ResponseFreshnessGuardDetector.containsUnsupportedVerificationClaim(response)) {
            ResponseFreshnessViolation
        } else {
            null
        }
    }

    fun augmentSystemInstruction(
        base: String,
        previousViolation: ResponseFreshnessViolation? = null,
    ): String {
        if (!isActive) return base
        val correction = if (previousViolation != null) {
            " A previous draft claimed external verification. Generate the complete answer again " +
                "without claiming any browsing, live lookup, or verification that was not performed."
        } else {
            ""
        }
        val combined = "$base\n\nExternal verification boundary: This chat runtime has no web, " +
            "browser, search, or live-data tool. Do not claim that you browsed, searched, checked, " +
            "or verified current information. If the answer depends on current information, state " +
            "that you cannot verify it live here and ask for a dated source or user-provided data. " +
            "You may still give clearly labeled stable background knowledge.$correction"
        require(
            combined.toByteArray(Charsets.UTF_8).size <=
                AssistantPromptComposer.MAX_SYSTEM_INSTRUCTION_BYTES,
        ) { "Assistant instruction exceeds the supported size" }
        return combined
    }
}

data object ResponseFreshnessViolation {
    fun safeDescription(): String =
        "claimed external verification that this chat runtime did not perform"
}

object ResponseFreshnessGuardDetector {
    private val explicitEnglishWebRequest = Regex(
        "(?is)(?:\\b(?:browse|search|check|verify|look\\s+up)\\b.{0,40}" +
            "\\b(?:the\\s+)?(?:web|internet|online)\\b)|" +
            "(?:\\b(?:web|internet|online)\\b.{0,40}" +
            "\\b(?:browse|search|check|verify|look\\s+up)\\b)",
    )
    private val englishFreshness = Regex(
        "(?i)\\b(?:latest|up[- ]to[- ]date|real[- ]time|today(?:'s)?|as\\s+of\\s+today)\\b",
    )
    private val explicitKoreanWebRequest = Regex(
        "(?s)(?:(?:웹|인터넷|온라인).{0,30}(?:검색|확인|검증|찾아)|" +
            "(?:검색|확인|검증|찾아).{0,30}(?:웹|인터넷|온라인))",
    )
    private val koreanFreshness = Regex(
        "(?:최신|실시간|오늘의|오늘\\s*(?:날씨|뉴스|가격|환율|주가)|" +
            "현재\\s*(?:날씨|뉴스|가격|환율|주가|대통령|대표|CEO|버전))",
        RegexOption.IGNORE_CASE,
    )
    private val englishBrowseClaim = Regex(
        "(?i)\\b(?:I|we)\\s+(?:have\\s+)?(?:browsed|searched|looked\\s+up)\\b",
    )
    private val englishCheckedLiveClaim = Regex(
        "(?is)\\b(?:I|we)\\s+(?:have\\s+)?(?:checked|verified)\\b.{0,60}" +
            "\\b(?:web|internet|online|live|current|today(?:'s)?)\\b",
    )
    private val englishConfirmClaim = Regex(
        "(?i)\\b(?:I|we)\\s+(?:can\\s+)?confirm(?:ed)?\\s+(?:from|via|on)\\s+" +
            "(?:the\\s+)?(?:web|internet|online)",
    )
    private val koreanUnsupportedClaim = Regex(
        "(?s)(?:웹|인터넷|온라인)(?:에서|으로|을|를)?" +
            ".{0,20}(?:검색|확인|검증)(?:해\\s*보니|했|하였다|했습니다|완료)",
    )

    fun detect(prompt: String): ResponseFreshnessGuard = ResponseFreshnessGuard(
        requiresExternalVerification =
            explicitEnglishWebRequest.containsMatchIn(prompt) ||
                englishFreshness.containsMatchIn(prompt) ||
                explicitKoreanWebRequest.containsMatchIn(prompt) ||
                koreanFreshness.containsMatchIn(prompt),
    )

    internal fun containsUnsupportedVerificationClaim(response: String): Boolean =
        englishBrowseClaim.containsMatchIn(response) ||
            englishCheckedLiveClaim.containsMatchIn(response) ||
            englishConfirmClaim.containsMatchIn(response) ||
            koreanUnsupportedClaim.containsMatchIn(response)
}
