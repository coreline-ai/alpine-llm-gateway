package dev.alpine.llm.demo.data

import dev.alpine.llm.demo.model.ChatConversation
import dev.alpine.llm.demo.model.ChatMessage
import dev.alpine.llm.demo.model.ChatMessageState
import dev.alpine.llm.demo.model.ChatRole
import dev.alpine.llm.demo.model.AssistantSelection
import dev.alpine.llm.demo.model.ConversationGenerationState
import dev.alpine.llm.demo.model.ConversationSummary
import org.json.JSONArray
import org.json.JSONObject

data class ConversationIndex(
    val activeConversationId: String?,
    val summaries: List<ConversationSummary>,
)

object ConversationCodec {
    const val MAX_CONVERSATION_BYTES = 8 * 1024 * 1024
    const val MAX_INDEX_BYTES = 2 * 1024 * 1024
    const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
    const val MAX_DRAFT_BYTES = 256 * 1024
    const val MAX_MESSAGES = 2_000
    const val MAX_CONVERSATIONS = 1_000

    fun encodeConversation(conversation: ChatConversation): ByteArray {
        validateConversation(conversation)
        val messages = JSONArray()
        conversation.messages.forEach { message -> messages.put(message.toJson()) }
        val root = JSONObject()
            .put("schema", CONVERSATION_SCHEMA_VERSION)
            .put("id", conversation.id)
            .put("title", conversation.title)
            .put("messages", messages)
            .put("draft", conversation.draft)
            .putNullable("selectedProfileId", conversation.selectedProfileId)
            .putNullable("selectedModel", conversation.selectedModel)
            .put("selectedSkillId", conversation.selectedSkillId)
            .put("selectedPersonaId", conversation.selectedPersonaId)
            .put("generationState", conversation.generationState.name)
            .put("hasUnreadCompletion", conversation.hasUnreadCompletion)
            .put("createdAtMs", conversation.createdAtMs)
            .put("updatedAtMs", conversation.updatedAtMs)
        return root.toBoundedBytes(MAX_CONVERSATION_BYTES, "conversation")
    }

    fun decodeConversation(bytes: ByteArray): ChatConversation {
        requireBounded(bytes, MAX_CONVERSATION_BYTES, "conversation")
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val schema = root.getInt("schema")
        require(schema in SUPPORTED_CONVERSATION_SCHEMAS) { "Unsupported conversation schema" }
        val array = root.getJSONArray("messages")
        require(array.length() <= MAX_MESSAGES) { "Conversation has too many messages" }
        val messages = buildList(array.length()) {
            repeat(array.length()) { index ->
                add(array.getJSONObject(index).toChatMessage(schema))
            }
        }
        return ChatConversation(
            id = root.requiredId("id"),
            title = root.requiredBoundedString("title", MAX_TITLE_BYTES),
            messages = messages,
            draft = root.requiredBoundedString("draft", MAX_DRAFT_BYTES),
            selectedProfileId = root.optionalBoundedString("selectedProfileId", MAX_METADATA_BYTES),
            selectedModel = root.optionalBoundedString("selectedModel", MAX_METADATA_BYTES),
            selectedSkillId = if (schema >= CONVERSATION_SCHEMA_VERSION) {
                root.requiredAssistantModeId("selectedSkillId")
            } else {
                AssistantSelection.DEFAULT_SKILL_ID
            },
            selectedPersonaId = if (schema >= CONVERSATION_SCHEMA_VERSION) {
                root.requiredAssistantModeId("selectedPersonaId")
            } else {
                AssistantSelection.DEFAULT_PERSONA_ID
            },
            generationState = enumValueOfStrict(root.getString("generationState")),
            hasUnreadCompletion = root.getBoolean("hasUnreadCompletion"),
            createdAtMs = root.nonNegativeLong("createdAtMs"),
            updatedAtMs = root.nonNegativeLong("updatedAtMs"),
        ).also(::validateConversation)
    }

    fun encodeIndex(index: ConversationIndex): ByteArray {
        require(index.summaries.size <= MAX_CONVERSATIONS) { "Too many conversations" }
        index.activeConversationId?.let(::requireValidId)
        val summaries = JSONArray()
        index.summaries.forEach { summary ->
            requireValidId(summary.id)
            summaries.put(
                JSONObject()
                    .put("id", summary.id)
                    .put("title", summary.title)
                    .put("preview", summary.preview)
                    .putNullable("selectedProfileId", summary.selectedProfileId)
                    .putNullable("selectedModel", summary.selectedModel)
                    .put("generationState", summary.generationState.name)
                    .put("hasUnreadCompletion", summary.hasUnreadCompletion)
                    .put("updatedAtMs", summary.updatedAtMs),
            )
        }
        return JSONObject()
            .put("schema", INDEX_SCHEMA_VERSION)
            .putNullable("activeConversationId", index.activeConversationId)
            .put("summaries", summaries)
            .toBoundedBytes(MAX_INDEX_BYTES, "conversation index")
    }

    fun decodeIndex(bytes: ByteArray): ConversationIndex {
        requireBounded(bytes, MAX_INDEX_BYTES, "conversation index")
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("schema") == INDEX_SCHEMA_VERSION) {
            "Unsupported conversation index schema"
        }
        val array = root.getJSONArray("summaries")
        require(array.length() <= MAX_CONVERSATIONS) { "Too many conversations" }
        val summaries = buildList(array.length()) {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    ConversationSummary(
                        id = item.requiredId("id"),
                        title = item.requiredBoundedString("title", MAX_TITLE_BYTES),
                        preview = item.requiredBoundedString("preview", MAX_PREVIEW_BYTES),
                        selectedProfileId = item.optionalBoundedString(
                            "selectedProfileId",
                            MAX_METADATA_BYTES,
                        ),
                        selectedModel = item.optionalBoundedString(
                            "selectedModel",
                            MAX_METADATA_BYTES,
                        ),
                        generationState = enumValueOfStrict(
                            item.getString("generationState"),
                        ),
                        hasUnreadCompletion = item.getBoolean("hasUnreadCompletion"),
                        updatedAtMs = item.nonNegativeLong("updatedAtMs"),
                    ),
                )
            }
        }
        require(summaries.map { it.id }.distinct().size == summaries.size) {
            "Duplicate conversation id"
        }
        val active = root.optionalBoundedString("activeConversationId", MAX_ID_LENGTH)
        active?.let(::requireValidId)
        return ConversationIndex(active, summaries)
    }

    fun requireValidId(id: String) {
        require(ID_PATTERN.matches(id)) { "Invalid conversation id" }
    }

    private fun validateConversation(conversation: ChatConversation) {
        requireValidId(conversation.id)
        requireUtf8Bound(conversation.title, MAX_TITLE_BYTES, "title")
        requireUtf8Bound(conversation.draft, MAX_DRAFT_BYTES, "draft")
        require(conversation.messages.size <= MAX_MESSAGES) { "Conversation has too many messages" }
        require(conversation.createdAtMs >= 0L && conversation.updatedAtMs >= 0L) {
            "Conversation timestamp is invalid"
        }
        conversation.selectedProfileId?.let {
            requireUtf8Bound(it, MAX_METADATA_BYTES, "selectedProfileId")
        }
        conversation.selectedModel?.let {
            requireUtf8Bound(it, MAX_METADATA_BYTES, "selectedModel")
        }
        AssistantSelection.requireValidId(conversation.selectedSkillId)
        AssistantSelection.requireValidId(conversation.selectedPersonaId)
        conversation.messages.forEach { message ->
            requireValidId(message.id)
            requireUtf8Bound(message.text, MAX_MESSAGE_BYTES, "message text")
            require(message.createdAtMs >= 0L) { "Message timestamp is invalid" }
            listOf(
                message.providerProfileId,
                message.providerLabel,
                message.model,
                message.assistantSkillId,
                message.assistantPersonaId,
            )
                .filterNotNull()
                .forEach { requireUtf8Bound(it, MAX_METADATA_BYTES, "message metadata") }
            message.assistantSkillId?.let(AssistantSelection::requireValidId)
            message.assistantPersonaId?.let(AssistantSelection::requireValidId)
        }
    }

    private fun ChatMessage.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("text", text)
        .put("state", state.name)
        .putNullable("providerProfileId", providerProfileId)
        .putNullable("providerLabel", providerLabel)
        .putNullable("model", model)
        .putNullable("assistantSkillId", assistantSkillId)
        .putNullable("assistantPersonaId", assistantPersonaId)
        .put("createdAtMs", createdAtMs)

    private fun JSONObject.toChatMessage(schema: Int): ChatMessage = ChatMessage(
        id = requiredId("id"),
        role = enumValueOfStrict(getString("role")),
        text = requiredBoundedString("text", MAX_MESSAGE_BYTES),
        state = enumValueOfStrict<ChatMessageState>(getString("state")),
        providerProfileId = optionalBoundedString("providerProfileId", MAX_METADATA_BYTES),
        providerLabel = optionalBoundedString("providerLabel", MAX_METADATA_BYTES),
        model = optionalBoundedString("model", MAX_METADATA_BYTES),
        assistantSkillId = if (schema >= CONVERSATION_SCHEMA_VERSION) {
            optionalAssistantModeId("assistantSkillId")
        } else {
            null
        },
        assistantPersonaId = if (schema >= CONVERSATION_SCHEMA_VERSION) {
            optionalAssistantModeId("assistantPersonaId")
        } else {
            null
        },
        createdAtMs = nonNegativeLong("createdAtMs"),
    )

    private fun JSONObject.requiredAssistantModeId(name: String): String =
        requiredBoundedString(name, MAX_ASSISTANT_MODE_ID_BYTES).also(
            AssistantSelection::requireValidId,
        )

    private fun JSONObject.optionalAssistantModeId(name: String): String? =
        optionalBoundedString(name, MAX_ASSISTANT_MODE_ID_BYTES)?.also(
            AssistantSelection::requireValidId,
        )

    private fun JSONObject.requiredId(name: String): String =
        requiredBoundedString(name, MAX_ID_LENGTH).also(::requireValidId)

    private fun JSONObject.requiredBoundedString(name: String, maxBytes: Int): String {
        require(has(name) && !isNull(name)) { "$name is required" }
        return getString(name).also { requireUtf8Bound(it, maxBytes, name) }
    }

    private fun JSONObject.optionalBoundedString(name: String, maxBytes: Int): String? {
        if (!has(name) || isNull(name)) return null
        return getString(name).also { requireUtf8Bound(it, maxBytes, name) }
    }

    private fun JSONObject.nonNegativeLong(name: String): Long = getLong(name).also {
        require(it >= 0L) { "$name is invalid" }
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.toBoundedBytes(maxBytes: Int, label: String): ByteArray =
        toString().toByteArray(Charsets.UTF_8).also { requireBounded(it, maxBytes, label) }

    private fun requireBounded(bytes: ByteArray, maxBytes: Int, label: String) {
        require(bytes.isNotEmpty() && bytes.size <= maxBytes) { "$label size is invalid" }
    }

    private fun requireUtf8Bound(value: String, maxBytes: Int, name: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= maxBytes) { "$name is too large" }
    }

    private inline fun <reified T : Enum<T>> enumValueOfStrict(value: String): T =
        enumValues<T>().firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unknown enum value")

    private const val LEGACY_CONVERSATION_SCHEMA_VERSION = 1
    private const val CONVERSATION_SCHEMA_VERSION = 2
    private val SUPPORTED_CONVERSATION_SCHEMAS = setOf(
        LEGACY_CONVERSATION_SCHEMA_VERSION,
        CONVERSATION_SCHEMA_VERSION,
    )
    private const val INDEX_SCHEMA_VERSION = 1
    private const val MAX_ID_LENGTH = 128
    private const val MAX_TITLE_BYTES = 512
    private const val MAX_PREVIEW_BYTES = 1_024
    private const val MAX_METADATA_BYTES = 2_048
    private const val MAX_ASSISTANT_MODE_ID_BYTES = 64
    private val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,$MAX_ID_LENGTH}")
}
