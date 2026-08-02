package dev.alpine.chat.feature.assistant

/** Deterministic constraints that can be measured locally without judging answer meaning. */
data class ResponseConstraints(
    val maxWords: Int? = null,
    val exactSentences: Int? = null,
    val exactBullets: Int? = null,
) {
    val isEmpty: Boolean
        get() = maxWords == null && exactSentences == null && exactBullets == null

    fun validate(response: String): List<ResponseConstraintViolation> = buildList {
        maxWords?.let { expected ->
            val actual = ResponseConstraintDetector.wordCount(response)
            if (actual > expected) {
                add(ResponseConstraintViolation(ResponseConstraintKind.WORDS_MAX, expected, actual))
            }
        }
        exactSentences?.let { expected ->
            val actual = ResponseConstraintDetector.sentenceCount(response)
            if (actual != expected) {
                add(ResponseConstraintViolation(ResponseConstraintKind.SENTENCES_EXACT, expected, actual))
            }
        }
        exactBullets?.let { expected ->
            val actual = ResponseConstraintDetector.bulletCount(response)
            if (actual != expected) {
                add(ResponseConstraintViolation(ResponseConstraintKind.BULLETS_EXACT, expected, actual))
            }
        }
    }

    fun augmentSystemInstruction(
        base: String,
        previousViolations: List<ResponseConstraintViolation> = emptyList(),
    ): String {
        if (isEmpty) return base
        val requirements = buildList {
            maxWords?.let { add("Use at most $it words in the complete answer.") }
            exactSentences?.let { add("Write exactly $it sentences in the complete answer.") }
            exactBullets?.let { add("Write exactly $it list items in the complete answer.") }
        }.joinToString(" ")
        val correction = previousViolations.takeIf { it.isNotEmpty() }?.joinToString(
            prefix = " A previous draft failed these checks: ",
            separator = "; ",
            postfix = ". Generate the complete answer again without discussing the correction.",
            transform = ResponseConstraintViolation::safeDescription,
        ).orEmpty()
        val combined = "$base\n\nMeasurable response format requirements: $requirements$correction"
        require(
            combined.toByteArray(Charsets.UTF_8).size <=
                AssistantPromptComposer.MAX_SYSTEM_INSTRUCTION_BYTES,
        ) { "Assistant instruction exceeds the supported size" }
        return combined
    }
}

enum class ResponseConstraintKind {
    WORDS_MAX,
    SENTENCES_EXACT,
    BULLETS_EXACT,
}

data class ResponseConstraintViolation(
    val kind: ResponseConstraintKind,
    val expected: Int,
    val actual: Int,
) {
    fun safeDescription(): String = when (kind) {
        ResponseConstraintKind.WORDS_MAX -> "$actual words, maximum $expected"
        ResponseConstraintKind.SENTENCES_EXACT -> "$actual sentences, required $expected"
        ResponseConstraintKind.BULLETS_EXACT -> "$actual list items, required $expected"
    }
}

object ResponseConstraintDetector {
    private const val MAX_DETECTED_VALUE = 500
    private const val ENGLISH_NUMBER =
        "(?:\\d{1,3}|one[ -]hundred|(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[- ](?:one|two|three|four|five|six|seven|eight|nine))?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen)"
    private const val KOREAN_NUMBER = "(?:\\d{1,3}|한|하나|두|둘|세|셋|네|넷|다섯)"

    private val underWords = Regex(
        "(?i)\\b(?:under|fewer\\s+than|less\\s+than)\\s+($ENGLISH_NUMBER)\\s+words?\\b",
    )
    private val atMostWords = Regex(
        "(?i)\\b(?:at\\s+most|no\\s+more\\s+than|maximum\\s+of|max(?:imum)?)\\s+($ENGLISH_NUMBER)\\s+words?\\b",
    )
    private val englishSentences = Regex(
        "(?i)\\b($ENGLISH_NUMBER)\\s+(?:short\\s+)?sentences?\\b",
    )
    private val englishSentenceRange = Regex(
        "(?i)\\b($ENGLISH_NUMBER)\\s*(?:or|to|-)\\s*($ENGLISH_NUMBER)\\s+(?:short\\s+)?sentences?\\b",
    )
    private val englishBullets = Regex(
        "(?i)\\b($ENGLISH_NUMBER)\\s+(?:short\\s+)?(?:bullets?|bullet\\s+points?|list\\s+items?)\\b",
    )
    private val englishBulletRange = Regex(
        "(?i)\\b($ENGLISH_NUMBER)\\s*(?:or|to|-)\\s*($ENGLISH_NUMBER)\\s+(?:short\\s+)?(?:bullets?|bullet\\s+points?|list\\s+items?)\\b",
    )
    private val koreanWords = Regex("($KOREAN_NUMBER)\\s*(?:개\\s*)?단어\\s*(미만|이하)")
    private val koreanSentences = Regex("($KOREAN_NUMBER)\\s*(?:개\\s*)?문장(?:으로|만)")
    private val koreanBullets = Regex(
        "($KOREAN_NUMBER)\\s*(?:개\\s*)?(?:불릿|bullet|목록\\s*항목)(?:으로|만)",
        RegexOption.IGNORE_CASE,
    )
    private val words = Regex("[\\p{L}\\p{N}]+(?:['’_-][\\p{L}\\p{N}]+)*")
    private val sentenceEnd = Regex("[.!?。！？]+(?:[\\\"'”’）)\\]]+)?(?=\\s|$)")
    private val bulletLine = Regex("(?m)^\\s*(?:[-+*]|\\d+[.)])\\s+\\S")
    private val fencedCode = Regex("(?s)(?:```|~~~).*?(?:(?:```|~~~)|$)")
    private val inlineCode = Regex("`([^`]*)`")

    fun detect(prompt: String): ResponseConstraints {
        val wordLimits = buildList {
            underWords.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let { add((it - 1).coerceAtLeast(1)) }
            }
            atMostWords.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
            }
            koreanWords.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let { number ->
                    add(if (match.groupValues[2] == "미만") (number - 1).coerceAtLeast(1) else number)
                }
            }
        }
        val sentenceValues = buildList {
            englishSentenceRange.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
                parseNumber(match.groupValues[2])?.let(::add)
            }
            englishSentences.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
            }
            koreanSentences.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
            }
        }
        val bulletValues = buildList {
            englishBulletRange.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
                parseNumber(match.groupValues[2])?.let(::add)
            }
            englishBullets.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
            }
            koreanBullets.findAll(prompt).forEach { match ->
                parseNumber(match.groupValues[1])?.let(::add)
            }
        }
        return ResponseConstraints(
            maxWords = wordLimits.minOrNull(),
            exactSentences = sentenceValues.singleUnambiguousValue(),
            exactBullets = bulletValues.singleUnambiguousValue(),
        )
    }

    fun wordCount(response: String): Int = words.findAll(withoutFenceMarkers(response)).count()

    fun sentenceCount(response: String): Int {
        val prose = withoutCode(response).trim()
        if (prose.isEmpty()) return 0
        return sentenceEnd.findAll(prose).count().takeIf { it > 0 } ?: 1
    }

    fun bulletCount(response: String): Int = bulletLine.findAll(
        response.replace(fencedCode, " "),
    ).count()

    internal fun parseNumber(raw: String): Int? {
        raw.toIntOrNull()?.let { return it.takeIf(::validValue) }
        KOREAN_VALUES[raw]?.let { return it }
        val parts = raw.lowercase().replace('-', ' ').trim().split(Regex("\\s+"))
        if (parts == listOf("one", "hundred")) return 100
        val value = when (parts.size) {
            1 -> ENGLISH_VALUES[parts[0]]
            2 -> TENS[parts[0]]?.plus(ONES[parts[1]] ?: return null)
            else -> null
        }
        return value?.takeIf(::validValue)
    }

    private fun withoutCode(response: String): String = response
        .replace(fencedCode, " ")
        .replace(inlineCode) { match -> match.groupValues[1] }
        .replace(Regex("[*_>#]"), " ")

    private fun withoutFenceMarkers(response: String): String = response
        .replace(Regex("(?m)^\\s*(?:```|~~~)[^\\n]*$"), " ")
        .replace(inlineCode) { match -> match.groupValues[1] }
        .replace(Regex("[*_>#]"), " ")

    private fun List<Int>.singleUnambiguousValue(): Int? = distinct().singleOrNull()

    private fun validValue(value: Int): Boolean = value in 1..MAX_DETECTED_VALUE

    private val ONES = mapOf(
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
    )
    private val TENS = mapOf(
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90,
    )
    private val ENGLISH_VALUES = ONES + TENS + mapOf(
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
    )
    private val KOREAN_VALUES = mapOf(
        "한" to 1,
        "하나" to 1,
        "두" to 2,
        "둘" to 2,
        "세" to 3,
        "셋" to 3,
        "네" to 4,
        "넷" to 4,
        "다섯" to 5,
    )
}
