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
    fun theAccountsLanguageComesFirst() {
        val s = LanguageSettings(prefs, listOf("zh-CN"))
        s.choose(LanguageChoice.Fixed(Language.ZH))
        s.accountOpened(alice, serverLanguage = "en")
        assertEquals(Language.EN, s.current.value)
        assertEquals(LanguageChoice.Fixed(Language.EN), s.choice)
        s.accountClosed(alice)
        assertEquals(Language.ZH, s.current.value)
        // No language on the account (""): this device's choice.
        s.accountOpened(bob, serverLanguage = "")
        assertEquals(Language.ZH, s.current.value)
        // A tag this app does not speak counts as none.
        s.accountClosed(bob)
        s.accountOpened(bob, serverLanguage = "fr")
        assertEquals(Language.ZH, s.current.value)
    }

    @Test
    fun choosingWhileSignedInSetsTheAccountToo() {
        val sent = mutableListOf<String>()
        val s = LanguageSettings(prefs, listOf("zh-CN"))
        s.onAccountChoice = { key, tag -> sent += "$key=$tag" }
        s.accountOpened(alice, serverLanguage = "")
        s.choose(LanguageChoice.Fixed(Language.EN))
        assertEquals(listOf("$alice=en"), sent)
        assertEquals(Language.EN, s.current.value)
        // Not sent yet (offline): it wins over the server's older value.
        assertEquals("en", s.pendingTag(alice))
        s.accountOpened(alice, serverLanguage = "zh")
        assertEquals(Language.EN, s.current.value)
        s.pushed(alice, "en")
        assertNull(s.pendingTag(alice))
        // Following the system clears the account's language.
        s.choose(LanguageChoice.System)
        assertEquals("$alice=", sent.last())
        assertEquals(Language.ZH, s.current.value)
        s.accountClosed(alice)
        assertEquals(LanguageChoice.System, s.deviceChoice)
    }

    @Test
    fun aPushedChangeDoesNotClearANewerOne() {
        val s = LanguageSettings(prefs, listOf("zh-CN"))
        s.accountOpened(alice, serverLanguage = "")
        s.choose(LanguageChoice.Fixed(Language.EN))
        s.choose(LanguageChoice.Fixed(Language.ZH))
        s.pushed(alice, "en")
        assertEquals("zh", s.pendingTag(alice))
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
