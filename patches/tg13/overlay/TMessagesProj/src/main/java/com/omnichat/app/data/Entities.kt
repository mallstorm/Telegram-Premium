package com.omnichat.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Secret is ciphertext and MUST never be included in chat exports. */
@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val encryptedApiKey: String,
    val model: String,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val protocol: String = "openai-chat",
    val systemPrompt: String = "",
    val allowLocalHttp: Boolean = false,
    val inputUsdPerMillion: Double = 0.0,
    val outputUsdPerMillion: Double = 0.0,
    /** Stable registry ID (not display name): dispatches vendor-specific auth. */
    val providerType: String = "custom",
    /** GigaChat OAuth scope or Yandex AI Studio folder/project ID. Not a secret. */
    val authScope: String = "",
    /** Last successful per-connection model discovery, persisted for offline picker. */
    val modelsJson: String = "[]"
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val instructions: String = "",
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val storageMode: String = "internal",
    val storageUri: String = "",
    val state: String = "PLANNING",
    val projectType: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chats", indices = [Index("projectId")])
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val providerId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: Long? = null,
    val archived: Boolean = false,
    val pinned: Boolean = false,
    /** Persisted model selection for this chat. Switching models never creates a new chat. */
    val activeModel: String = "",
    /** Rolling semantic memory. Raw messages remain stored in the messages table. */
    val memorySummary: String = "",
    /** Last raw message already folded into memorySummary. */
    val memoryUntilMessageId: Long = 0,
    val memoryUpdatedAt: Long = 0,
    /**
     * Provider-specific continuation state keyed by provider/model.
     * Contains opaque web conversation metadata only; never credentials.
     */
    val transportStateJson: String = "{}"
)

@Entity(tableName = "messages", foreignKeys = [
    ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val status: String = "complete",
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** JSON array of attachments. Paths stay private to the application. */
    val attachmentsJson: String = "[]"
)

@Entity(
    tableName = "visual_context",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("updatedAt")]
)
data class VisualContextEntity(
    @PrimaryKey val chatId: Long,
    val rootMessageId: Long,
    val lastMessageId: Long,
    val lastAssetPath: String,
    val lastAssetMime: String,
    val lastAssetName: String,
    val lastAssetSize: Long,
    val lastPrompt: String,
    val subjectSummary: String,
    val styleSummary: String,
    val providerId: Long,
    val providerType: String,
    val model: String,
    val mode: String,
    /** JSON array of stable subject labels remembered for the current visual scene. */
    val sceneSubjectsJson: String = "[]",
    /** Structured scene memory for natural-language edits across many turns. */
    val sceneStateJson: String = "{}",
    /** Reserved scene-property locks for future preserve-only commands. */
    val lockedFieldsJson: String = "[]",
    /** Current node in the persistent visual-version chain. */
    val activeAssetId: Long = 0,
    /** Subject that pronouns/short follow-ups currently refer to. */
    val activeSubject: String = "",
    /** SUBJECT/BACKGROUND/STYLE/COMPOSITION/CANVAS/GLOBAL. */
    val editScope: String = "GLOBAL",
    val updatedAt: Long = System.currentTimeMillis()
)


@Entity(
    tableName = "visual_assets",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId"), Index("messageId"), Index("createdAt")]
)
data class VisualAssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val messageId: Long,
    val parentAssetId: Long? = null,
    val path: String,
    val mime: String,
    val name: String,
    val size: Long,
    val prompt: String,
    val subjectSummary: String,
    val styleSummary: String,
    val sceneStateJson: String = "{}",
    val lockedFieldsJson: String = "[]",
    val activeSubject: String = "",
    val editScope: String = "GLOBAL",
    val providerId: Long,
    val providerType: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** Token counts are null when the provider did not return actual usage. */
@Entity(tableName = "usage_events", indices = [Index("chatId"), Index("createdAt")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val providerId: Long,
    val model: String,
    val protocol: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val estimatedInputTokens: Long = 0,
    val estimatedOutputTokens: Long = 0,
    val inputRateUsdPerMillion: Double = 0.0,
    val outputRateUsdPerMillion: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val inputCount: Long get() = inputTokens ?: estimatedInputTokens
    val outputCount: Long get() = outputTokens ?: estimatedOutputTokens
    val estimated: Boolean get() = inputTokens == null || outputTokens == null
    val costUsd: Double get() = (inputCount * inputRateUsdPerMillion + outputCount * outputRateUsdPerMillion) / 1_000_000.0
}

/** User-defined HTTPS text tool: no credentials are stored or sent for extensions. */
@Entity(tableName = "tool_extensions")
data class ToolExtensionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val url: String,
    val method: String = "GET",
    val enabled: Boolean = true
)
