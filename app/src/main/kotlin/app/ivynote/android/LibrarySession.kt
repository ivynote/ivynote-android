package app.ivynote.android

import android.content.Context
import android.util.Log
import app.ivynote.android.editor.EditorHost
import app.ivynote.android.platform.SqliteDocumentStore
import app.ivynote.android.platform.networkAvailable
import app.ivynote.core.ApiClient
import app.ivynote.core.ApiError
import app.ivynote.core.DocumentStore
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
    private val api: ApiClient? = account?.let { ApiClient(it.server, it.token) }
    private var events: Job? = null
    private var network: Job? = null
    private var active = false
    private var closed = false

    private var editorHost: EditorHost? = null

    /** The note editor; one web view per library, created on first use. */
    val editor: EditorHost
        get() = editorHost ?: EditorHost(context, this).also { editorHost = it }

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
            val engine = SyncEngine(store, acct?.let { ApiClient(it.server, it.token) }, device, scope)
            withContext(Dispatchers.IO) { engine.load() }
            return LibrarySession(context.applicationContext, ref, acct, storeFile, store, engine, scope)
        }
    }
}
