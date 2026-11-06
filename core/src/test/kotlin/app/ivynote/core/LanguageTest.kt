package app.ivynote.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanguageTest {
    private val prefs = MemoryKeyValueStore()
    private val alice = "https://web.ivynote.app|u1"
    private val bob = "https://web.ivynote.app|u2"

    @AfterTest
    fun reset() {
        L10n.language = Language.ZH
    }

    @Test
    fun systemTags() {
        assertEquals(Language.ZH, Language.fromTag("zh-Hans-CN"))
        assertEquals(Language.ZH, Language.fromTag("zh_TW"))
        assertEquals(Language.EN, Language.fromTag("en-GB"))
        assertNull(Language.fromTag("ja-JP"))
        assertNull(Language.fromTag(""))
        // The first supported language in the list wins; none falls back to English.
        assertEquals(Language.ZH, Language.fromSystem(listOf("ja-JP", "zh-CN", "en-US")))
        assertEquals(Language.EN, Language.fromSystem(listOf("en-US", "zh-CN")))
        assertEquals(Language.EN, Language.fromSystem(listOf("fr-FR")))
        assertEquals(Language.EN, Language.fromSystem(emptyList()))
    }

    @Test
    fun followsTheSystemUntilChosen() {
        val s = LanguageSettings(prefs, listOf("en-US"))
        assertEquals(Language.EN, s.current.value)
        assertEquals(Language.EN, L10n.language)
        assertEquals(LanguageChoice.System, s.choice)
        s.systemChanged(listOf("zh-CN"))
        assertEquals(Language.ZH, s.current.value)
    }

    @Test
    fun aManualChoiceIsRemembered() {
        LanguageSettings(prefs, listOf("en-US")).choose(LanguageChoice.Fixed(Language.ZH))
        val again = LanguageSettings(prefs, listOf("en-US"))
        assertEquals(Language.ZH, again.current.value)
        assertEquals(LanguageChoice.Fixed(Language.ZH), again.choice)
        // Back to following the system.
        again.choose(LanguageChoice.System)
        assertEquals(Language.EN, LanguageSettings(prefs, listOf("en-US")).current.value)
    }

    @Test
    fun theAccountsChoiceComesFirst() {
        val s = LanguageSettings(prefs, listOf("zh-CN"))
        s.choose(LanguageChoice.Fixed(Language.ZH))
        // From the server (once it stores the language).
        s.accountOpened(alice, serverChoice = LanguageChoice.Fixed(Language.EN))
        assertEquals(Language.EN, s.current.value)
        s.accountClosed(alice)
        assertEquals(Language.ZH, s.current.value)
        // Kept on this device for the account until the server says otherwise.
        s.accountOpened(alice)
        assertEquals(Language.EN, s.current.value)
        // An account with no choice: this device's choice.
        s.accountClosed(alice)
        s.accountOpened(bob)
        assertEquals(Language.ZH, s.current.value)
    }

    @Test
    fun choosingWhileSignedInSetsTheAccountToo() {
        val sent = mutableListOf<Pair<String, LanguageChoice>>()
        val s = LanguageSettings(prefs, listOf("zh-CN"))
        s.onAccountChoice = { key, choice -> sent += key to choice }
        s.accountOpened(alice)
        s.choose(LanguageChoice.Fixed(Language.EN))
        assertEquals(listOf<Pair<String, LanguageChoice>>(alice to LanguageChoice.Fixed(Language.EN)), sent)
        assertEquals(Language.EN, s.current.value)
        s.accountClosed(alice)
        // The device remembers it as well.
        assertEquals(Language.EN, s.current.value)
        assertEquals(LanguageChoice.Fixed(Language.EN), s.deviceChoice)
    }

    @Test
    fun aNewAccountStartsWithTheLanguageInUse() {
        val s = LanguageSettings(prefs, listOf("en-US"))
        assertEquals(Language.EN, s.registered(alice))
        // The system changes later; the account keeps its language.
        s.systemChanged(listOf("zh-CN"))
        s.accountOpened(alice)
        assertEquals(Language.EN, s.current.value)
        assertEquals(LanguageChoice.Fixed(Language.EN), s.choice)
    }

    @Test
    fun coreMessagesFollowTheLanguage() {
        val s = LanguageSettings(prefs, listOf("en-US"))
        assertEquals("Wrong username or password", L10n.s.wrongCredentials)
        assertEquals("Global", SyncRegion.GLOBAL.title)
        assertEquals("Enter the server address", ServerAddress.ParseError.Empty.message)
        assertEquals("Request failed (418)", ApiError.Server(418, "").message)
        s.choose(LanguageChoice.Fixed(Language.ZH))
        assertEquals("全球", SyncRegion.GLOBAL.title)
        assertEquals("请填写服务器地址", ServerAddress.ParseError.Empty.message)
        assertEquals("1 change to sync", EnStrings.pending(1))
        assertEquals("2 changes to sync", EnStrings.pending(2))
    }
}
