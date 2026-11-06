package app.ivynote.android.editor

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.ivynote.android.BuildConfig
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.core.IvyJson
import app.ivynote.core.Note
import app.ivynote.core.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The note editor web view of an open library: the web client's CodeMirror
 * editor with live preview (built by `editor-web/build.sh`), so every client
 * renders Markdown the same way. The page loads once; opening another note
 * swaps the document (guide 9: plain Markdown only).
 *
 * The page is served from the app's assets under
 * `https://appassets.androidplatform.net/assets/editor/`; attachments
 * (`ivy://res/<id>`) load from `/res/<id>` on the same origin, which the app
 * fetches with the account's token and caches on disk, so the token never
 * appears in a URL and the page needs no CORS (guide 8.2).
 */
class EditorHost(appContext: Context, private val session: LibrarySession) {
    private val app = appContext.applicationContext
    private val engine: SyncEngine get() = session.engine
    private val context = MutableContextWrapper(app)
    private val main = Handler(Looper.getMainLooper())
    /** Attachments for the page and for the native screens (thumbnails, previews). */
    val media = AttachmentLoader(session.account, IvyApp.instance.mediaDir(session.ref.id)) { id -> session.engine.localFile(id) }
    private val assets = WebViewAssetLoader.Builder()
        .setDomain(HOST)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
        .build()

    private var ready = false
    private val queued = ArrayList<String>()
    var noteId: String? = null
        private set
    /** The body the page has, to skip echoing its own edits back to it. */
    private var pageBody = ""
    private var dark = false

    private val _webView = MutableStateFlow(create())
    /** Replaced when the page's process dies; the screen re-attaches the new one. */
    val webView: StateFlow<WebView> = _webView.asStateFlow()

    /** The editor has the keyboard (shows the format bar). */
    private val _focused = MutableStateFlow(false)
    val focused: StateFlow<Boolean> = _focused.asStateFlow()

    /** The 0-based body line at the top of the view, for the outline. */
    private val _topLine = MutableStateFlow(0)
    val topLine: StateFlow<Int> = _topLine.asStateFlow()

    /** A followed `[[wiki link]]`: id ("" when no such note) and title. */
    var onOpenNote: ((String, String) -> Unit)? = null

    // Hosting

    /** Puts the web view in [activity]'s window (for text selection menus and the keyboard). */
    fun attach(activity: Context): View {
        context.baseContext = activity
        val view = _webView.value
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
        if (view === _webView.value) context.baseContext = app
    }

    fun destroy() {
        val view = _webView.value
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    // Kotlin → page

    fun open(note: Note, focus: Boolean) {
        noteId = note.id
        pageBody = note.body
        _topLine.value = 0
        sendTitles()
        refreshKinds()
        call("ivy.setTheme($dark)")
        val payload = buildJsonObject {
            put("id", note.id)
            put("body", note.body)
            put("readonly", note.isTrashed)
            put("fontSize", 16)
            put("focus", focus)
        }
        call("ivy.load($payload)")
        if (focus) showKeyboard()
    }

    /** Titles for `[[` completion and link resolution, most recently updated first. */
    fun sendTitles() {
        val list = buildJsonArray {
            for (n in engine.liveNotes.sortedByDescending { it.updatedAt }) {
                add(buildJsonObject {
                    put("id", n.id)
                    put("title", n.title)
                    put("folder", engine.folders[n.folderId]?.name ?: "")
                })
            }
        }
        call("ivy.setTitles($list)")
    }

    /** The note's body changed outside the editor (pull, merge). */
    fun bodyChanged(id: String, body: String) {
        if (id != noteId || body == pageBody) return
        pageBody = body
        call("ivy.setBody(${JsonPrimitive(id)}, ${JsonPrimitive(body)})")
    }

    /** Kinds of every attachment and queued file, so embeds render as image, video, audio or a file card. */
    fun refreshKinds() {
        val kinds = buildJsonObject {
            for (a in engine.attachments.values) put(a.id, a.kind)
            for (u in engine.uploads.values) put(u.id, app.ivynote.core.Attachment.kindOf(u.mime))
        }
        val types = HashMap<String, String>()
        for (u in engine.uploads.values) types[u.id] = u.mime
        if (engine.isLocal) for (a in engine.attachments.values) types[a.id] = a.mime.ifEmpty { "application/octet-stream" }
        media.localTypes = types
        val json = kinds.toString()
        if (json == sentKinds) return
        sentKinds = json
        call("ivy.setKinds($json)")
    }
    private var sentKinds = ""

    /** Inserts Markdown at the cursor, on its own line (an attachment embed). */
    fun insert(markdown: String) = call("ivy.insert(${JsonPrimitive(markdown)}, true)")

    fun setReadonly(ro: Boolean) = call("ivy.setReadonly($ro)")

    fun setTheme(dark: Boolean) {
        this.dark = dark
        call("ivy.setTheme($dark)")
    }

    fun focus() {
        call("ivy.focus(true)")
        showKeyboard()
    }

    fun command(name: String) = call("ivy.command(${JsonPrimitive(name)})")

    /** Scrolls to a 0-based line of the body, e.g. a heading from the outline. */
    fun scrollToLine(line: Int) = call("ivy.scrollToLine($line)")

    fun hideKeyboard() {
        command("blur")
        val view = _webView.value
        view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard() {
        val view = _webView.value
        view.requestFocus()
        main.postDelayed({
            view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
    }

    private fun call(js: String) {
        if (!ready) {
            queued += js
            return
        }
        _webView.value.evaluateJavascript(js, null)
    }

    // Page → Kotlin

    private fun receive(json: String) {
        val m = runCatching { IvyJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        when (m.str("type")) {
            "ready" -> {
                ready = true
                val q = queued.toList()
                queued.clear()
                q.forEach(::call)
            }
            "change" -> {
                val id = m.str("id") ?: return
                val body = m.str("body") ?: return
                if (id != noteId) return
                pageBody = body
                engine.updateNote(id, SyncEngine.NotePatch(body = body))
            }
            "open" -> onOpenNote?.invoke(m.str("id").orEmpty(), m.str("title").orEmpty())
            "focus" -> _focused.value = (m["focused"] as? JsonPrimitive)?.booleanOrNull ?: false
            "scroll" -> _topLine.value = (m["line"] as? JsonPrimitive)?.intOrNull ?: 0
            "error", "log" -> if (BuildConfig.DEBUG) Log.d("ivy", "page ${m.str("type")}: ${m.str("message")}")
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    // The web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val view = WebView(context)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.isVerticalScrollBarEnabled = true
        view.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            cacheMode = WebSettings.LOAD_NO_CACHE
            saveFormData = false
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        // The bridge answers only our own page's origin.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(ORIGIN)) { _, message, origin, isMainFrame, _ ->
                if (isMainFrame && origin.toString().trimEnd('/') == ORIGIN) message.data?.let(::receive)
            }
        } else {
            view.addJavascriptInterface(EditorBridge { json -> main.post { receive(json) } }, BRIDGE)
        }
        view.webViewClient = Client()
        ready = false
        view.loadUrl(PAGE_URL)
        return view
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            val path = url.path.orEmpty()
            if (path.startsWith("/res/")) {
                return media.load(path.removePrefix("/res/"), url.getQueryParameter("thumb") == "1", request.requestHeaders["Range"] ?: request.requestHeaders["range"])
            }
            return assets.shouldInterceptRequest(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == HOST) return false
            openExternally(view.context, url)
            return true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page is gone (memory pressure): a new web view, the note reopened.
            Log.w("ivy", "editor page died, crashed=${detail.didCrash()}")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            queued.clear()
            sentKinds = ""
            _webView.value = create()
            val id = noteId
            val note = id?.let { engine.notes[it] }
            if (note != null) open(note, focus = false)
            return true
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val PAGE_URL = "$ORIGIN/assets/editor/index.html"
        private const val BRIDGE = "IvyAndroid"

        /** Links in notes go to the system browser; nothing else leaves the app (guide 8.2). */
        fun openExternally(context: Context, url: Uri) {
            val scheme = url.scheme?.lowercase() ?: return
            if (scheme !in setOf("http", "https", "mailto", "tel")) return
            val intent = Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)
            if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
            }
        }

        private fun Context.findActivity(): android.app.Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is android.app.Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}

/** For web views without `addWebMessageListener`: the same message, by name. */
class EditorBridge(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun postMessage(json: String) = onMessage(json)
}
