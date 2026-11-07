package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * The Ivynote REST API (`{server}/api/v1`), guide chapter 5. Holds no state
 * but the address and token; every call is a suspend function that throws
 * [ApiError].
 */
class ApiClient(
    val server: ServerAddress,
    val token: String? = null,
    private val transport: HttpTransport = OkHttpTransport(),
) : SyncTransport {

    fun authorized(token: String?) = ApiClient(server, token, transport)

    // Public

    suspend fun health(): Health = get("health")
    suspend fun info(): ServerInfo = get("info")

    /** Checks that the address is an Ivynote server and returns what it allows. */
    suspend fun probe(): ServerInfo {
        val h = health()
        if (!h.ok) throw ApiError.InvalidResponse()
        return info()
    }

    suspend fun login(identity: String, password: String): Pair<String, User> {
        val body = buildJsonObject { put("identity", identity); put("password", password) }
        val r: AuthResponse = send("POST", "auth/login", body, AuthResponse.serializer())
        return r.token to r.user
    }

    /** [language]: the interface language at sign-up; it becomes the account's. */
    suspend fun register(username: String, password: String, email: String = "", displayName: String = "", language: String = L10n.language.tag): Pair<String, User> {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            if (email.isNotEmpty()) put("email", email)
            if (displayName.isNotEmpty()) put("displayName", displayName)
            if (language.isNotEmpty()) put("language", language)
        }
        val r: AuthResponse = send("POST", "auth/register", body, AuthResponse.serializer())
        return r.token to r.user
    }

    /** Ends only this device's session. */
    suspend fun logout() {
        send("POST", "auth/logout", null, JsonObject.serializer())
    }

    suspend fun me(): User = get("me")

    /** Sets the account's interface language; "" clears it (follow the device). */
    suspend fun setLanguage(language: String): User =
        send("PATCH", "me", buildJsonObject { put("language", language) }, User.serializer())

    // Sync (guide 5.4)

    override suspend fun snapshot(): PullResponse = get("sync/snapshot")
    override suspend fun pull(since: Long): PullResponse = get("sync/pull?since=$since")

    override suspend fun push(changes: List<Change>): PushResponse {
        val body = IvyJson.encodeToString(PushBody.serializer(), PushBody(changes))
        return sendRaw("POST", "sync/push", body.toByteArray(Charsets.UTF_8), PushResponse.serializer())
    }

    @Serializable
    private data class PushBody(val changes: List<Change>)

    // Attachments (guide 5.6, 7)

    /** Uploads a file as `multipart/form-data`; the server de-duplicates by content. */
    override suspend fun upload(file: File, name: String, mime: String): UploadResult {
        val req = HttpRequest("POST", url("attachments"), headers(json = false), upload = FileUpload("file", name, mime, file))
        return decode(execute(req), UploadResult.serializer())
    }

    suspend fun media(kind: String = "", trashed: Boolean = false, orphaned: Boolean = false, query: String = ""): MediaList {
        val q = buildList {
            if (kind.isNotEmpty()) add("kind=" + enc(kind))
            if (trashed) add("trashed=1")
            if (orphaned) add("orphaned=1")
            if (query.isNotBlank()) add("q=" + enc(query.trim()))
        }.joinToString("&")
        return get("attachments" + if (q.isEmpty()) "" else "?$q")
    }

    suspend fun attachment(id: String): AttachmentDetail = get("attachments/${enc(id)}")

    suspend fun renameAttachment(id: String, name: String): Attachment =
        send("PATCH", "attachments/${enc(id)}", buildJsonObject { put("name", name) }, Attachment.serializer())

    suspend fun trashAttachment(id: String): Attachment = send("POST", "attachments/${enc(id)}/trash", null, Attachment.serializer())
    suspend fun restoreAttachment(id: String): Attachment = send("POST", "attachments/${enc(id)}/restore", null, Attachment.serializer())

    /** Deletes for good; only a file in the trash (400 otherwise). */
    suspend fun purgeAttachment(id: String) {
        send("DELETE", "attachments/${enc(id)}", null, JsonObject.serializer())
    }

    /** A public `/r/` (or CDN) address; the same one again when it is already public. */
    suspend fun publishAttachment(id: String): String = send("POST", "attachments/${enc(id)}/publish", null, PublicLink.serializer()).url
    suspend fun unpublishAttachment(id: String): Attachment = send("DELETE", "attachments/${enc(id)}/publish", null, Attachment.serializer())

    // Notes (guide 5.5)

    suspend fun backlinks(noteId: String): List<Note> = sendRaw("GET", "notes/${enc(noteId)}/backlinks", null, ListSerializer(Note.serializer()))
    suspend fun history(noteId: String): List<Revision> = sendRaw("GET", "notes/${enc(noteId)}/history", null, ListSerializer(Revision.serializer()))
    suspend fun revision(noteId: String, rev: Long): Revision = get("notes/${enc(noteId)}/history/$rev")

    /** The note as Markdown; [public] publishes its attachments and links them by public address. */
    suspend fun exportNote(noteId: String, public: Boolean): String {
        val req = HttpRequest("GET", url("notes/${enc(noteId)}/export" + if (public) "?public=1" else ""), headers(json = false) + ("Accept" to "text/markdown"))
        return execute(req).body.decodeToString()
    }

    suspend fun share(noteId: String): Share? = sendRaw("GET", "notes/${enc(noteId)}/share", null, Share.serializer().nullable)

    /**
     * `enabled` and `expiresAt` are always written; [password] null keeps the
     * current one, "" removes it (guide 5.5).
     */
    suspend fun setShare(noteId: String, enabled: Boolean, expiresAt: Millis, password: String? = null): Share {
        val body = buildJsonObject {
            put("enabled", enabled)
            put("expiresAt", expiresAt)
            if (password != null) put("password", password)
        }
        return send("PUT", "notes/${enc(noteId)}/share", body, Share.serializer())
    }

    suspend fun shares(): List<Share> = sendRaw("GET", "shares", null, ListSerializer(Share.serializer()))

    suspend fun deleteShare(id: String) {
        send("DELETE", "shares/${enc(id)}", null, JsonObject.serializer())
    }

    /** What people open in a browser: `{server}/s/{id}`. */
    fun shareLink(id: String): String = "${server.base}/s/$id"

    // Plumbing

    fun url(path: String): String = "${server.apiBase}/$path"

    fun headers(json: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", IvyHttp.userAgent)
        // The interface language, so server messages can match it.
        put("Accept-Language", L10n.language.tag)
        token?.let { put("Authorization", "Bearer $it") }
        if (json) put("Content-Type", "application/json")
    }

    private suspend inline fun <reified T> get(path: String): T = sendRaw("GET", path, null, serializer<T>())

    private suspend fun <T> send(method: String, path: String, body: JsonObject?, out: KSerializer<T>): T =
        sendRaw(method, path, body?.toString()?.toByteArray(Charsets.UTF_8), out)

    private suspend fun <T> sendRaw(method: String, path: String, body: ByteArray?, out: KSerializer<T>): T =
        decode(execute(HttpRequest(method, url(path), headers(body != null || method != "GET"), body)), out)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Sends a request; throws [ApiError] unless the status is 2xx. */
    private suspend fun execute(request: HttpRequest): HttpResponse {
        val response = try {
            transport.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            throw ApiError.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            throw ApiError.Unreachable(e.message ?: "invalid url")
        }
        if (response.status !in 200..299) {
            val message = runCatching {
                IvyJson.decodeFromString(ErrorBody.serializer(), response.body.decodeToString()).error
            }.getOrNull().orEmpty()
            throw ApiError.Server(response.status, message)
        }
        return response
    }

    private fun <T> decode(response: HttpResponse, out: KSerializer<T>): T {
        return try {
            IvyJson.decodeFromString(out, response.body.decodeToString())
        } catch (e: Exception) {
            throw ApiError.InvalidResponse()
        }
    }

    @Serializable
    private data class ErrorBody(val error: String = "")
}

/** What the sync engine needs from the server (guide 5.4). */
interface SyncTransport {
    suspend fun snapshot(): PullResponse
    suspend fun pull(since: Long): PullResponse
    suspend fun push(changes: List<Change>): PushResponse

    /** `POST /attachments` (guide 7.1). */
    suspend fun upload(file: File, name: String, mime: String): UploadResult =
        throw ApiError.Server(501, "uploads are not supported")
}
