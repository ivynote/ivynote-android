package app.ivynote.android.editor

import android.util.Log
import android.webkit.WebResourceResponse
import app.ivynote.core.IvyHttp
import app.ivynote.core.StoredAccount
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serves `ivy://res/<id>` to the editor page: files kept on this device
 * (queued uploads, a local library's attachments) as they are; others from
 * the disk cache when this device has them, otherwise from
 * `GET /attachments/{id}/raw` with the account's token (guide 7.3).
 * Attachments never change under the same id, so a cached file is kept and
 * works offline. Byte ranges pass through, so videos seek. Runs on the web
 * view's network thread; [fetch] serves the native screens.
 */
class AttachmentLoader(
    private val account: StoredAccount?,
    private val dir: File,
    /** The file on this device for an id, if it lives here (thread-safe). */
    private val local: (String) -> File? = { null },
) {
    /** MIME types of local files, refreshed from the main thread. */
    @Volatile var localTypes: Map<String, String> = emptyMap()

    /**
     * The file for [id] on disk, downloading it into the cache first when
     * needed; null when it cannot be had (offline, gone). Blocking: call on IO.
     */
    fun fetch(id: String, thumb: Boolean): File? {
        if (!ID.matches(id)) return null
        local(id)?.let { return it }
        val file = File(dir, if (thumb) "$id.thumb" else id)
        if (file.exists()) return file.also { it.setLastModified(System.currentTimeMillis()) }
        val account = account ?: return null
        val url = "${account.server.apiBase}/attachments/$id/raw" + if (thumb) "?thumb=1" else ""
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${account.token}")
            .header("User-Agent", IvyHttp.userAgent)
            .build()
        return try {
            IvyHttp.client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return null
                dir.mkdirs()
                val tmp = File(dir, file.name + ".part")
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                File(dir, file.name + ".type").writeText(response.header("Content-Type") ?: "application/octet-stream")
                tmp.renameTo(file)
                prune()
                file
            }
        } catch (e: Exception) {
            Log.d("ivy", "attachment unavailable: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The cached or local file's MIME type, if known. */
    fun typeOf(id: String): String? =
        localTypes[id] ?: File(dir, "$id.type").takeIf { it.exists() }?.readText()

    fun load(id: String, thumb: Boolean, range: String?): WebResourceResponse {
        if (!ID.matches(id)) return status(404, "Not Found")
        local(id)?.let { f ->
            // Kept here: no thumbnail, the original serves both.
            val type = localTypes[id] ?: "application/octet-stream"
            return if (range != null) partial(f, type, range) else whole(f, type)
        }
        val file = File(dir, if (thumb) "$id.thumb" else id)
        val typeFile = File(dir, file.name + ".type")
        if (file.exists()) {
            val type = typeFile.takeIf { it.exists() }?.readText() ?: "application/octet-stream"
            return if (range != null) partial(file, type, range) else whole(file, type)
        }
        val account = account ?: return status(404, "Not Found")
        val url = "${account.server.apiBase}/attachments/$id/raw" + if (thumb) "?thumb=1" else ""
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${account.token}")
            .header("User-Agent", IvyHttp.userAgent)
            .apply { if (range != null) header("Range", range) }
            .build()
        return try {
            val response = IvyHttp.client.newCall(request).execute()
            val body = response.body
            val type = response.header("Content-Type") ?: "application/octet-stream"
            if (!response.isSuccessful || body == null) {
                response.close()
                return status(response.code, "Error")
            }
            val length = body.contentLength()
            if (range == null && response.code == 200 && length in 0..MAX_CACHED) {
                // Small enough to keep: store it, then serve the stored copy.
                dir.mkdirs()
                val tmp = File(dir, file.name + ".part")
                body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                response.close()
                typeFile.writeText(type)
                tmp.renameTo(file)
                prune()
                whole(file, type)
            } else {
                // A range, or too large to cache: stream it through.
                val headers = buildMap {
                    for (name in listOf("Content-Range", "Accept-Ranges", "Content-Length")) response.header(name)?.let { put(name, it) }
                }
                WebResourceResponse(mime(type), charset(type), response.code, reason(response.code), headers,
                    object : FilterInputStream(body.byteStream()) {
                        override fun close() {
                            super.close()
                            response.close()
                        }
                    })
            }
        } catch (e: Exception) {
            Log.d("ivy", "attachment unavailable: ${e.javaClass.simpleName}")
            status(502, "Bad Gateway")
        }
    }

    private fun whole(file: File, type: String): WebResourceResponse {
        file.setLastModified(System.currentTimeMillis())
        return WebResourceResponse(mime(type), charset(type), 200, "OK",
            mapOf("Content-Length" to file.length().toString(), "Accept-Ranges" to "bytes", "Cache-Control" to "no-store"),
            file.inputStream())
    }

    private fun partial(file: File, type: String, range: String): WebResourceResponse {
        val total = file.length()
        val m = RANGE.matchEntire(range.trim()) ?: return whole(file, type)
        var start = m.groupValues[1].toLongOrNull()
        var end = m.groupValues[2].toLongOrNull()
        if (start == null) {
            // "bytes=-N": the last N bytes.
            val n = end ?: return whole(file, type)
            start = maxOf(0, total - n)
            end = total - 1
        }
        end = minOf(end ?: (total - 1), total - 1)
        if (start > end || start >= total) return status(416, "Range Not Satisfiable")
        val count = end - start + 1
        val raf = RandomAccessFile(file, "r").apply { seek(start) }
        val stream = object : InputStream() {
            var left = count
            override fun read(): Int {
                if (left <= 0) return -1
                left--
                return raf.read()
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                if (n > 0) left -= n
                return n
            }
            override fun close() = raf.close()
        }
        return WebResourceResponse(mime(type), charset(type), 206, "Partial Content",
            mapOf("Content-Range" to "bytes $start-$end/$total", "Content-Length" to count.toString(), "Accept-Ranges" to "bytes"),
            stream)
    }

    /** Keeps the cache under its size, dropping what was used longest ago. */
    private fun prune() {
        val files = dir.listFiles()?.filter { !it.name.endsWith(".type") && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_TOTAL) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL * 8 / 10) break
            total -= f.length()
            f.delete()
            File(dir, f.name + ".type").delete()
        }
    }

    private fun status(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun mime(type: String) = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
    private fun charset(type: String) = type.substringAfter("charset=", "").trim().ifEmpty { null }
    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        else -> "Status"
    }

    companion object {
        /** Puts a file that was just uploaded into the cache under its id. */
        fun store(dir: File, id: String, source: File, mime: String) {
            if (!ID.matches(id) || source.length() > MAX_CACHED) return
            dir.mkdirs()
            val target = File(dir, id)
            if (target.exists()) return
            val tmp = File(dir, "$id.part")
            source.copyTo(tmp, overwrite = true)
            File(dir, "$id.type").writeText(mime.ifEmpty { "application/octet-stream" })
            tmp.renameTo(target)
        }

        private val ID = Regex("[A-Za-z0-9_-]{8,64}")
        private val RANGE = Regex("bytes=(\\d*)-(\\d*)")
        private const val MAX_CACHED = 64L shl 20
        private const val MAX_TOTAL = 512L shl 20
    }
}
