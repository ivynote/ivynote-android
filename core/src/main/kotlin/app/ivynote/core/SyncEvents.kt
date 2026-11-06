package app.ivynote.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * `GET /sync/events`: server-sent events announcing the latest `seq`
 * (guide 6.9). Only a hint to pull; a lost event does no harm.
 */
class SyncEvents(private val api: ApiClient, private val client: OkHttpClient = IvyHttp.streaming) {

    /** Emits each announced `seq` until the connection drops; throws [ApiError] then. */
    fun stream(): Flow<Long> = flow {
        val builder = Request.Builder().url(api.url("sync/events"))
        for ((k, v) in api.headers(json = false)) builder.header(k, v)
        builder.header("Accept", "text/event-stream")
        val call = client.newCall(builder.build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                throw ApiError.Unreachable(e.message ?: "stream failed")
            }
            response.use { r ->
                if (r.code != 200) throw ApiError.Server(r.code, "")
                val source = r.body?.source() ?: throw ApiError.InvalidResponse()
                val parser = Parser()
                while (true) {
                    val line = try { source.readUtf8Line() } catch (e: IOException) { throw ApiError.Unreachable(e.message ?: "stream closed") }
                        ?: break
                    parser.feed(line)?.let { emit(it) }
                }
            }
        } finally {
            handle.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** Reads an event stream line by line. */
    class Parser {
        private var event = ""

        /** Feeds one line; returns a `seq` when a `seq` event's data arrives. */
        fun feed(line: String): Long? {
            if (line.isEmpty()) { event = ""; return null }
            if (line.startsWith(":")) return null
            val i = line.indexOf(':')
            val field = if (i < 0) line else line.substring(0, i)
            var value = if (i < 0) "" else line.substring(i + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    val e = event
                    event = ""
                    if (e == "seq" || e.isEmpty()) return value.trim().toLongOrNull()
                }
            }
            return null
        }
    }
}
