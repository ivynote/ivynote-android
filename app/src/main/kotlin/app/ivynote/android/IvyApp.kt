package app.ivynote.android

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.ivynote.android.platform.KeystoreCredentialStore
import app.ivynote.android.platform.SharedPrefsStore
import app.ivynote.core.IvyHttp
import app.ivynote.core.Language
import app.ivynote.core.LanguageSettings
import app.ivynote.core.LibraryCatalog
import app.ivynote.core.OfficialRegions
import app.ivynote.core.newEntityId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Holds what outlives screens: the library catalog and the open library
 * (its engine and editor), driven by the app's foreground and background.
 */
class IvyApp : Application() {
    lateinit var prefs: SharedPrefsStore
        private set
    lateinit var catalog: LibraryCatalog
        private set
    lateinit var language: LanguageSettings
        private set

    /** The system's locale (or this app's, set in the system settings), before ours replaces the default. */
    var systemLocale: Locale = Locale.getDefault()
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
        systemLocale = systemLocales(resources.configuration).firstOrNull() ?: Locale.getDefault()
        language = LanguageSettings(prefs, systemLocales(resources.configuration).map { it.toLanguageTag() })
        // TODO(server): once ivynote stores the account's language, send the
        // choice here (PATCH /me) and pass `/me`'s value to accountOpened.
        language.onAccountChoice = null
        applyLocale(language.current.value)
        appScope.launch { language.current.collect { applyLocale(it) } }
        // Official node addresses: the website's last answer, else the built-in ones.
        OfficialRegions.install(prefs)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system language changed: the framework has reset the default locale.
        systemLocale = systemLocales(newConfig).firstOrNull() ?: systemLocale
        language.systemChanged(systemLocales(newConfig).map { it.toLanguageTag() })
        applyLocale(language.current.value)
    }

    /**
     * Dates, relative times and number formats follow the interface
     * language. The region stays the system's (it picks the sync node).
     */
    private fun applyLocale(lang: Language) {
        // The system's own English (en-GB dates); Chinese is always the simplified script.
        val base = if (lang == Language.EN && systemLocale.language == "en") systemLocale else null
        val locale = base ?: Locale.Builder().setLanguage(lang.tag).setRegion(systemLocale.country.takeIf { it.length == 2 } ?: "")
            .apply { if (lang == Language.ZH) setScript("Hans") }.build()
        Locale.setDefault(locale)
    }

    private fun systemLocales(config: Configuration): List<Locale> {
        val list = config.locales
        return (0 until list.size()).map { list[it] }
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

    /**
     * Files that must not be evicted: uploads waiting for the network and a
     * local library's attachments. Next to the database, so removing the
     * library or signing out removes them too (guide 8.2).
     */
    fun filesDir(libraryId: String): File = File(catalog.storeFile(libraryId).parentFile, "files")

    /** Scratch space for files on their way in (camera, picked files) or out (shared). */
    fun transferDir(): File = File(cacheDir, "transfer").apply { mkdirs() }

    companion object {
        private const val DEVICE_KEY = "ivy.device"
        lateinit var instance: IvyApp
            private set
    }
}
