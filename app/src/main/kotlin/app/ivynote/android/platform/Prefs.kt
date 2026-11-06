package app.ivynote.android.platform

import android.content.Context
import app.ivynote.core.KeyValueStore

/** Device-local settings: the library list, view state, the device id. */
class SharedPrefsStore(context: Context, name: String = "ivy") : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
}
