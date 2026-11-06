package app.ivynote.core

/** Where a server library keeps its sign-in. On Android: encrypted with a Keystore key. */
interface CredentialStore {
    fun load(): StoredAccount?
    fun save(account: StoredAccount)
    fun clear()
}

class InMemoryCredentialStore(initial: StoredAccount? = null) : CredentialStore {
    @Volatile private var value: StoredAccount? = initial
    override fun load() = value
    override fun save(account: StoredAccount) { value = account }
    override fun clear() { value = null }
}

/** Small device-local settings: shared preferences on Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class MemoryKeyValueStore : KeyValueStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}
