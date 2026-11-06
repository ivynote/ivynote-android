package app.ivynote.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import app.ivynote.android.editor.AttachmentLoader
import app.ivynote.android.editor.EditorHost
import app.ivynote.android.platform.SqliteDocumentStore
import app.ivynote.android.platform.networkAvailable
import app.ivynote.core.L10n
import app.ivynote.core.AddedFile
import app.ivynote.core.ApiClient
import app.ivynote.core.ApiError
import app.ivynote.core.DocumentStore
import app.ivynote.core.MediaLibrary
import app.ivynote.core.LibraryRef
import app.ivynote.core.StoredAccount
import app.ivynote.core.SyncEngine
import app.ivynote.core.SyncEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One open library: the engine over its database plus, for a server library,
 * what drives sync (start-up, server events, network changes, app lifecycle;
 * guide 6.8). A local library has no account and never syncs. The Android
 * counterpart of the Apple client's `Library`.
 */
class LibrarySession private constructor(
    private val context: Context,
    val ref: LibraryRef,
    /** The signed-in account, for a server library. */
    val account: StoredAccount?,
    private val storeFile: File,
    private val store: DocumentStore,
    val engine: SyncEngine,
    private val scope: CoroutineScope,
) {
    /** The REST API for what sync does not cover (history, sharing, media); null for a local library. */
    val api: ApiClient? = account?.let { ApiClient(it.server, it.token) }

    /** The library's files: the server's media library, or this device's for a local library. */
    val media = MediaLibrary(engine, api)

    /** Files being added right now (the note bar's spinner, guide 11.3). */
    private val _adding = MutableStateFlow(0)
    val adding: StateFlow<Int> = _adding.asStateFlow()

    init {
        val cache = IvyApp.instance.mediaDir(ref.id)
        // What was just uploaded shows from the cache, offline too, without a download.
        engine.onUploaded = { att, file ->
            runCatching { AttachmentLoader.store(cache, att.id, file, att.mime) }
        }
    }

    /**
     * Adds a picked or captured file to the library (guide 7.1): uploaded,
     * queued while offline, or kept on this device in a local library.
     * Returns the Markdown that embeds it.
     */
    suspend fun addFile(uri: Uri, fallbackName: String? = null): AddedFile {
        _adding.update { it + 1 }
        val tmp = File(IvyApp.instance.transferDir(), "in-" + System.nanoTime())
        try {
            val resolver = context.contentResolver
            val (name, mime) = withContext(Dispatchers.IO) {
                var name: String? = null
                runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) name = c.getString(0)
                    }
                }
                val type = resolver.getType(uri)
                    ?: name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                    ?: "application/octet-stream"
                (resolver.openInputStream(uri) ?: throw java.io.IOException(L10n.s.cannotReadFile)).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                (name ?: fallbackName ?: defaultName(type)) to type
            }
            return engine.addFile(tmp, name, mime)
        } finally {
            withContext(Dispatchers.IO) { tmp.delete() }
            _adding.update { it - 1 }
        }
    }

    /** Guide 7.1: a pasted or captured image without a name is `image-<timestamp>.<ext>`. */
    private fun defaultName(mime: String): String {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
        return (if (mime.startsWith("image/")) "image-" else "file-") + "$stamp.$ext"
    }
    private var events: Job? = null
    private var network: Job? = null
    private var active = false
    private var closed = false

    private var editorHost: EditorHost? = null

    /** The note editor; one web view per library, created on first use. */
    val editor: EditorHost
        get() = editorHost ?: EditorHost(context, this).also { editorHost = it }

    /** Runs work that belongs to the library rather than a screen (an upload keeps going when a sheet closes). */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /** The app is (back) in the foreground: sync now and listen for changes. */
    fun activate() {
        if (active || closed) return
        active = true
        if (api == null) return
        scope.launch { engine.sync() }
        connectEvents()
        watchNetwork()
    }

    /** The app went to the background: save now, stop listening. */
    fun deactivate() {
        active = false
        engine.persistNow()
        events?.cancel()
        events = null
        network?.cancel()
        network = null
    }

    /** One push and pull, e.g. before going to the background or signing out. */
    suspend fun flush() {
        if (closed) return
        engine.sync()
        engine.persistNow()
        withContext(Dispatchers.IO) { store.flush() }
    }

    /** Stops everything; the database stays for the next open. */
    fun close() {
        if (closed) return
        closed = true
        deactivate()
        engine.stop()
        editorHost?.destroy()
        scope.cancel()
        Thread { store.close() }.start()
    }

    /** Deletes this library's local copy: database and cached attachments (guide 6.10). */
    fun destroy() {
        if (closed) return
        closed = true
        deactivate()
        engine.stop()
        editorHost?.destroy()
        scope.cancel()
        val media = IvyApp.instance.mediaDir(ref.id)
        Thread {
            store.close()
            DocumentStore.remove(storeFile)
            media.deleteRecursively()
        }.start()
    }

    // Server events (guide 6.9)

    private fun connectEvents() {
        events?.cancel()
        val api = api ?: return
        val source = SyncEvents(api)
        events = scope.launch {
            var delayMs = 3_000L
            while (true) {
                try {
                    source.stream().collect { seq ->
                        delayMs = 3_000
                        engine.onServerSeq(seq)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    if (e.endsSession) return@launch // the sync loop reports it
                } catch (e: Exception) {
                    Log.d("ivy", "events: ${e.javaClass.simpleName}")
                }
                delay(delayMs)
                delayMs = minOf(delayMs * 2, 60_000)
            }
        }
    }

    // Network (guide 6.8: sync and reconnect when it comes back)

    private fun watchNetwork() {
        network?.cancel()
        var online = true
        network = scope.launch {
            networkAvailable(context).collect { up ->
                val cameBack = up && !online
                online = up
                if (cameBack && active) {
                    launch { engine.sync() }
                    connectEvents()
                }
            }
        }
    }

    companion object {
        /** Opens a library's database off the main thread, then builds its engine on it. */
        suspend fun open(context: Context, ref: LibraryRef, account: StoredAccount?, storeFile: File, device: String): LibrarySession {
            val store = withContext(Dispatchers.IO) { SqliteDocumentStore(storeFile) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
                Log.e("ivy", "library task failed", e)
            })
            val acct = if (ref.isLocal) null else account
            val engine = SyncEngine(store, acct?.let { ApiClient(it.server, it.token) }, device, scope, files = IvyApp.instance.filesDir(ref.id))
            withContext(Dispatchers.IO) { engine.load() }
            return LibrarySession(context.applicationContext, ref, acct, storeFile, store, engine, scope)
        }
    }
}
