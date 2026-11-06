package app.ivynote.core

import kotlinx.serialization.Serializable

// What the REST endpoints answer beyond sync (guide 5.5, 5.6). These need
// the server: the UI says so when offline.

/** `POST /attachments`: the stored file, or the one it duplicates (same user, same SHA-256). */
@Serializable
data class UploadResult(val attachment: Attachment, val duplicate: Boolean = false)

/** `GET /attachments`. */
@Serializable
data class MediaList(
    val items: List<Attachment> = emptyList(),
    /** all, image, video, audio, file, orphaned. */
    val counts: Map<String, Int> = emptyMap(),
    val policy: MediaPolicy = MediaPolicy(),
)

@Serializable
data class MediaPolicy(val mode: String = "", val orphanDays: Int = 0, val trashDays: Int = 0, val cdn: Boolean = false)

/** A note referencing an attachment, as `GET /attachments/{id}` lists it. */
@Serializable
data class NoteRef(val id: String, val title: String = "", val folderId: String = "")

/** `GET /attachments/{id}`. */
@Serializable
data class AttachmentDetail(val attachment: Attachment, val notes: List<NoteRef> = emptyList())

@Serializable
data class PublicLink(val url: String = "")

/** One saved version of a note (guide 5.5), newest first; [body] only from `history/{rev}`. */
@Serializable
data class Revision(
    val rev: Long,
    val title: String = "",
    val device: String = "",
    val size: Long = 0,
    val createdAt: Millis = 0,
    val body: String? = null,
)

/** A note's public link (guide 5.5). */
@Serializable
data class Share(
    val id: String,
    val noteId: String = "",
    val expiresAt: Millis = 0,
    val enabled: Boolean = false,
    val views: Int = 0,
    val lastViewedAt: Millis = 0,
    val createdAt: Millis = 0,
    val updatedAt: Millis = 0,
    val hasPassword: Boolean = false,
    /** The access password, readable by the owner (newer servers). */
    val password: String = "",
    val noteTitle: String = "",
    val expired: Boolean = false,
) {
    /** Public links need a password; one made before that rule shows nothing until it gets one. */
    val live: Boolean get() = enabled && hasPassword

    companion object {
        const val MIN_PASSWORD = 6
        const val MAX_PASSWORD_BYTES = 72

        fun passwordError(p: String): String? = when {
            p.length < MIN_PASSWORD -> L10n.s.sharePasswordMin(MIN_PASSWORD)
            p.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES -> L10n.s.sharePasswordMax(MAX_PASSWORD_BYTES)
            else -> null
        }

        /** Eight characters without look-alikes such as l/1 and O/0, as on the web. */
        fun generatePassword(): String {
            val abc = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
            val r = java.security.SecureRandom()
            return buildString { repeat(8) { append(abc[r.nextInt(abc.length)]) } }
        }

        /** The message the web copies with 复制链接和密码. */
        fun message(title: String, link: String, password: String): String = L10n.s.shareMessage(title, link, password)
    }
}
