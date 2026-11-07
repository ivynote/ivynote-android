package app.ivynote.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * One library on this device, as the desktop and Apple clients have them
 * (`client/packages/core/src/library.ts`).
 *
 * A library is one independent collection of notes: `LOCAL` lives only on
 * this device (no account, no sync), `SERVER` syncs with one Ivynote server.
 * Each has its own database and its own sign-in, so switching never mixes
 * data. The kind is fixed at creation. A local library can still move to the
 * cloud, but only into a newly registered, empty account: its content is
 * copied into a new synced library ([LibraryMigrator]).
 */
@Serializable
data class LibraryRef(
    val id: String,
    val kind: Kind,
    val name: String,
    /** The sync target, for server libraries. */
    val server: ServerAddress? = null,
    /** The account last signed in: its username for display, its id to tell when another account signs in. */
    val account: String? = null,
    val userId: String? = null,
    val createdAt: Millis = 0,
    val openedAt: Millis = 0,
    /** A local library copied to the cloud: the synced library that holds it now. */
    val migratedTo: String? = null,
) {
    @Serializable
    enum class Kind { LOCAL, SERVER }

    val isLocal: Boolean get() = kind == Kind.LOCAL

    /** A local library that can still turn on sync. */
    val canTurnOnSync: Boolean get() = kind == Kind.LOCAL && migratedTo == null
}

/** The libraries on this device and which one is open. */
class LibraryCatalog(
    private val prefs: KeyValueStore,
    private val root: File,
    private val credentials: (String) -> CredentialStore,
    private val now: () -> Millis = ::nowMillis,
) {
    private val _libraries = MutableStateFlow<List<LibraryRef>>(emptyList())
    val libraries: StateFlow<List<LibraryRef>> = _libraries.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    /**
     * A local library whose "Turn on sync" was chosen from inside it: the
     * library list opens the sign-up for it once the library has closed.
     */
    val pendingSync = MutableStateFlow<String?>(null)

    init {
        _libraries.value = prefs.getString(LIST_KEY)?.let {
            runCatching { IvyJson.decodeFromString(ListSerializer(LibraryRef.serializer()), it) }.getOrNull()
        }.orEmpty()
        _activeId.value = prefs.getString(ACTIVE_KEY)?.takeIf { id -> _libraries.value.any { it.id == id } }
    }

    val active: LibraryRef? get() = _activeId.value?.let(::library)

    fun library(id: String): LibraryRef? = _libraries.value.firstOrNull { it.id == id }

    /** The sign-in of a server library. */
    fun credentials(id: String): CredentialStore = credentials.invoke(id)

    /** Where a library's database lives. */
    fun storeFile(id: String): File = File(root, "libraries/$id/library.sqlite")

    // Changes

    fun createLocal(name: String): LibraryRef =
        add(LibraryRef(id = newId(), kind = LibraryRef.Kind.LOCAL, name = clean(name, L10n.s.defaultLibraryName), createdAt = now(), openedAt = now()))

    fun createServer(server: ServerAddress, name: String): LibraryRef =
        add(LibraryRef(id = newId(), kind = LibraryRef.Kind.SERVER, name = clean(name, server.host), server = server, createdAt = now(), openedAt = now()))

    /**
     * Adds a synced library for an account that has already signed in, so no
     * library exists for a server nobody could sign in to. Opens it.
     */
    fun createSignedIn(server: ServerAddress, name: String, token: String, user: User): LibraryRef {
        val ref = LibraryRef(id = newId(), kind = LibraryRef.Kind.SERVER, name = clean(name, server.host), server = server,
            account = user.username, userId = user.id, createdAt = now(), openedAt = now())
        credentials(ref.id).save(StoredAccount(server, token, user))
        prefs.putString(SessionStore.LAST_SERVER_KEY, server.base)
        return add(ref)
    }

    /** Records that a local library was copied into the synced library [target]. */
    fun markMigrated(id: String, target: String) = update(id) { if (it.isLocal) it.copy(migratedTo = target) else it }

    /** Leaves an open local library to turn on sync for it from the list. */
    fun turnOnSync(id: String) {
        if (library(id)?.canTurnOnSync != true) return
        pendingSync.value = id
        open(null)
    }

    /** Opens a library; null goes back to the list. */
    fun open(id: String?) {
        if (id == null || library(id) == null) {
            _activeId.value = null
            prefs.remove(ACTIVE_KEY)
            return
        }
        _activeId.value = id
        prefs.putString(ACTIVE_KEY, id)
        update(id) { it.copy(openedAt = now()) }
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = clean(name, it.name)) }

    /** Records who signed in to a server library. */
    fun signedIn(id: String, user: User) = update(id) { it.copy(account = user.username, userId = user.id) }

    /**
     * Forgets a library and deletes everything of it on this device: database,
     * sign-in and view state. Server data is untouched.
     */
    fun remove(id: String) {
        DocumentStore.remove(storeFile(id))
        runCatching { credentials(id).clear() }
        for (key in prefs.keys()) if (key.startsWith(libPrefix(id))) prefs.remove(key)
        _libraries.value = _libraries.value.filter { it.id != id }
        save()
        if (_activeId.value == id) open(null)
    }

    // Per-library view state (guide 11.2: expansion, sort, kept on this device)

    fun libPrefix(id: String) = "ivy.lib.$id."

    fun expanded(id: String): Set<String> = prefs.getString(libPrefix(id) + "expanded")?.let {
        runCatching { IvyJson.decodeFromString(ListSerializer(String.serializer()), it).toSet() }.getOrNull()
    }.orEmpty()

    fun saveExpanded(id: String, keys: Set<String>) =
        prefs.putString(libPrefix(id) + "expanded", IvyJson.encodeToString(ListSerializer(String.serializer()), keys.sorted()))

    // Plumbing

    private fun add(ref: LibraryRef): LibraryRef {
        _libraries.value = _libraries.value + ref
        save()
        open(ref.id)
        return ref
    }

    private fun update(id: String, change: (LibraryRef) -> LibraryRef) {
        val list = _libraries.value
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        _libraries.value = list.toMutableList().also { it[i] = change(it[i]) }
        save()
    }

    private fun save() {
        prefs.putString(LIST_KEY, IvyJson.encodeToString(ListSerializer(LibraryRef.serializer()), _libraries.value))
    }

    private fun clean(name: String, fallback: String): String {
        val n = name.trim()
        return if (n.isEmpty()) fallback else n.take(60)
    }

    companion object {
        const val LIST_KEY = "ivy.libraries"
        const val ACTIVE_KEY = "ivy.library"
        fun newId(): String = newEntityId(12)
    }
}
