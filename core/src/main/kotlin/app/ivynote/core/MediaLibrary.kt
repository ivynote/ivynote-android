package app.ivynote.core

/**
 * The media library (guide 3.4, 11.6): every file of a library, filtered and
 * searched, with the notes that reference each one. A server library asks the
 * server and falls back to the synced metadata when offline (read-only); a
 * local library manages its own files. Main thread, like [SyncEngine].
 */
class MediaLibrary(private val engine: SyncEngine, private val api: ApiClient?) {

    enum class Filter(val kind: String = "") {
        ALL, IMAGE("image"), VIDEO("video"), AUDIO("audio"), FILE("file"), ORPHANED, TRASH;

        val label: String get() = with(L10n.s) {
            when (this@Filter) {
                ALL -> filterAll
                IMAGE -> filterImages
                VIDEO -> filterVideo
                AUDIO -> filterAudio
                FILE -> filterFiles
                ORPHANED -> unreferenced
                TRASH -> trash
            }
        }
    }

    data class Listing(
        val items: List<Attachment>,
        /** By [Filter.name], for the chips. */
        val counts: Map<Filter, Int>,
        /** Days before an unreferenced file goes to the trash; 0 = never. */
        val orphanDays: Int = 0,
        /** From this device's copy: the server could not be reached. */
        val offline: Boolean = false,
    )

    /** Server changes need the network; a local library has no public links. */
    val canPublish: Boolean get() = api != null

    suspend fun list(filter: Filter, query: String = ""): Listing {
        if (api != null) {
            try {
                val r = api.media(kind = filter.kind, trashed = filter == Filter.TRASH, orphaned = filter == Filter.ORPHANED, query = query)
                val counts = Filter.entries.associateWith { f ->
                    when (f) {
                        Filter.ALL -> r.counts["all"] ?: 0
                        Filter.ORPHANED -> r.counts["orphaned"] ?: 0
                        Filter.TRASH -> -1
                        else -> r.counts[f.kind] ?: 0
                    }
                }
                return Listing(r.items, counts, r.policy.orphanDays)
            } catch (e: ApiError) {
                if (!e.isTransient) throw e
            }
        }
        return localList(filter, query).copy(offline = api != null)
    }

    /** From the attachments this device knows, with references counted from the local notes. */
    fun localList(filter: Filter, query: String = ""): Listing {
        val refs = engine.attachmentRefs()
        val q = query.trim().lowercase()
        val counts = Filter.entries.associateWithTo(LinkedHashMap()) { 0 }
        val items = ArrayList<Attachment>()
        for (raw in engine.attachments.values.sortedByDescending { it.createdAt }) {
            if (raw.deleted) continue
            if (raw.isTrashed != (filter == Filter.TRASH)) continue
            val a = raw.copy(refs = refs[raw.id]?.size ?: 0)
            if (q.isNotEmpty() && !a.name.lowercase().contains(q)) continue
            counts[Filter.ALL] = counts.getValue(Filter.ALL) + 1
            Filter.entries.firstOrNull { it.kind == a.kind }?.let { counts[it] = counts.getValue(it) + 1 }
            if (a.refs == 0) counts[Filter.ORPHANED] = counts.getValue(Filter.ORPHANED) + 1
            if (filter.kind.isNotEmpty() && a.kind != filter.kind) continue
            if (filter == Filter.ORPHANED && a.refs > 0) continue
            items += a
        }
        counts[Filter.TRASH] = -1
        return Listing(items, counts)
    }

    /** The file and the notes that reference it. */
    suspend fun detail(id: String): AttachmentDetail {
        if (api != null) {
            try {
                return api.attachment(id).also { engine.putAttachment(it.attachment) }
            } catch (e: ApiError) {
                if (!e.isTransient) throw e
            }
        }
        val a = engine.attachments[id] ?: throw ApiError.Server(404, L10n.s.fileNotFound)
        val ids = engine.attachmentRefs()[id].orEmpty()
        val notes = ids.mapNotNull { engine.notes[it] }.map { NoteRef(it.id, it.title, it.folderId) }
        return AttachmentDetail(a.copy(refs = notes.size), notes)
    }

    suspend fun rename(id: String, name: String): Attachment {
        val clean = name.trim().replace('\n', ' ')
        if (clean.isEmpty()) throw ApiError.Server(400, L10n.s.nameEmpty)
        return change(id, { it.renameAttachment(id, clean) }) { it.copy(name = clean) }
    }

    suspend fun trash(id: String): Attachment =
        change(id, { it.trashAttachment(id) }) { it.copy(trashedAt = nowMillis(), trashReason = "manual") }

    suspend fun restore(id: String): Attachment =
        change(id, { it.restoreAttachment(id) }) { it.copy(trashedAt = 0, trashReason = "", orphanSince = 0) }

    /** Deletes for good; the file must be in the trash. */
    suspend fun purge(id: String) {
        if (api != null) api.purgeAttachment(id)
        engine.removeAttachment(id)
    }

    /** The public address, made on first use (guide 8.3: no longer behind sign-in). */
    suspend fun publish(id: String): String {
        val api = api ?: throw ApiError.Server(400, L10n.s.localNoPublicLink)
        val url = api.publishAttachment(id)
        engine.attachments[id]?.let { engine.putAttachment(it.copy(public = true)) }
        return url
    }

    suspend fun unpublish(id: String): Attachment {
        val api = api ?: throw ApiError.Server(400, L10n.s.localNoPublicLink)
        return api.unpublishAttachment(id).also { engine.putAttachment(it) }
    }

    private suspend fun change(id: String, remote: suspend (ApiClient) -> Attachment, local: (Attachment) -> Attachment): Attachment {
        val updated = if (api != null) {
            remote(api)
        } else {
            val a = engine.attachments[id] ?: throw ApiError.Server(404, L10n.s.fileNotFound)
            local(a).copy(updatedAt = nowMillis())
        }
        engine.putAttachment(updated)
        return updated
    }
}
