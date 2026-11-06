package app.ivynote.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.ivynote.android.platform.KeystoreCredentialStore
import app.ivynote.android.platform.SharedPrefsStore
import app.ivynote.core.IvyHttp
import app.ivynote.core.LibraryCatalog
import app.ivynote.core.newEntityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Holds what outlives screens: the library catalog and the open library
 * (its engine and editor), driven by the app's foreground and background.
 */
class IvyApp : Application() {
    lateinit var prefs: SharedPrefsStore
        private set
    lateinit var catalog: LibraryCatalog
        private set

    /** For work that must finish after a screen is gone (pushing before sign-out). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        IvyHttp.userAgent = "Ivynote-Android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"
        prefs = SharedPrefsStore(this)
        catalog = LibraryCatalog(
            prefs = prefs,
            root = File(filesDir, "ivynote"),
            credentials = { id -> KeystoreCredentialStore(this, "library.$id") },
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { _foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { _foreground.value = false }
        })
    }

    /** This install's HLC node and `device` name, e.g. `android-a1b2c3d4` (guide 6.2). */
    val deviceId: String
        get() {
            prefs.getString(DEVICE_KEY)?.let { return it }
            val id = "android-" + newEntityId(8)
            prefs.putString(DEVICE_KEY, id)
            return id
        }

    /** Attachments fetched for the editor, per library (guide 7.3). */
    fun mediaDir(libraryId: String) = File(cacheDir, "media/$libraryId")

    companion object {
        private const val DEVICE_KEY = "ivy.device"
        lateinit var instance: IvyApp
            private set
    }
}
