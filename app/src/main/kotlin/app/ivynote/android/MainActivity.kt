package app.ivynote.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.ivynote.android.reminders.Reminders
import app.ivynote.android.ui.IvyTheme
import app.ivynote.android.ui.ProvideStrings
import app.ivynote.android.ui.RootScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Reminders.ensureChannel(this)
        Reminders.onScheduled = { runOnUiThread { askForNotificationsOnce() } }
        handle(intent)
        setContent {
            ProvideStrings { IvyTheme { RootScreen() } }
        }
    }

    override fun onDestroy() {
        Reminders.onScheduled = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        askForNotificationsOnce()
    }

    /** A tapped reminder opens its note (LibraryScreen picks the request up). */
    private fun handle(intent: Intent?) {
        val note = intent?.getStringExtra(EXTRA_NOTE) ?: return
        val library = intent.getStringExtra(EXTRA_LIBRARY) ?: return
        openRequest.value = OpenRequest(library, note)
        intent.removeExtra(EXTRA_NOTE)
    }

    /**
     * Android 13+ asks before an app may notify. The question comes once, the
     * first time a library has a reminder to come, not at the first start.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || Reminders.mayNotify(this)) return
        val prefs = getSharedPreferences("ivy.app", MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return
        val any = getSharedPreferences("ivy.reminders", MODE_PRIVATE).all.values.any { ((it as? String)?.length ?: 0) > 2 }
        if (!any) return
        prefs.edit().putBoolean(ASKED, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    data class OpenRequest(val library: String, val note: String)

    companion object {
        const val EXTRA_LIBRARY = "app.ivynote.library"
        const val EXTRA_NOTE = "app.ivynote.note"
        private const val ASKED = "notificationsAsked"

        /** A note to open, from a tapped reminder. */
        val openRequest = MutableStateFlow<OpenRequest?>(null)
    }
}
