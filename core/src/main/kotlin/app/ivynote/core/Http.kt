package app.ivynote.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP request, independent of the HTTP library (stubbed in tests). */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** A file sent as `multipart/form-data` instead of [body] (guide 5.6). */
    val upload: FileUpload? = null,
)

/** One file field of a multipart request, streamed from disk. */
data class FileUpload(val field: String, val name: String, val mime: String, val file: File)

class HttpResponse(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

/** Sends one HTTP request. Swapped for a stub in tests. */
fun interface HttpTransport {
    /** Throws [IOException] when there is no answer. */
    suspend fun send(request: HttpRequest): HttpResponse
}

/** The shared HTTP stack: Bearer token only, no cookies, no response cache (guide 5.1, 8.2). */
object IvyHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** For the event stream: the server pings every 25 s, anything quieter is dead. */
    val streaming: OkHttpClient by lazy {
        client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }

    /** Set by the app, e.g. `Ivynote-Android/1.0.0`. */
    @Volatile var userAgent: String = "Ivynote-Android"
}

class OkHttpTransport(private val client: OkHttpClient = IvyHttp.client) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        for ((k, v) in request.headers) builder.header(k, v)
        val upload = request.upload
        if (upload != null) {
            val part = upload.file.asRequestBody(upload.mime.ifEmpty { "application/octet-stream" }.toMediaType())
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(upload.field, upload.name, part).build()
            builder.removeHeader("Content-Type")
            builder.method(request.method, form)
        } else {
            val type = request.headers["Content-Type"]?.toMediaType()
            builder.method(request.method, request.body?.toRequestBody(type) ?: if (request.method == "POST" || request.method == "PUT" || request.method == "PATCH") ByteArray(0).toRequestBody(type) else null)
        }
        return client.newCall(builder.build()).await().use { r ->
            HttpResponse(r.code, r.body?.bytes() ?: ByteArray(0), r.headers.toMap())
        }
    }
}

/** Runs the call on OkHttp's pool; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
}
