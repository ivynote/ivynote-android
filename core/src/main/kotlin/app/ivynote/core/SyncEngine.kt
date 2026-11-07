package app.ivynote.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/** Where the engine stands, for the status line (guide 3.5). */
data class SyncState(
    val status: Status = Status.IDLE,
    /** Local changes waiting to be pushed (not counting refused ones). */
    val pending: Int = 0,
    /** Changes the server refused, kept on this device. */
    val blocked: Int = 0,
    val lastSyncAt: Millis = 0,
    /** The first sync of this library: nothing downloaded yet. */
    val initial: Boolean = false,
    val error: String? = null,
) {
    enum class Status { IDLE, SYNCING, OFFLINE, ERROR }
}

/** The server kept a pushed body as a separate note. */
data class ConflictNotice(val noteId: String, val copyId: String)

/** A queued file the server refused (too large, a type it does not take). */
data class UploadFailure(val name: String, val message: String)

/** What [SyncEngine.addFile] put in the library: the id to reference and how to embed it. */
data class AddedFile(val id: String, val name: String, val kind: String, val queued: Boolean, val duplicate: Boolean = false) {
    val markdown: String get() = Links.embed(id, name, kind)
}

/**
 * Local-first sync engine: a port of the web client's
 * (`ivynote/client/packages/core/src/sync/engine.ts`) and of the Apple
 * client's `SyncEngine.swift`, following guide chapter 6.
 *
 * Everything is read from memory, loaded from the local store at start-up.
 * Each local edit is applied at once, persisted shortly after, and queued in
 * an outbox that is pushed in the background. Pulls bring everything after
 * the last seen `seq`; fields with unpushed local edits keep the local value
 * until they are pushed. Note bodies carry the `rev` they were based on, so
 * the server can three-way merge concurrent edits.
 *
 * Not thread-safe: every call, and [scope], must be on one thread (the main
 * thread in the app). Network calls suspend inside the transport.
 */
class SyncEngine(
    private val store: DocumentStore,
    /** Null for a local library: edits are only saved, never queued or sent. */
    private val transport: SyncTransport?,
    val device: String,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 800,
    private val persistDelayMs: Long = 300,
    private val now: () -> Millis = ::nowMillis,
    /**
     * Files that live on this device: uploads waiting for the network
     * (`local-` ids, guide 7.2) and, in a local library, every attachment.
     * Null: the library cannot take files.
     */
    val files: File? = null,
) {
    private val _notes = HashMap<String, Note>()
    private val _folders = HashMap<String, Folder>()
    private val _tags = HashMap<String, Tag>()
    private val _attachments = HashMap<String, Attachment>()
    val notes: Map<String, Note> get() = _notes
    val folders: Map<String, Folder> get() = _folders
    val tags: Map<String, Tag> get() = _tags
    val attachments: Map<String, Attachment> get() = _attachments
    private val _uploads = LinkedHashMap<String, PendingUpload>()
    /** Files added offline, waiting for the next sync. */
    val uploads: Map<String, PendingUpload> get() = _uploads

    /** Set when a queued file was refused; cleared by the UI. */
    private val _uploadFailure = MutableStateFlow<UploadFailure?>(null)
    val uploadFailure: StateFlow<UploadFailure?> = _uploadFailure.asStateFlow()
    fun clearUploadFailure() { _uploadFailure.value = null }

    /**
     * Called with each file the server took and the local copy it was sent
     * from, before that copy is deleted: the app keeps it in its attachment
     * cache, so the file shows offline without a download.
     */
    var onUploaded: ((Attachment, File) -> Unit)? = null

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Bumped whenever notes, folders, tags or attachments change: what the UI watches. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Set when the server answers with a conflict copy; cleared by the UI. */
    private val _conflict = MutableStateFlow<ConflictNotice?>(null)
    val conflict: StateFlow<ConflictNotice?> = _conflict.asStateFlow()
    fun clearConflict() { _conflict.value = null }

    var cursor: Long = 0
        private set

    /** Called when the server answers 401: the session is over. */
    var onUnauthorized: (() -> Unit)? = null

    internal val outbox = HashMap<String, OutboxEntry>()
    private val hlc = HLC(device)
    private var syncTimer: Job? = null
    private var persistTimer: Job? = null
    private var running: Deferred<Unit>? = null
    private var again = false
    private var stopped = false
    private var retryDelayMs = 2_000L

    private class Dirty {
        val notes = HashSet<String>()
        val folders = HashSet<String>()
        val tags = HashSet<String>()
        val outbox = HashSet<String>()
        val isEmpty get() = notes.isEmpty() && folders.isEmpty() && tags.isEmpty() && outbox.isEmpty()
    }

    private var dirty = Dirty()

    /** A local library: no server, no outbox. */
    val isLocal: Boolean get() = transport == null

    /** A note with local changes not yet on the server (the shelf's pending dot). */
    fun hasPending(noteId: String): Boolean = outbox.containsKey("note:$noteId")

    val liveNotes: List<Note> get() = _notes.values.filter { !it.isTrashed && !it.deleted }
    val trashedNotes: List<Note> get() = _notes.values.filter { it.isTrashed }

    /** Loads the local copy. The first [sync] then downloads a snapshot. Safe off the main thread before use. */
    fun load() {
        for (doc in store.all(Table.NOTES)) decode(doc, Note.serializer())?.let { _notes[it.id] = it }
        for (doc in store.all(Table.FOLDERS)) decode(doc, Folder.serializer())?.let { _folders[it.id] = it }
        for (doc in store.all(Table.TAGS)) decode(doc, Tag.serializer())?.let { _tags[it.id] = it }
        for (doc in store.all(Table.ATTACHMENTS)) decode(doc, Attachment.serializer())?.let { _attachments[it.id] = it }
        // A restart is a new attempt for refused files too (guide 7.2).
        for (doc in store.all(Table.UPLOADS)) decode(doc, PendingUpload.serializer())?.let { _uploads[it.id] = it.copy(failed = null) }
        for (doc in store.all(Table.OUTBOX)) {
            // A restart is a new attempt: the clock or the server may have changed.
            decode(doc, OutboxEntry.serializer())?.let { outbox[it.key] = it.copy(blocked = null) }
        }
        cursor = store.meta("cursor")?.toLongOrNull() ?: 0
        val last = store.meta("lastSyncAt")?.toLongOrNull() ?: 0
        _state.update { it.copy(lastSyncAt = last) }
        for (n in _notes.values) observeClock(n.hlc)
        for (f in _folders.values) observeClock(f.hlc)
        for (t in _tags.values) observeClock(t.hlc)
        updateCounts()
        changed()
    }

    // Sync loop (guide 6.8)

    /** Pushes, then pulls. Concurrent calls coalesce into one more round. */
    suspend fun sync() {
        if (stopped || transport == null) return
        running?.takeIf { it.isActive }?.let {
            again = true
            it.await()
            return
        }
        val task = scope.async {
            do {
                again = false
                runOnce()
            } while (again && !stopped)
        }
        running = task
        try {
            task.await()
        } finally {
            if (running === task) running = null
        }
    }

    private suspend fun runOnce() {
        syncTimer?.cancel()
        syncTimer = null
        _state.update { it.copy(status = SyncState.Status.SYNCING, initial = it.lastSyncAt == 0L) }
        try {
            flushUploads()
            push()
            pull()
            retryDelayMs = 2_000
            val at = now()
            store.write(StoreBatch().apply { meta["lastSyncAt"] = at.toString() })
            _state.update { it.copy(lastSyncAt = at, status = SyncState.Status.IDLE, initial = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiError && e.status == 401) {
                _state.update { it.copy(status = SyncState.Status.ERROR, error = "unauthorized", initial = false) }
                onUnauthorized?.invoke()
                return
            }
            val offline = (e as? ApiError)?.isTransient ?: false
            _state.update {
                it.copy(
                    status = if (offline) SyncState.Status.OFFLINE else SyncState.Status.ERROR,
                    error = if (offline) null else e.message,
                    initial = false,
                )
            }
            scheduleSync(retryDelayMs)
            retryDelayMs = minOf(retryDelayMs * 2, MAX_RETRY_MS)
        }
    }

    /** Schedules a sync (default: the edit debounce), replacing a scheduled one. */
    fun scheduleSync(delayMs: Long = debounceMs) {
        if (stopped || transport == null) return
        syncTimer?.cancel()
        syncTimer = scope.launch {
            delay(delayMs)
            syncTimer = null
            sync()
        }
    }

    /** Reacts to a server notification (SSE). */
    fun onServerSeq(seq: Long) {
        if (seq > cursor) scope.launch { sync() }
    }

    /** Stops timers. Pending changes stay persisted for the next start. */
    fun stop() {
        stopped = true
        syncTimer?.cancel()
        persistNow()
    }

    // Pull (guide 6.5)

    internal suspend fun pull() {
        val transport = transport ?: return
        val full = cursor == 0L
        val res = if (full) transport.snapshot() else transport.pull(cursor)
        applyPull(res, snapshot = full)
        cursor = res.seq
        persistNow()
        store.write(StoreBatch().apply { meta["cursor"] = cursor.toString() })
    }

    /** Applies a pull or snapshot. A snapshot replaces entities missing from it. */
    fun applyPull(res: PullResponse, snapshot: Boolean) {
        val batch = StoreBatch()
        if (snapshot) {
            prune(_notes, EntityType.NOTE, res.notes.mapTo(HashSet()) { it.id }, dirty.notes)
            prune(_folders, EntityType.FOLDER, res.folders.mapTo(HashSet()) { it.id }, dirty.folders)
            prune(_tags, EntityType.TAG, res.tags.mapTo(HashSet()) { it.id }, dirty.tags)
            _attachments.clear()
            batch.clears += Table.ATTACHMENTS
        }
        for (f in res.folders) { observeClock(f.hlc); applyRemote(EntityType.FOLDER, f, _folders, dirty.folders) }
        for (t in res.tags) { observeClock(t.hlc); applyRemote(EntityType.TAG, t, _tags, dirty.tags) }
        for (n in res.notes) { observeClock(n.hlc); applyRemote(EntityType.NOTE, n, _notes, dirty.notes) }
        for (a in res.attachments) {
            if (a.deleted) {
                _attachments.remove(a.id)
                batch.delete(Table.ATTACHMENTS, a.id)
            } else {
                _attachments[a.id] = a
                batch.put(Table.ATTACHMENTS, a.id, IvyJson.encodeToString(Attachment.serializer(), a))
            }
        }
        if (!batch.isEmpty) store.write(batch)
        updateCounts()
        changed()
    }

    private fun <T> prune(map: HashMap<String, T>, type: EntityType, keep: Set<String>, dirtyIds: HashSet<String>) {
        val gone = map.keys.filter { it !in keep && outbox["${type.wire}:$it"] == null }
        for (id in gone) {
            map.remove(id)
            dirtyIds += id
        }
    }

    private fun <T : SyncEntity<T>> applyRemote(type: EntityType, remote: T, map: HashMap<String, T>, dirtyIds: HashSet<String>) {
        val key = "${type.wire}:${remote.id}"
        val pending = outbox[key]
        dirtyIds += remote.id
        if (remote.deleted) {
            // A tombstone wins over unpushed edits.
            map.remove(remote.id)
            if (pending != null) removeOutbox(key)
            return
        }
        if (pending?.op == Op.DELETE) return
        var merged = remote
        if (pending != null) {
            val local = map[remote.id]
            if (local != null) {
                for (field in pending.fields.keys) merged = merged.keepField(field, local)
            } else {
                // Written before the entity arrived here, as in a conflict copy.
                for ((field, value) in pending.fields) merged = merged.applyField(field, value, now())
            }
        }
        map[remote.id] = merged
    }

    private fun observeClock(stamps: Map<String, String>?) {
        stamps?.values?.forEach(hlc::observe)
    }

    // Push (guide 6.3, 6.7)

    internal suspend fun push() {
        val keys = outbox.values.filter { it.blocked == null }.sortedBy { it.order }.map { it.key }
        var i = 0
        while (i < keys.size) {
            val batch = mutableListOf<OutboxEntry>()
            var bytes = 0
            while (i < keys.size && batch.size < PUSH_BATCH) {
                val e = outbox[keys[i]]
                if (e == null || e.blocked != null) { i++; continue }
                val size = utf8Length(IvyJson.encodeToString(Change.serializer(), e.change))
                if (batch.isNotEmpty() && bytes + size > PUSH_BYTES) break
                batch += e
                bytes += size
                i++
            }
            if (batch.isNotEmpty()) send(batch)
            persistNow()
            updateCounts()
        }
    }

    /** Pushes a batch. A refused one is split until the change at fault is alone. */
    private suspend fun send(batch: List<OutboxEntry>) {
        val transport = transport ?: return
        val res = try {
            transport.push(batch.map { it.change })
        } catch (e: ApiError.Server) {
            if (e.code != 400 && e.code != 413) throw e
            if (batch.size == 1) {
                val b = batch[0]
                applyResult(b, ChangeResult(b.type, b.id, ok = false, error = e.serverMessage.ifEmpty { "rejected" }))
                return
            }
            val mid = batch.size / 2
            send(batch.subList(0, mid))
            send(batch.subList(mid, batch.size))
            return
        }
        for ((sent, r) in batch.zip(res.results)) applyResult(sent, r)
        changed()
    }

    internal fun applyResult(sent: OutboxEntry, r: ChangeResult) {
        var cur = outbox[sent.key]
        val unchanged = cur != null && cur.version == sent.version

        if (!r.ok) {
            // The edit stays here rather than vanish: it waits for the next
            // edit or restart, and pulls keep its fields.
            if (unchanged) setOutbox(cur!!.copy(blocked = r.error ?: "rejected"))
            return
        }
        if (unchanged) {
            removeOutbox(sent.key)
            cur = null
        }

        when (sent.type) {
            EntityType.FOLDER -> {
                val f = _folders[sent.id]
                if (r.seq != null && f != null) { _folders[sent.id] = f.copy(seq = r.seq); dirty.folders += sent.id }
                return
            }
            EntityType.TAG -> {
                val t = _tags[sent.id]
                if (r.seq != null && t != null) { _tags[sent.id] = t.copy(seq = r.seq); dirty.tags += sent.id }
                return
            }
            EntityType.NOTE -> Unit
        }

        var n = _notes[sent.id] ?: return
        if (sent.op == Op.DELETE) return
        if (r.seq != null) n = n.copy(seq = r.seq)
        val bodySent = sent.fields.containsKey("body")

        if (r.conflictCopy != null) {
            // The server kept its body; the pushed one lives in the copy, and
            // so does anything written since. The next pull restores the note.
            val c = cur
            val body = c?.fields?.get("body")
            if (c != null && body != null) {
                val rest = c.fields - "body"
                if (rest.isEmpty()) removeOutbox(c.key) else setOutbox(c.copy(fields = rest, baseRev = null))
                enqueue(EntityType.NOTE, r.conflictCopy, mapOf("body" to body), baseRev = 1)
            }
            _notes[n.id] = n
            dirty.notes += n.id
            _conflict.value = ConflictNotice(sent.id, r.conflictCopy)
            return
        }

        if (r.rev != null) {
            val c = cur
            if (c == null) {
                n = n.copy(rev = r.rev)
            } else if (bodySent && r.merged != true) {
                // Later edits build on the accepted body.
                n = n.copy(rev = r.rev)
                cur = c.copy(baseRev = r.rev)
                setOutbox(cur)
            } else if (!bodySent) {
                n = n.copy(rev = r.rev)
            }
            // Merged while newer local edits wait: keep the old base so the
            // server merges the newer text against it again.
        }
        if (r.merged == true && r.body != null && cur == null && n.body != r.body) {
            n = n.copy(body = r.body)
        }
        _notes[n.id] = n
        dirty.notes += n.id
    }

    // Outbox (guide 6.6)

    private fun enqueue(type: EntityType, id: String, fields: Map<String, JsonElement>, delete: Boolean = false, baseRev: Long? = null) {
        // A local library has nothing to sync with: the edit is just saved.
        if (transport == null) {
            schedulePersist()
            return
        }
        val key = "${type.wire}:$id"
        val ts = hlc.tick(now())
        var e = outbox[key] ?: OutboxEntry(key = key, type = type, id = id, op = Op.UPSERT, hlc = ts, device = device, order = ts)
        e = if (delete) {
            e.copy(op = Op.DELETE, fields = emptyMap(), baseRev = null)
        } else {
            e.copy(
                fields = e.fields + fields,
                baseRev = if (fields.containsKey("body") && e.baseRev == null) baseRev else e.baseRev,
            )
        }
        setOutbox(e.copy(blocked = null, hlc = ts, version = e.version + 1))
        schedulePersist()
        updateCounts()
        scheduleSync()
    }

    private fun setOutbox(e: OutboxEntry) {
        outbox[e.key] = e
        dirty.outbox += e.key
    }

    private fun removeOutbox(key: String) {
        outbox.remove(key)
        dirty.outbox += key
    }

    private fun updateCounts() {
        val blocked = outbox.values.count { it.blocked != null } + _uploads.values.count { it.failed != null }
        _state.update { it.copy(blocked = blocked, pending = outbox.size + _uploads.size - blocked) }
    }

    private fun changed() {
        _revision.value = _revision.value + 1
    }

    // Persistence

    private fun schedulePersist() {
        if (persistTimer != null) return
        persistTimer = scope.launch {
            delay(persistDelayMs)
            persistTimer = null
            persistNow()
        }
    }

    /** Hands what changed in memory to the local store. */
    fun persistNow() {
        persistTimer?.cancel()
        persistTimer = null
        if (dirty.isEmpty) return
        val d = dirty
        dirty = Dirty()
        val batch = StoreBatch()
        fun <T> split(table: Table, ids: Set<String>, map: Map<String, T>, encode: (T) -> String) {
            for (id in ids) {
                val v = map[id]
                if (v != null) batch.put(table, id, encode(v)) else batch.delete(table, id)
            }
        }
        split(Table.NOTES, d.notes, _notes) { IvyJson.encodeToString(Note.serializer(), it) }
        split(Table.FOLDERS, d.folders, _folders) { IvyJson.encodeToString(Folder.serializer(), it) }
        split(Table.TAGS, d.tags, _tags) { IvyJson.encodeToString(Tag.serializer(), it) }
        split(Table.OUTBOX, d.outbox, outbox) { IvyJson.encodeToString(OutboxEntry.serializer(), it) }
        try {
            store.write(batch)
        } catch (e: Exception) {
            // Keep the changes in memory and try again with the next write.
            dirty.notes += d.notes; dirty.folders += d.folders; dirty.tags += d.tags; dirty.outbox += d.outbox
            _state.update { it.copy(error = e.message) }
        }
    }

    // Local edits

    data class NotePatch(
        val title: String? = null,
        val body: String? = null,
        val folderId: String? = null,
        val tags: List<String>? = null,
        val pinned: Boolean? = null,
        val trashed: Boolean? = null,
    )

    fun createNote(title: String = "", body: String = "", folderId: String = "", tags: List<String> = emptyList(), pinned: Boolean = false): Note {
        val t = now()
        var id = newEntityId()
        while (_notes.containsKey(id)) id = newEntityId()
        val note = Note(id = id, folderId = folderId, title = title, body = body, tags = tags, pinned = pinned, createdAt = t, updatedAt = t)
        _notes[id] = note
        dirty.notes += id
        val fields = linkedMapOf<String, JsonElement>("title" to JsonPrimitive(title), "body" to JsonPrimitive(body), "createdAt" to JsonPrimitive(t))
        if (folderId.isNotEmpty()) fields["folderId"] = JsonPrimitive(folderId)
        if (tags.isNotEmpty()) fields["tags"] = JsonArray(tags.map(::JsonPrimitive))
        if (pinned) fields["pinned"] = JsonPrimitive(true)
        enqueue(EntityType.NOTE, id, fields, baseRev = 0)
        changed()
        return note
    }

    /** A copy next to the original, titled `<title> 副本` (guide 3.2). */
    fun duplicateNote(id: String): Note? {
        val n = _notes[id] ?: return null
        val title = (n.title.ifBlank { L10n.s.untitled } + L10n.s.duplicateSuffix).take(300)
        return createNote(title = title, body = n.body, folderId = n.folderId, tags = n.tags)
    }

    fun updateNote(id: String, patch: NotePatch) {
        var n = _notes[id] ?: return
        val fields = linkedMapOf<String, JsonElement>()
        patch.title?.let { if (it != n.title) { n = n.copy(title = it); fields["title"] = JsonPrimitive(it) } }
        patch.body?.let { if (it != n.body) { n = n.copy(body = it); fields["body"] = JsonPrimitive(it) } }
        patch.folderId?.let { if (it != n.folderId) { n = n.copy(folderId = it); fields["folderId"] = JsonPrimitive(it) } }
        patch.tags?.let { if (it != n.tags) { n = n.copy(tags = it); fields["tags"] = JsonArray(it.map(::JsonPrimitive)) } }
        patch.pinned?.let { if (it != n.pinned) { n = n.copy(pinned = it); fields["pinned"] = JsonPrimitive(it) } }
        patch.trashed?.let { if (it != n.isTrashed) { n = n.copy(trashedAt = if (it) now() else 0); fields["trashed"] = JsonPrimitive(it) } }
        if (fields.isEmpty()) return
        n = n.copy(updatedAt = now())
        _notes[id] = n
        dirty.notes += id
        enqueue(EntityType.NOTE, id, fields, baseRev = n.rev)
        changed()
    }

    /** Permanently deletes a note. */
    fun deleteNote(id: String) {
        if (_notes.remove(id) == null) return
        dirty.notes += id
        enqueue(EntityType.NOTE, id, emptyMap(), delete = true)
        changed()
    }

    /** Permanently deletes every note in the trash. */
    fun emptyTrash() {
        for (n in trashedNotes) deleteNote(n.id)
    }

    fun createFolder(name: String, parentId: String = ""): Folder {
        val t = now()
        val sort = (_folders.values.filter { it.parentId == parentId }.maxOfOrNull { it.sort } ?: 0.0) + 1
        val f = Folder(id = newEntityId(), parentId = parentId, name = name, sort = sort, createdAt = t, updatedAt = t)
        _folders[f.id] = f
        dirty.folders += f.id
        enqueue(EntityType.FOLDER, f.id, mapOf("name" to JsonPrimitive(name), "parentId" to JsonPrimitive(parentId), "sort" to JsonPrimitive(sort)))
        changed()
        return f
    }

    fun renameFolder(id: String, name: String) {
        val f = _folders[id] ?: return
        if (f.name == name) return
        _folders[id] = f.copy(name = name, updatedAt = now())
        dirty.folders += id
        enqueue(EntityType.FOLDER, id, mapOf("name" to JsonPrimitive(name)))
        changed()
    }

    /** Whether folder [id] may move under [parent] without a cycle (depth ≤ 64). */
    fun canMoveFolder(id: String, parent: String): Boolean {
        var p = parent
        var depth = 0
        while (p.isNotEmpty()) {
            if (p == id || depth > 64) return false
            val f = _folders[p] ?: return false
            p = f.parentId
            depth++
        }
        return true
    }

    fun moveFolder(id: String, parent: String) {
        val f = _folders[id] ?: return
        if (f.parentId == parent || !canMoveFolder(id, parent)) return
        _folders[id] = f.copy(parentId = parent, updatedAt = now())
        dirty.folders += id
        enqueue(EntityType.FOLDER, id, mapOf("parentId" to JsonPrimitive(parent)))
        changed()
    }

    /** Deletes a folder tree; its notes go to the trash, as on the server. */
    fun deleteFolder(id: String) {
        if (!_folders.containsKey(id)) return
        val doomed = hashSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (f in _folders.values) {
                if (f.id !in doomed && f.parentId in doomed) {
                    doomed += f.id
                    grew = true
                }
            }
        }
        val t = now()
        for (n in _notes.values.toList()) {
            if (n.folderId in doomed) {
                _notes[n.id] = n.copy(folderId = "", trashedAt = if (n.trashedAt == 0L) t else n.trashedAt)
                dirty.notes += n.id
            }
        }
        for (fid in doomed) {
            _folders.remove(fid)
            dirty.folders += fid
        }
        enqueue(EntityType.FOLDER, id, emptyMap(), delete = true)
        changed()
    }

    fun createTag(name: String, color: String = ""): Tag {
        val t = now()
        val clean = cleanTagName(name)
        val tag = Tag(id = newEntityId(), name = clean, color = color, createdAt = t, updatedAt = t)
        _tags[tag.id] = tag
        dirty.tags += tag.id
        enqueue(EntityType.TAG, tag.id, mapOf("name" to JsonPrimitive(clean), "color" to JsonPrimitive(color)))
        changed()
        return tag
    }

    /** Renames or recolors a tag; [name] is cleaned as on the server (no `#`, at most 50). */
    fun updateTag(id: String, name: String? = null, color: String? = null) {
        var t = _tags[id] ?: return
        val fields = linkedMapOf<String, JsonElement>()
        name?.let { cleanTagName(it) }?.takeIf { it.isNotEmpty() && it != t.name }?.let { t = t.copy(name = it); fields["name"] = JsonPrimitive(it) }
        color?.takeIf { it != t.color }?.let { t = t.copy(color = it); fields["color"] = JsonPrimitive(it) }
        if (fields.isEmpty()) return
        _tags[id] = t.copy(updatedAt = now())
        dirty.tags += id
        enqueue(EntityType.TAG, id, fields)
        changed()
    }

    /** The tag of that name (case and spacing ignored), if there is one. */
    fun tagNamed(name: String): Tag? {
        val key = Links.normalizeTitle(cleanTagName(name))
        if (key.isEmpty()) return null
        return _tags.values.filter { !it.deleted && Links.normalizeTitle(it.name) == key }.minByOrNull { it.createdAt }
    }

    /** Adds or removes one tag on a note (at most [MAX_NOTE_TAGS], guide 4.3). */
    fun setNoteTag(noteId: String, tagId: String, on: Boolean) {
        val n = _notes[noteId] ?: return
        val tags = if (on) {
            if (tagId in n.tags || n.tags.size >= MAX_NOTE_TAGS) return
            n.tags + tagId
        } else {
            if (tagId !in n.tags) return
            n.tags - tagId
        }
        updateNote(noteId, NotePatch(tags = tags))
    }

    fun deleteTag(id: String) {
        if (_tags.remove(id) == null) return
        dirty.tags += id
        for (n in _notes.values.toList()) {
            if (id in n.tags) {
                _notes[n.id] = n.copy(tags = n.tags.filter { it != id })
                dirty.notes += n.id
            }
        }
        enqueue(EntityType.TAG, id, emptyMap(), delete = true)
        changed()
    }

    // Attachments (guide 7)

    /**
     * Adds a file: uploaded now; queued under a `local-` id when the server
     * cannot be reached (only then, guide 7.1); in a local library kept on
     * this device. Other server errors are thrown. [source] is not kept, so
     * it may be a temporary file.
     */
    suspend fun addFile(source: File, name: String, mime: String): AddedFile {
        val dir = files ?: throw IllegalStateException("this library cannot take files")
        val type = mime.ifEmpty { "application/octet-stream" }
        val kind = Attachment.kindOf(type)
        val transport = transport
        if (transport == null) return addLocalFile(dir, source, name, type)
        try {
            val res = transport.upload(source, name, type)
            putAttachment(res.attachment)
            onUploaded?.invoke(res.attachment, source)
            return AddedFile(res.attachment.id, res.attachment.name.ifEmpty { name }, res.attachment.kind, queued = false, duplicate = res.duplicate)
        } catch (e: ApiError.Unreachable) {
            val id = "local-" + newEntityId()
            val size = withContext(Dispatchers.IO) {
                dir.mkdirs()
                source.copyTo(File(dir, id), overwrite = true).length()
            }
            val up = PendingUpload(id = id, name = name, mime = type, size = size, createdAt = now())
            _uploads[id] = up
            store.write(StoreBatch().apply { put(Table.UPLOADS, id, IvyJson.encodeToString(PendingUpload.serializer(), up)) })
            _state.update { it.copy(status = SyncState.Status.OFFLINE) }
            updateCounts()
            changed()
            scheduleSync(retryDelayMs)
            return AddedFile(id, name, kind, queued = true)
        }
    }

    /** A local library's file: copied in under a new id, or the same file already there. */
    private suspend fun addLocalFile(dir: File, source: File, name: String, mime: String): AddedFile {
        val id = newEntityId()
        val target = File(dir, id)
        val (hash, size) = withContext(Dispatchers.IO) {
            dir.mkdirs()
            source.copyTo(target, overwrite = true)
            sha256(target) to target.length()
        }
        _attachments.values.firstOrNull { it.sha256 == hash && !it.deleted }?.let { same ->
            withContext(Dispatchers.IO) { target.delete() }
            if (same.isTrashed) putAttachment(same.copy(trashedAt = 0, trashReason = "", updatedAt = now()))
            return AddedFile(same.id, same.name, same.kind, queued = false, duplicate = true)
        }
        val t = now()
        val a = Attachment(id = id, name = name, mime = mime, kind = Attachment.kindOf(mime), size = size, sha256 = hash,
            storage = "device", createdAt = t, updatedAt = t)
        putAttachment(a)
        return AddedFile(id, name, a.kind, queued = false)
    }

    /** Uploads queued files and rewrites their `local-` ids in note bodies (guide 7.2). */
    suspend fun flushUploads() {
        val transport = transport ?: return
        val dir = files ?: return
        for (up in _uploads.values.toList()) {
            if (up.failed != null) continue
            val file = File(dir, up.id)
            if (!file.exists()) {
                // Lost (storage cleared): nothing to send, drop the entry.
                dropUpload(up.id)
                continue
            }
            val res = try {
                transport.upload(file, up.name, up.mime)
            } catch (e: ApiError.Server) {
                if (!refused(e.code)) throw e
                // One bad file must not hold back everything else.
                val msg = if (e.code == 413) L10n.s.fileTooLarge else e.message ?: "rejected"
                _uploads[up.id] = up.copy(failed = msg)
                _uploadFailure.value = UploadFailure(up.name, msg)
                updateCounts()
                continue
            }
            val att = res.attachment
            putAttachment(att)
            val ids = mapOf(up.id to att.id)
            for (n in _notes.values.toList()) {
                if (n.body.contains(up.id)) updateNote(n.id, NotePatch(body = Links.rewriteResources(n.body, ids)))
            }
            onUploaded?.invoke(att, file)
            dropUpload(up.id)
        }
    }

    private suspend fun dropUpload(id: String) {
        _uploads.remove(id)
        store.write(StoreBatch().apply { delete(Table.UPLOADS, id) })
        files?.let { dir -> withContext(Dispatchers.IO) { File(dir, id).delete() } }
        updateCounts()
        changed()
    }

    /** The file on this device for [id]: a queued upload or a local library's attachment. */
    fun localFile(id: String): File? {
        val dir = files ?: return null
        if (!id.startsWith("local-") && !isLocal) return null
        return File(dir, id).takeIf { it.exists() }
    }

    /** image / video / audio / file, for embeds; null when unknown. */
    fun attachmentKind(id: String): String? =
        _uploads[id]?.let { Attachment.kindOf(it.mime) } ?: _attachments[id]?.kind

    /** The MIME type of an attachment or queued file. */
    fun attachmentMime(id: String): String? = _uploads[id]?.mime ?: _attachments[id]?.mime

    /** Records an attachment's metadata (server answers; a local library's own files). */
    fun putAttachment(a: Attachment) {
        _attachments[a.id] = a
        store.write(StoreBatch().apply { put(Table.ATTACHMENTS, a.id, IvyJson.encodeToString(Attachment.serializer(), a)) })
        changed()
    }

    /** Forgets an attachment; in a local library its file goes too. */
    fun removeAttachment(id: String) {
        _attachments.remove(id) ?: return
        store.write(StoreBatch().apply { delete(Table.ATTACHMENTS, id) })
        if (isLocal) files?.let { File(it, id).delete() }
        changed()
    }

    /** Which notes reference each attachment, from the local bodies (trashed notes count, as on the server). */
    fun attachmentRefs(): Map<String, List<String>> {
        val refs = HashMap<String, MutableList<String>>()
        for (n in _notes.values) {
            if (n.deleted || !n.body.contains("ivy://res/")) continue
            for (id in Links.resourceRefs(n.body)) refs.getOrPut(id) { mutableListOf() }.add(n.id)
        }
        return refs
    }

    private fun <T> decode(doc: String, s: kotlinx.serialization.KSerializer<T>): T? =
        // A row this build cannot read is skipped, not fatal: the next
        // snapshot or pull brings the entity back.
        try { IvyJson.decodeFromString(s, doc) } catch (_: Exception) { null }

    companion object {
        const val MAX_NOTE_TAGS = 50

        /** As the server stores tag names: no leading `#`, trimmed, at most 50 characters. */
        fun cleanTagName(name: String): String = name.trim().trimStart('#').trim().replace('\n', ' ').take(50)

        /** Guide 7.2: other 4xx refuse this one file; network, 5xx, 401, 408 and 429 stop the round. */
        internal fun refused(code: Int): Boolean = code in 400..499 && code !in setOf(401, 408, 429)

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        internal fun utf8Length(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            return n
        }

        const val PUSH_BATCH = 200
        // Below what the server reads; one larger change still goes out alone.
        const val PUSH_BYTES = 6 shl 20
        const val MAX_RETRY_MS = 60_000L
    }
}
