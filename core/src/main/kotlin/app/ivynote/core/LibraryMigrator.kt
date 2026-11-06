package app.ivynote.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns on sync for a local library: copies it into a newly registered,
 * empty account (the desktop client's `core/src/promote.ts`, the Apple
 * client's `LibraryMigrator.swift`).
 *
 * A local library keeps no outbox, so it cannot simply start syncing; its
 * content is uploaded once instead. Only an empty account takes it, so
 * nothing on the server is ever merged with it. Ids are kept, which makes a
 * retry after an interruption safe: the server ignores writes that change
 * nothing, and an account holding only this library's own ids counts as a
 * run to resume.
 */
class LibraryMigrator(
    private val api: SyncTransport,
    private val device: String,
    private val now: () -> Millis = ::nowMillis,
) {
    data class Content(val folders: List<Folder>, val tags: List<Tag>, val notes: List<Note>) {
        companion object {
            /** Reads a library's database directly; the library should not be open. */
            fun read(store: DocumentStore): Content = Content(
                folders = store.all(Table.FOLDERS).mapNotNull { runCatching { IvyJson.decodeFromString(Folder.serializer(), it) }.getOrNull() },
                tags = store.all(Table.TAGS).mapNotNull { runCatching { IvyJson.decodeFromString(Tag.serializer(), it) }.getOrNull() },
                notes = store.all(Table.NOTES).mapNotNull { runCatching { IvyJson.decodeFromString(Note.serializer(), it) }.getOrNull() },
            )
        }
    }

    data class Progress(val phase: Phase, val done: Int, val total: Int) {
        enum class Phase { CHECK, NOTES, DONE }
    }

    data class Result(val notes: Int = 0, val rejected: List<String> = emptyList())

    class AccountNotEmpty : Exception("这个账户里已经有笔记。本地笔记库只能迁移到新注册的空账户。")

    suspend fun run(content: Content, progress: suspend (Progress) -> Unit = {}): Result {
        val folders = parentsFirst(content.folders.filter { !it.deleted })
        val tags = content.tags.filter { !it.deleted }
        val notes = content.notes.filter { !it.deleted }

        progress(Progress(Progress.Phase.CHECK, 0, 1))
        val existing = api.pull(0)
        val ours = HashSet<String>().apply {
            folders.mapTo(this) { it.id }; tags.mapTo(this) { it.id }; notes.mapTo(this) { it.id }
        }
        val theirs = existing.notes.filter { !it.deleted }.map { it.id } +
            existing.folders.filter { !it.deleted }.map { it.id } +
            existing.tags.filter { !it.deleted }.map { it.id }
        val strayFiles = existing.attachments.any { !it.deleted }
        if (strayFiles || theirs.any { it !in ours }) throw AccountNotEmpty()

        val clock = HLC(device)
        val changes = ArrayList<Change>()
        for (f in folders) {
            changes += Change(EntityType.FOLDER, f.id, Op.UPSERT,
                mapOf("name" to JsonPrimitive(f.name), "parentId" to JsonPrimitive(f.parentId), "sort" to JsonPrimitive(f.sort)),
                clock.tick(now()), null, device)
        }
        for (t in tags) {
            changes += Change(EntityType.TAG, t.id, Op.UPSERT,
                mapOf("name" to JsonPrimitive(t.name), "color" to JsonPrimitive(t.color)), clock.tick(now()), null, device)
        }
        for (n in notes) {
            val fields = linkedMapOf<String, JsonElement>("title" to JsonPrimitive(n.title), "body" to JsonPrimitive(n.body), "createdAt" to JsonPrimitive(n.createdAt))
            if (n.folderId.isNotEmpty()) fields["folderId"] = JsonPrimitive(n.folderId)
            if (n.tags.isNotEmpty()) fields["tags"] = JsonArray(n.tags.map(::JsonPrimitive))
            if (n.pinned) fields["pinned"] = JsonPrimitive(true)
            if (n.isTrashed) fields["trashed"] = JsonPrimitive(true)
            changes += Change(EntityType.NOTE, n.id, Op.UPSERT, fields, clock.tick(now()), 0, device)
        }

        var rejected = ArrayList<String>()
        var accepted = 0
        var sent = 0
        var notesSent = 0
        while (sent < changes.size) {
            val batch = ArrayList<Change>()
            var bytes = 0
            while (sent + batch.size < changes.size && batch.size < BATCH_CHANGES) {
                val c = changes[sent + batch.size]
                val size = SyncEngine.utf8Length(IvyJson.encodeToString(Change.serializer(), c))
                if (batch.isNotEmpty() && bytes + size > BATCH_BYTES) break
                batch += c
                bytes += size
            }
            val res = api.push(batch)
            for (r in res.results) {
                if (!r.ok) rejected.add("${r.type.wire}:${r.id} ${r.error ?: "refused"}")
                else if (r.type == EntityType.NOTE) accepted++
            }
            sent += batch.size
            notesSent += batch.count { it.type == EntityType.NOTE }
            progress(Progress(Progress.Phase.NOTES, notesSent, notes.size))
        }
        progress(Progress(Progress.Phase.DONE, notes.size, notes.size))
        return Result(accepted, rejected)
    }

    companion object {
        const val BATCH_CHANGES = 200
        const val BATCH_BYTES = 2 shl 20

        /** Parents before children, so every parentId points at a folder the server already has. */
        fun parentsFirst(folders: List<Folder>): List<Folder> {
            val byId = HashMap<String, Folder>()
            for (f in folders) byId.putIfAbsent(f.id, f)
            val out = ArrayList<Folder>()
            val seen = HashSet<String>()
            fun visit(f: Folder, path: MutableSet<String>) {
                if (f.id in seen || f.id in path) return
                path += f.id
                if (f.parentId.isNotEmpty()) byId[f.parentId]?.let { visit(it, path) }
                seen += f.id
                out += f
            }
            for (f in folders) visit(f, HashSet())
            return out
        }
    }
}
