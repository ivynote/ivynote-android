package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who is signed in to a server library, and whether the server is reachable.
 * Each server library has its own, over its own credential item.
 *
 * Follows the guide's session rules (3.1, 6.10): the cached account opens the
 * library immediately; `GET /me` refreshes it in the background; only 401/403
 * end the session, while network errors and 5xx just mark it offline.
 */
class SessionStore(
    private val store: CredentialStore,
    private val prefs: KeyValueStore,
    private val transport: HttpTransport = OkHttpTransport(),
) {
    sealed interface State {
        data object Loading : State
        data object SignedOut : State
        data class SignedIn(val account: StoredAccount) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The last `/me` check could not reach the server. */
    private val _offline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _offline.asStateFlow()

    /** Shown on the sign-in screen after the server ended the session. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val account: StoredAccount? get() = (_state.value as? State.SignedIn)?.account

    fun client(server: ServerAddress) = ApiClient(server, null, transport)

    /** The API client for the signed-in account. */
    val api: ApiClient? get() = account?.let { ApiClient(it.server, it.token, transport) }

    suspend fun restore() {
        val saved = try { store.load() } catch (_: Exception) { null }
        if (saved == null) {
            _state.value = State.SignedOut
            return
        }
        _state.value = State.SignedIn(saved)
        refresh()
    }

    /** Re-checks the session with `GET /me`. */
    suspend fun refresh() {
        val current = account ?: return
        val api = api ?: return
        try {
            val user = api.me()
            _offline.value = false
            if (account?.token != current.token) return // signed out meanwhile
            val updated = current.copy(user = user)
            if (updated != current) {
                runCatching { store.save(updated) }
                _state.value = State.SignedIn(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.endsSession) expire(e) else _offline.value = true
        } catch (e: Exception) {
            _offline.value = true
        }
    }

    fun signIn(server: ServerAddress, token: String, user: User) {
        val account = StoredAccount(server, token, user)
        store.save(account)
        prefs.putString(LAST_SERVER_KEY, server.base)
        _notice.value = null
        _offline.value = false
        _state.value = State.SignedIn(account)
    }

    /**
     * Saves the account's interface language on the server (`PATCH /me`).
     * False when it could not be sent (offline): the caller tries again later.
     */
    suspend fun pushLanguage(tag: String): Boolean {
        val current = account ?: return false
        val api = api ?: return false
        return try {
            val user = api.setLanguage(tag)
            if (account?.token == current.token) {
                val updated = current.copy(user = user)
                runCatching { store.save(updated) }
                _state.value = State.SignedIn(updated)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            if (e.endsSession) expire(e)
            // A refused tag will not succeed later either.
            !e.isTransient
        } catch (e: Exception) {
            false
        }
    }

    /** Revokes this device's token (best effort) and forgets the account. */
    suspend fun signOut() {
        api?.let { runCatching { it.logout() } }
        runCatching { store.clear() }
        _offline.value = false
        _state.value = State.SignedOut
    }

    /** The server refused the token outside [refresh], e.g. during sync. */
    fun sessionRejected() {
        if (account == null) return
        expire(ApiError.Server(401, ""))
    }

    private fun expire(error: ApiError) {
        // The token is no longer valid. Local notes and the outbox are kept
        // (guide 5.1): signing in again resumes syncing them.
        runCatching { store.clear() }
        _notice.value = if (error.status == 403) error.message ?: L10n.s.accountUnavailable else L10n.s.sessionExpired
        _offline.value = false
        _state.value = State.SignedOut
    }

    companion object {
        const val LAST_SERVER_KEY = "ivy.lastServer"
    }
}
