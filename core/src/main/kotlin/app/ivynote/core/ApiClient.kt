package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.io.IOException

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

    suspend fun register(username: String, password: String, email: String = "", displayName: String = ""): Pair<String, User> {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            if (email.isNotEmpty()) put("email", email)
            if (displayName.isNotEmpty()) put("displayName", displayName)
        }
        val r: AuthResponse = send("POST", "auth/register", body, AuthResponse.serializer())
        return r.token to r.user
    }

    /** Ends only this device's session. */
    suspend fun logout() {
        send("POST", "auth/logout", null, JsonObject.serializer())
    }

    suspend fun me(): User = get("me")

    // Sync (guide 5.4)

    override suspend fun snapshot(): PullResponse = get("sync/snapshot")
    override suspend fun pull(since: Long): PullResponse = get("sync/pull?since=$since")

    override suspend fun push(changes: List<Change>): PushResponse {
        val body = IvyJson.encodeToString(PushBody.serializer(), PushBody(changes))
        return sendRaw("POST", "sync/push", body.toByteArray(Charsets.UTF_8), PushResponse.serializer())
    }

    @Serializable
    private data class PushBody(val changes: List<Change>)

    // Plumbing

    fun url(path: String): String = "${server.apiBase}/$path"

    fun headers(json: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", IvyHttp.userAgent)
        token?.let { put("Authorization", "Bearer $it") }
        if (json) put("Content-Type", "application/json")
    }

    private suspend inline fun <reified T> get(path: String): T = sendRaw("GET", path, null, serializer<T>())

    private suspend fun <T> send(method: String, path: String, body: JsonObject?, out: KSerializer<T>): T =
        sendRaw(method, path, body?.toString()?.toByteArray(Charsets.UTF_8), out)

    private suspend fun <T> sendRaw(method: String, path: String, body: ByteArray?, out: KSerializer<T>): T {
        val response = try {
            transport.send(HttpRequest(method, url(path), headers(body != null || method != "GET"), body))
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
}
