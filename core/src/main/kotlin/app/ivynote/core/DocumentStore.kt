package app.ivynote.core

import java.io.File

/** The tables of a library database: each maps an ID to a JSON document. */
enum class Table { NOTES, FOLDERS, TAGS, ATTACHMENTS, OUTBOX }

/** Writes applied together, in one transaction. */
class StoreBatch {
    val puts = mutableMapOf<Table, MutableList<Pair<String, String>>>()
    val deletes = mutableMapOf<Table, MutableList<String>>()
    val clears = mutableSetOf<Table>()
    val meta = mutableMapOf<String, String>()

    fun put(table: Table, id: String, doc: String) {
        puts.getOrPut(table) { mutableListOf() }.add(id to doc)
    }

    fun delete(table: Table, id: String) {
        deletes.getOrPut(table) { mutableListOf() }.add(id)
    }

    val isEmpty: Boolean get() = puts.isEmpty() && deletes.isEmpty() && clears.isEmpty() && meta.isEmpty()
}

/**
 * One library's local database (guide 4.4). The sync engine keeps everything
 * in memory and writes back what changed, so the store only needs batched
 * writes and load-all. Bodies are stored in plain text (guide 8.3).
 *
 * [write] may run asynchronously, but batches are applied in order and each
 * one atomically: data written before a cursor is never lost behind it.
 */
interface DocumentStore {
    fun all(table: Table): List<String>
    fun meta(key: String): String?
    fun write(batch: StoreBatch)

    /** Blocks until every batch handed to [write] is on disk. */
    fun flush() {}

    fun close() {}

    companion object {
        /** Deletes a library's database and everything next to it. */
        fun remove(file: File) {
            file.parentFile?.deleteRecursively()
        }
    }
}

/** In memory, for tests and previews. */
class MemoryStore : DocumentStore {
    private val tables = Table.entries.associateWith { linkedMapOf<String, String>() }
    private val metas = mutableMapOf<String, String>()

    @Synchronized override fun all(table: Table): List<String> = tables.getValue(table).values.toList()
    @Synchronized override fun meta(key: String): String? = metas[key]

    @Synchronized override fun write(batch: StoreBatch) {
        for (t in batch.clears) tables.getValue(t).clear()
        for ((t, items) in batch.puts) for ((id, doc) in items) tables.getValue(t)[id] = doc
        for ((t, ids) in batch.deletes) for (id in ids) tables.getValue(t).remove(id)
        metas.putAll(batch.meta)
    }
}
