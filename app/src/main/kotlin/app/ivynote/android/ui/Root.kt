package app.ivynote.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ivynote.android.IvyApp
import app.ivynote.android.LibrarySession
import app.ivynote.core.DocumentStore
import app.ivynote.core.LanguageSettings
import app.ivynote.core.LibraryRef
import app.ivynote.core.SessionStore
import app.ivynote.core.StoredAccount
import kotlinx.coroutines.launch

/** The open library, or the list of libraries when none is open. */
@Composable
fun RootScreen() {
    val app = IvyApp.instance
    val activeId by app.catalog.activeId.collectAsState()
    val libraries by app.catalog.libraries.collectAsState()
    val ref = libraries.firstOrNull { it.id == activeId }
    Box(Modifier.fillMaxSize().background(Ivy.colors.canvas)) {
        Crossfade(targetState = ref?.id, label = "library") { id ->
            val current = libraries.firstOrNull { it.id == id }
            if (current != null) {
                // Another library is another world: fresh state for it.
                key(current.id) { LibraryRoot(current) }
            } else {
                LibrariesScreen()
            }
        }
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Ivy.colors.muted)
    }
}

/**
 * One library: a local one opens at once; a server one restores its sign-in
 * first and asks for it when there is none.
 */
@Composable
fun LibraryRoot(ref: LibraryRef) {
    val app = IvyApp.instance
    if (ref.isLocal) {
        OpenLibrary(ref, account = null, session = null)
        return
    }
    val session = remember(ref.id) { SessionStore(app.catalog.credentials(ref.id), app.prefs) }
    LaunchedEffect(session) { session.restore() }
    when (val state = session.state.collectAsState().value) {
        SessionStore.State.Loading -> Loading()
        SessionStore.State.SignedOut -> SignInScreen(ref, session)
        // Another account on the same server: its own engine.
        is SessionStore.State.SignedIn -> key(state.account.user.id) { OpenLibrary(ref, state.account, session) }
    }
}

/** Sends a language change made on this device (maybe offline) to the account. */
private suspend fun pushLanguage(session: SessionStore, key: String) {
    val language = IvyApp.instance.language
    val tag = language.pendingTag(key) ?: return
    if (session.pushLanguage(tag)) language.pushed(key, tag)
}

/** Opens a library's database and drives it with the app's foreground and background. */
@Composable
fun OpenLibrary(ref: LibraryRef, account: StoredAccount?, session: SessionStore?) {
    val app = IvyApp.instance
    val context = LocalContext.current
    var library by remember { mutableStateOf<LibrarySession?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    // Signed in: the account's language comes first (LanguageSettings), the
    // server's value as `/me` last returned it.
    if (account != null && session != null) {
        val key = LanguageSettings.accountKey(account.server, account.user.id)
        val scope = rememberCoroutineScope()
        DisposableEffect(key) {
            val send: (String, String) -> Unit = { k, _ -> if (k == key) scope.launch { pushLanguage(session, key) } }
            app.language.onAccountChoice = send
            scope.launch { pushLanguage(session, key) }
            onDispose {
                // Libraries cross-fade: the next one may have set its own already.
                if (app.language.onAccountChoice === send) app.language.onAccountChoice = null
                app.language.accountClosed(key)
            }
        }
        LaunchedEffect(key, account.user.language) { app.language.accountOpened(key, account.user.language) }
    }

    LaunchedEffect(ref.id, account?.user?.id) {
        val file = app.catalog.storeFile(ref.id)
        if (account != null) {
            // Notes of another account on this server must not mix in.
            val previous = ref.userId
            if (previous != null && previous != account.user.id) DocumentStore.remove(file)
            app.catalog.signedIn(ref.id, account.user)
        }
        try {
            val lib = LibrarySession.open(context, ref, account, file, app.deviceId)
            lib.engine.onUnauthorized = { session?.sessionRejected() }
            library = lib
        } catch (e: Exception) {
            openError = e.message ?: e.javaClass.simpleName
        }
    }

    val lib = library
    if (lib != null) {
        // Foreground: sync and listen; background: save, and push what was just written (guide 6.8).
        LaunchedEffect(lib) {
            app.foreground.collect { fg ->
                if (fg) {
                    lib.activate()
                    session?.let { s ->
                        launch {
                            s.refresh()
                            // Back online: a language change made offline goes out now.
                            account?.let { a -> pushLanguage(s, LanguageSettings.accountKey(a.server, a.user.id)) }
                        }
                    }
                } else {
                    lib.deactivate()
                    if (!ref.isLocal) app.appScope.launch { lib.flush() }
                }
            }
        }
        // Switching libraries or signing out: save now, push what is pending, then stop.
        DisposableEffect(lib) {
            onDispose {
                lib.deactivate()
                app.appScope.launch {
                    lib.flush()
                    lib.close()
                }
            }
        }
        LibraryScreen(lib, session)
    } else if (openError != null) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val s = LocalStrings.current
            Text(s.cannotOpenData, style = SerifTitle)
            Text(openError ?: "", color = Ivy.colors.muted, textAlign = TextAlign.Center)
            InkButton(s.backToLibraries, onClick = { app.catalog.open(null) })
        }
    } else {
        Loading()
    }
}
