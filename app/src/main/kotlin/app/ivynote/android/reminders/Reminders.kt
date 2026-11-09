package app.ivynote.android.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.ivynote.android.MainActivity
import app.ivynote.android.R
import app.ivynote.core.IvyJson
import app.ivynote.core.L10n
import app.ivynote.core.Note
import app.ivynote.core.Tasks
import app.ivynote.core.nowMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalTime

/**
 * Reminders for tasks with a 📅 date (guide 9.4, 11.8). Whenever a library's
 * notes change, the next reminders of that library are handed to the
 * system's AlarmManager, so they fire while the app is closed. They are also
 * kept in preferences, to be set again after a reboot or an app update.
 * Protected notes take no part: their tasks are encrypted.
 */
object Reminders {
    const val CHANNEL = "reminders"
    private const val PREFS = "ivy.reminders"
    private const val ACTION = "app.ivynote.REMINDER"
    /** How far ahead alarms are set; changes reschedule anyway. */
    private const val HORIZON_MS = 8L * 24 * 3600 * 1000
    private const val MAX = 48
    /** A date without a time reminds at this hour (as the web's default). */
    val ALL_DAY: LocalTime = LocalTime.of(9, 0)

    @Serializable
    data class Pending(val key: String, val library: String, val noteId: String, val title: String, val text: String, val at: Long)

    /** Called after alarms were set for at least one reminder (any thread). */
    @Volatile var onScheduled: (() -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context, library: String): List<Pending> =
        prefs(context).getString(library, null)?.let { runCatching { IvyJson.decodeFromString(ListSerializer(Pending.serializer()), it) }.getOrNull() } ?: emptyList()

    private fun save(context: Context, library: String, list: List<Pending>) {
        prefs(context).edit().putString(library, IvyJson.encodeToString(ListSerializer(Pending.serializer()), list)).apply()
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, L10n.s.reminderChannel, NotificationManager.IMPORTANCE_HIGH)
        ch.description = L10n.s.reminderChannelHint
        nm.createNotificationChannel(ch)
    }

    private fun intent(context: Context, p: Pending): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ACTION)
            .putExtra("key", p.key).putExtra("library", p.library).putExtra("note", p.noteId)
            .putExtra("title", p.title).putExtra("text", p.text)
        return PendingIntent.getBroadcast(context, p.key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun set(context: Context, p: Pending) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context, p)
        // Exact when the system allows it; otherwise within a few minutes.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, p.at, pi)
        }
    }

    private fun cancel(context: Context, p: Pending) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(intent(context, p))
    }

    /** Sets the alarms of a library's next reminders, replacing the earlier ones. */
    fun schedule(context: Context, library: String, notes: Collection<Note>) {
        val now = nowMillis()
        val next = Tasks.reminders(notes, now, now + HORIZON_MS, ALL_DAY).take(MAX).map {
            Pending(it.key, library, it.noteId, it.noteTitle, it.text, it.at)
        }
        val old = load(context, library)
        val keep = next.map { it.key }.toSet()
        for (p in old) if (p.key !in keep) cancel(context, p)
        for (p in next) set(context, p)
        save(context, library, next)
        if (next.isNotEmpty()) onScheduled?.invoke()
    }

    /** Sets every saved alarm again (after a reboot or an update). */
    fun restore(context: Context) {
        val now = nowMillis()
        for (library in prefs(context).all.keys) {
            val list = load(context, library).filter { it.at > now - 60_000 }
            list.forEach { set(context, it) }
            save(context, library, list)
        }
    }

    /** Stops the reminders of a library (signed out, removed). */
    fun clear(context: Context, library: String) {
        load(context, library).forEach { cancel(context, it) }
        prefs(context).edit().remove(library).apply()
    }

    fun mayNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether the library has reminders to come (to ask for permission). */
    fun hasPending(context: Context, library: String): Boolean = load(context, library).isNotEmpty()

    internal fun show(context: Context, i: Intent) {
        if (!mayNotify(context)) return
        ensureChannel(context)
        val title = i.getStringExtra("text").orEmpty().ifEmpty { i.getStringExtra("title").orEmpty() }
        val noteTitle = i.getStringExtra("title").orEmpty()
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_LIBRARY, i.getStringExtra("library"))
            .putExtra(MainActivity.EXTRA_NOTE, i.getStringExtra("note"))
        val key = i.getStringExtra("key").orEmpty()
        val tap = PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ivy_leaf)
            .setContentTitle(title)
            .setContentText(if (noteTitle.isNotEmpty()) L10n.s.reminderFrom(noteTitle) else null)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // Checked here, where the call is: the permission can be withdrawn at any time.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
            // Withdrawn between the check and the call: nothing to show.
        }
    }
}

/** Fires a reminder; after a reboot or an update, sets the alarms again. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Reminders.restore(context)
            else -> Reminders.show(context, intent)
        }
    }
}
