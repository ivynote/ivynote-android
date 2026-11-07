package app.ivynote.android.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import app.ivynote.android.BuildConfig
import app.ivynote.core.AppUpdates
import app.ivynote.core.KeyValueStore
import app.ivynote.core.UpdateAsset
import app.ivynote.core.UpdateInfo
import app.ivynote.core.UpdatePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Updates of the app, as on the desktop (`client/web/src/lib/platform/updates.ts`
 * in ivynote): a check shortly after start and then every few hours; the
 * dialog offers the new version → download with progress → the system
 * installer replaces the app.
 *
 * Automatic checks stay silent unless there is something new the person has
 * not skipped or postponed; a check from 关于常青笔记 always reports its result.
 * Debug builds are another app (`app.ivynote.debug`) that a release APK cannot
 * update: they check, but send the person to the website.
 */
class Updater(
    private val context: Context,
    private val scope: CoroutineScope,
    prefs: KeyValueStore,
    private val foreground: StateFlow<Boolean>,
) {
    enum class Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, INSTALLING }
    enum class Failed { CHECK, DOWNLOAD, INSTALL }

    data class State(
        val phase: Phase = Phase.IDLE,
        val open: Boolean = false,
        val info: UpdateInfo? = null,
        val downloaded: Long = 0,
        val total: Long = 0,
        val error: String = "",
        val failed: Failed? = null,
    )

    val prefs = UpdatePrefs(prefs)
    val current: String = BuildConfig.VERSION_NAME

    /** A release build can install the release APK over itself. */
    val canInstallHere: Boolean = !BuildConfig.DEBUG

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Saves the open library before the app is replaced; set by the screen that holds it. */
    @Volatile var beforeInstall: (suspend () -> Unit)? = null

    private var download: Job? = null
    private var started = false

    private fun set(f: State.() -> State) = _state.update(f)

    /** Starts the automatic checks (once per process). */
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { cleanup() }
        scope.launch {
            delay(FIRST_CHECK_MS)
            check(AppUpdates.Reason.STARTUP)
            while (true) {
                delay(CHECK_EVERY_MS)
                check(AppUpdates.Reason.PERIODIC)
            }
        }
    }

    fun checkNow() {
        scope.launch { check(AppUpdates.Reason.MANUAL) }
    }

    /** Asks for a newer version. Manual: always shows the outcome; otherwise only a new, not muted version opens the dialog. */
    suspend fun check(reason: AppUpdates.Reason): UpdateInfo? {
        val manual = reason == AppUpdates.Reason.MANUAL
        val s = _state.value
        // Never interrupt a download or an install in progress.
        if (s.phase == Phase.CHECKING || s.phase == Phase.DOWNLOADING || s.phase == Phase.INSTALLING) {
            if (manual) set { copy(open = info?.available == true) }
            return s.info
        }
        set { copy(phase = Phase.CHECKING, error = "", failed = null) }
        return try {
            val info = AppUpdates.check(current, reason, prefs.installId(), arch())
            if (!info.available) {
                set { copy(phase = Phase.IDLE, info = info) }
                if (manual) toast("已是最新版本（$current）")
                return info
            }
            val ready = withContext(Dispatchers.IO) { info.asset?.let { a -> fileFor(info, a)?.let { AppUpdates.verified(it, a) } } == true }
            val size = info.asset?.size ?: 0
            val open = manual || !prefs.muted(info.latest) || _state.value.open
            set { copy(phase = if (ready) Phase.READY else Phase.AVAILABLE, info = info, open = open, downloaded = if (ready) size else 0, total = size) }
            info
        } catch (e: CancellationException) {
            set { copy(phase = Phase.IDLE) }
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            set { copy(phase = Phase.IDLE, error = msg, failed = Failed.CHECK) }
            if (manual) toast("检查更新失败：$msg", long = true)
            null
        }
    }

    /** The person confirmed: download (with progress), verify, then install. */
    fun downloadAndInstall() {
        val s = _state.value
        val info = s.info ?: return
        val asset = info.asset
        if (asset == null || !canInstallHere) {
            openDownloadPage()
            return
        }
        if (s.phase == Phase.READY) {
            install()
            return
        }
        if (s.phase == Phase.DOWNLOADING) return
        val dest = fileFor(info, asset) ?: return
        set { copy(phase = Phase.DOWNLOADING, error = "", failed = null, downloaded = 0, total = asset.size) }
        download = scope.launch {
            try {
                AppUpdates.download(asset, info.site, dest) { d, t ->
                    _state.update { if (it.phase == Phase.DOWNLOADING) it.copy(downloaded = d, total = if (t > 0) t else it.total) else it }
                }
                set { copy(phase = Phase.READY, downloaded = total) }
                // In the foreground: straight on to the installer. Otherwise the dialog waits.
                if (foreground.value) install() else set { copy(open = true) }
            } catch (e: CancellationException) {
                set { copy(phase = Phase.AVAILABLE, downloaded = 0) }
                throw e
            } catch (e: Exception) {
                set { copy(phase = Phase.AVAILABLE, error = e.message ?: e.javaClass.simpleName, failed = Failed.DOWNLOAD) }
            }
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    /** Saves the open library, then hands the APK to the system installer. */
    fun install() {
        val s = _state.value
        val info = s.info ?: return
        val asset = info.asset ?: return
        if (s.phase != Phase.READY) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Android asks once per installing app: 设置 → 安装未知应用 → 常青笔记.
            toast("请允许「常青笔记」安装应用，然后返回点击「立即安装」", long = true)
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val file = fileFor(info, asset) ?: return
        set { copy(phase = Phase.INSTALLING, error = "", failed = null) }
        scope.launch {
            beforeInstall?.let { save -> withTimeoutOrNull(10_000) { runCatching { save() } } }
            try {
                if (!withContext(Dispatchers.IO) { AppUpdates.verified(file, asset) }) error("下载的安装包已失效，请重新下载")
                ApkInstaller.install(context, file)
                // The installer answers through InstallResultReceiver.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val gone = !file.exists()
                set { copy(phase = if (gone) Phase.AVAILABLE else Phase.READY, error = e.message ?: e.javaClass.simpleName, failed = Failed.INSTALL) }
            }
        }
    }

    /** The system installer's outcome (success ends this process, so it is rarely seen). */
    fun installFinished(status: Int, message: String?) {
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> set { copy(phase = Phase.IDLE, open = false) }
            // The person declined in the system dialog: back to "ready", no error.
            PackageInstaller.STATUS_FAILURE_ABORTED -> set { copy(phase = Phase.READY) }
            else -> {
                val why = when (status) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "新版本与已安装的应用签名不一致，请前往官网下载安装"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "存储空间不足"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "安装被系统阻止"
                    else -> message?.takeIf { it.isNotBlank() } ?: "安装未完成"
                }
                set { copy(phase = Phase.READY, open = true, error = why, failed = Failed.INSTALL) }
            }
        }
    }

    /** Closes the dialog; an automatic check asks again about this version tomorrow. */
    fun later() {
        val s = _state.value
        if (s.info != null && s.phase != Phase.DOWNLOADING) prefs.snooze(s.info.latest)
        set { copy(open = false) }
    }

    /** Automatic checks stay quiet about this version; 关于常青笔记 can still install it. */
    fun skip() {
        _state.value.info?.let { prefs.skip(it.latest) }
        set { copy(open = false) }
    }

    /** Hides the dialog while a download goes on. */
    fun hide() = set { copy(open = false) }

    fun show() = set { copy(open = info?.available == true) }

    /** The website's download page: no APK on offer, a debug build, or as a fallback. */
    fun openDownloadPage() {
        val site = _state.value.info?.site ?: AppUpdates.SITES.first()
        val lang = if (Locale.getDefault().language == "zh") "zh" else "en"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$site/$lang/download#mobile")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun dir() = File(context.cacheDir, "updates")

    /** Where the APK of a version is kept; null for a version or name that is not a plain file name. */
    private fun fileFor(info: UpdateInfo, asset: UpdateAsset): File? {
        if (!AppUpdates.safeName(info.latest) || !AppUpdates.safeName(asset.name)) return null
        return File(File(dir(), info.latest), asset.name)
    }

    /** Removes downloads of versions this app already is (or is newer than). */
    private fun cleanup() {
        dir().listFiles()?.forEach { f ->
            if (!f.isDirectory || !AppUpdates.isNewer(f.name, current)) f.deleteRecursively()
        }
    }

    private fun toast(text: String, long: Boolean = false) {
        scope.launch(Dispatchers.Main) {
            Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val FIRST_CHECK_MS = 15_000L
        private const val CHECK_EVERY_MS = 6 * 3600_000L

        /** The CPU the website counts installs by. */
        fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x64"
            else -> ""
        }
    }
}
