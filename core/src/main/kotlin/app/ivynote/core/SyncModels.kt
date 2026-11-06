package app.ivynote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.security.SecureRandom

@Serializable
enum class EntityType {
    @SerialName("note") NOTE,
    @SerialName("folder") FOLDER,
    @SerialName("tag") TAG;

    val wire: String get() = name.lowercase()
}

/** Common shape of the entities merged by `SyncEngine.applyRemote`. */
interface SyncEntity<T> {
    val id: String
    val seq: Long
    val deleted: Boolean
    val hlc: Map<String, String>?

    /** Keeps a field the user changed locally but has not pushed yet. */
    fun keepField(field: String, local: T): T

    /** Applies a pushed field to an entity that has no local copy yet. */
    fun applyField(field: String, value: JsonElement, now: Millis): T

    fun withSeq(seq: Long): T
}

@Serializable
data class Note(
    override val id: String,
    val folderId: String = "",
    val title: String = "",
    val body: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    /** Content version: bumps when the title or body changes. 0 = never pushed. */
    val rev: Long = 0,
    override val seq: Long = 0,
    val trashedAt: Millis = 0,
    override val deleted: Boolean = false,
    val words: Int = 0,
    override val hlc: Map<String, String>? = null,
    val createdAt: Millis = 0,
    val updatedAt: Millis = 0,
) : SyncEntity<Note> {
    val isTrashed: Boolean get() = trashedAt > 0

    override fun keepField(field: String, local: Note): Note = when (field) {
        "title" -> copy(title = local.title)
        "body" -> copy(body = local.body)
        "folderId" -> copy(folderId = local.folderId)
        "tags" -> copy(tags = local.tags)
        "pinned" -> copy(pinned = local.pinned)
        "trashed" -> copy(trashedAt = local.trashedAt)
        else -> this
    }

    override fun applyField(field: String, value: JsonElement, now: Millis): Note = when (field) {
        "title" -> copy(title = value.string ?: title)
        "body" -> copy(body = value.string ?: body)
        "folderId" -> copy(folderId = value.string ?: folderId)
        "tags" -> copy(tags = value.stringList ?: tags)
        "pinned" -> copy(pinned = value.bool ?: pinned)
        "trashed" -> copy(trashedAt = if (value.bool == true) (if (trashedAt > 0) trashedAt else now) else 0)
        else -> this
    }

    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
data class Folder(
    override val id: String,
    val parentId: String = "",
    val name: String = "",
    val sort: Double = 0.0,
    override val seq: Long = 0,
    override val deleted: Boolean = false,
    override val hlc: Map<String, String>? = null,
    val createdAt: Millis = 0,
    val updatedAt: Millis = 0,
) : SyncEntity<Folder> {
    override fun keepField(field: String, local: Folder): Folder = when (field) {
        "name" -> copy(name = local.name)
        "parentId" -> copy(parentId = local.parentId)
        "sort" -> copy(sort = local.sort)
        else -> this
    }

    override fun applyField(field: String, value: JsonElement, now: Millis): Folder = when (field) {
        "name" -> copy(name = value.string ?: name)
        "parentId" -> copy(parentId = value.string ?: parentId)
        "sort" -> copy(sort = value.double ?: sort)
        else -> this
    }

    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
data class Tag(
    override val id: String,
    val name: String = "",
    /** "" or green / blue / amber / pink / purple / gray. */
    val color: String = "",
    override val seq: Long = 0,
    override val deleted: Boolean = false,
    override val hlc: Map<String, String>? = null,
    val createdAt: Millis = 0,
    val updatedAt: Millis = 0,
) : SyncEntity<Tag> {
    override fun keepField(field: String, local: Tag): Tag = when (field) {
        "name" -> copy(name = local.name)
        "color" -> copy(color = local.color)
        else -> this
    }

    override fun applyField(field: String, value: JsonElement, now: Millis): Tag = when (field) {
        "name" -> copy(name = value.string ?: name)
        "color" -> copy(color = value.string ?: color)
        else -> this
    }

    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
data class Attachment(
    val id: String,
    val name: String = "",
    val mime: String = "",
    /** image / video / audio / file. */
    val kind: String = "file",
    /** Plain bytes. */
    val size: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    /** Plain SHA-256, hex. */
    val sha256: String = "",
    /** local | s3 on a server; "device" for a local library's own files. */
    val storage: String = "",
    val hasThumb: Boolean = false,
    /** How many notes reference it (server-computed; local libraries count themselves). */
    val refs: Int = 0,
    val orphanSince: Millis = 0,
    val trashedAt: Millis = 0,
    /** manual | orphan. */
    val trashReason: String = "",
    val public: Boolean = false,
    /** Only for CDN copies. */
    val publicUrl: String = "",
    val seq: Long = 0,
    val deleted: Boolean = false,
    val createdAt: Millis = 0,
    val updatedAt: Millis = 0,
) {
    val isTrashed: Boolean get() = trashedAt > 0

    companion object {
        /** Guide 4.1: the kind follows the MIME type. */
        fun kindOf(mime: String): String = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "audio"
            else -> "file"
        }
    }
}

/**
 * A file added while the server could not be reached (guide 7.2): kept on the
 * device under a `local-` id until [SyncEngine.flushUploads] uploads it and
 * rewrites the notes that reference it.
 */
@Serializable
data class PendingUpload(
    /** `local-<id>`. */
    val id: String,
    val name: String,
    val mime: String,
    val size: Long = 0,
    val createdAt: Millis = 0,
    /** Why the server refused it; not retried until the app restarts. */
    val failed: String? = null,
)

// Wire format (guide 6.3, 6.5)

@Serializable
enum class Op {
    @SerialName("upsert") UPSERT,
    @SerialName("delete") DELETE,
}

@Serializable
data class Change(
    val type: EntityType,
    val id: String,
    val op: Op,
    val fields: Map<String, JsonElement>? = null,
    val hlc: String,
    val baseRev: Long? = null,
    val device: String? = null,
)

@Serializable
data class ChangeResult(
    val type: EntityType,
    val id: String,
    val ok: Boolean = false,
    val error: String? = null,
    val seq: Long? = null,
    val rev: Long? = null,
    val merged: Boolean? = null,
    val body: String? = null,
    val conflictCopy: String? = null,
)

@Serializable
data class PushResponse(val results: List<ChangeResult> = emptyList(), val seq: Long = 0)

@Serializable
data class PullResponse(
    val seq: Long = 0,
    val folders: List<Folder> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val notes: List<Note> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
)

/** A local change waiting to be pushed. One per entity (`key = type:id`). */
@Serializable
data class OutboxEntry(
    val key: String,
    val type: EntityType,
    val id: String,
    val op: Op,
    val fields: Map<String, JsonElement> = emptyMap(),
    val hlc: String,
    val baseRev: Long? = null,
    val device: String,
    /** Bumped on every local edit, to tell whether it changed during a push. */
    val version: Int = 0,
    /**
     * The HLC of the first edit: pushes go out in the order entities were
     * touched, so a new folder reaches the server before notes moved into it.
     */
    val order: String = hlc,
    /** Why the server refused it. Not pushed again until edited or restarted. */
    val blocked: String? = null,
) {
    val change: Change
        get() = Change(
            type = type, id = id, op = op,
            fields = if (op == Op.UPSERT) fields else null,
            hlc = hlc,
            baseRev = if (fields.containsKey("body")) baseRev else null,
            device = device,
        )
}

private val random = SecureRandom()
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** 16 random `[a-z0-9]`, from a cryptographically secure source (guide 4.3). */
fun newEntityId(length: Int = 16): String =
    buildString(length) { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

// JsonElement helpers

internal val JsonElement.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
internal val JsonElement.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal val JsonElement.double: Double? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
internal val JsonElement.stringList: List<String>? get() = (this as? JsonArray)?.mapNotNull { it.string }
