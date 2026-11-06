package app.ivynote.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The languages of the interface: Chinese first, English second, as on the
 * web (`client/packages/core/src/i18n.ts`). [tag] is the value the web keeps
 * in `ivy.lang` and the one the server is expected to store for an account.
 */
enum class Language(val tag: String) {
    ZH("zh"),
    EN("en");

    val strings: Strings get() = when (this) {
        ZH -> ZhStrings
        EN -> EnStrings
    }

    companion object {
        /** `zh`, `zh-Hans-CN`, `zh_TW`, `en-GB`… to a supported language; null for any other. */
        fun fromTag(tag: String?): Language? {
            val primary = tag?.trim()?.lowercase()?.split('-', '_')?.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.tag == primary }
        }

        /**
         * The first supported language in the system's preference list
         * (most preferred first). English when none is, as the world's
         * common second language; Chinese users always have a `zh` entry.
         */
        fun fromSystem(tags: List<String>): Language = tags.firstNotNullOfOrNull(::fromTag) ?: EN
    }
}

/** A language choice: follow the system, or one language. */
sealed interface LanguageChoice {
    data object System : LanguageChoice
    data class Fixed(val language: Language) : LanguageChoice

    /** `system`, `zh`, `en`: how it is stored, and sent to the server. */
    val tag: String get() = when (this) {
        System -> SYSTEM_TAG
        is Fixed -> language.tag
    }

    companion object {
        const val SYSTEM_TAG = "system"

        fun fromTag(tag: String?): LanguageChoice? = when {
            tag == null -> null
            tag == SYSTEM_TAG -> System
            else -> Language.fromTag(tag)?.let(::Fixed)
        }

        val all: List<LanguageChoice> = listOf(System) + Language.entries.map(::Fixed)
    }
}

/** The strings of the current language, for code without a UI context (errors, shelf labels). */
object L10n {
    @Volatile
    var language: Language = Language.ZH

    val s: Strings get() = language.strings
}

/**
 * Which language the interface speaks, in this order (guide §界面语言):
 *
 * 1. the signed-in account's language (`User.language`, kept by the server,
 *    the same on every device), when a synced library is open and it is set;
 * 2. the choice made on this device, remembered across launches and sign-outs;
 * 3. the system's language (the first supported one in its list, else English).
 *
 * Choosing in settings sets this device's choice and, when an account is
 * open, the account's (`PATCH /me {language}`; "follow the system" clears
 * it). A change made offline is kept as pending and sent once the server can
 * be reached ([pendingTag], [pushed]). A new account is registered with the
 * language in use at that moment.
 */
class LanguageSettings(
    private val prefs: KeyValueStore,
    systemTags: List<String>,
) {
    private var systemTags: List<String> = systemTags

    /** The open account: its key and its language, if it has one. */
    private var account: String? = null
    private var accountLanguage: Language? = null

    /** Called when the user changes the open account's language: send [tag] to the server. */
    var onAccountChoice: ((accountKey: String, tag: String) -> Unit)? = null

    private val _current = MutableStateFlow(resolve())
    /** The language in use. */
    val current: StateFlow<Language> = _current.asStateFlow()

    init {
        L10n.language = _current.value
    }

    /** This device's choice; [LanguageChoice.System] until the user picks one. */
    val deviceChoice: LanguageChoice
        get() = LanguageChoice.fromTag(prefs.getString(DEVICE_KEY)) ?: LanguageChoice.System

    /** What the settings page shows as selected. */
    val choice: LanguageChoice
        get() = accountLanguage?.let(LanguageChoice::Fixed) ?: deviceChoice

    private val _selected = MutableStateFlow(choice)
    /** [choice], observable. */
    val selected: StateFlow<LanguageChoice> = _selected.asStateFlow()

    /** The system's own language, for "follow the system". */
    val systemLanguage: Language get() = Language.fromSystem(systemTags)

    /** Whether an account is open, so a choice is saved to it too. */
    val hasAccount: Boolean get() = account != null

    /** The system language changed (or the app's per-app language in system settings). */
    fun systemChanged(tags: List<String>) {
        systemTags = tags
        update()
    }

    /** The user picked a language in settings. */
    fun choose(choice: LanguageChoice) {
        if (choice == LanguageChoice.System) prefs.remove(DEVICE_KEY) else prefs.putString(DEVICE_KEY, choice.tag)
        account?.let { key ->
            // "Follow the system" clears the account's language.
            val tag = (choice as? LanguageChoice.Fixed)?.language?.tag ?: ""
            accountLanguage = (choice as? LanguageChoice.Fixed)?.language
            prefs.putString(PENDING_KEY_PREFIX + key, tag)
            onAccountChoice?.invoke(key, tag)
        }
        update()
    }

    /**
     * A synced library is open with a signed-in account, or its account was
     * refreshed. [serverLanguage] is `User.language` ("" when not set). A
     * change not sent yet wins over it.
     */
    fun accountOpened(accountKey: String, serverLanguage: String) {
        account = accountKey
        val tag = pendingTag(accountKey) ?: serverLanguage
        accountLanguage = Language.fromTag(tag)
        update()
    }

    /** The account's library closed (switched, signed out): back to this device's choice. */
    fun accountClosed(accountKey: String) {
        if (account != accountKey) return
        account = null
        accountLanguage = null
        update()
    }

    /** A language change for the account not yet saved on the server ("" clears it), if any. */
    fun pendingTag(accountKey: String): String? = prefs.getString(PENDING_KEY_PREFIX + accountKey)

    /** The server took [tag]; a newer pending change stays. */
    fun pushed(accountKey: String, tag: String) {
        if (pendingTag(accountKey) == tag) prefs.remove(PENDING_KEY_PREFIX + accountKey)
    }

    private fun resolve(): Language = accountLanguage ?: when (val c = deviceChoice) {
        LanguageChoice.System -> Language.fromSystem(systemTags)
        is LanguageChoice.Fixed -> c.language
    }

    private fun update() {
        val language = resolve()
        L10n.language = language
        _selected.value = choice
        _current.value = language
    }

    companion object {
        const val DEVICE_KEY = "ivy.language"
        const val PENDING_KEY_PREFIX = "ivy.language.pending."

        /** An account across devices: its server and user id. */
        fun accountKey(server: ServerAddress, userId: String): String = "${server.base}|$userId"
    }
}
