package app.ivynote.android.platform

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import app.ivynote.core.DocumentStore
import app.ivynote.core.StoreBatch
import app.ivynote.core.Table
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One library's database (guide 4.4): a table of JSON documents per entity
 * type, plus `meta`. Writes go to one background thread in order, each batch
 * in one transaction, so typing never waits for the disk and data always
 * lands before the cursor that follows it.
 */
class SqliteDocumentStore(file: File) : DocumentStore {
    private val db: SQLiteDatabase
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ivy-store").apply { isDaemon = true } }
    /** Batches that failed (disk full); retried before the next one. */
    private val failed = ArrayDeque<StoreBatch>()

    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
        db.enableWriteAheadLogging()
        for (t in Table.entries) db.execSQL("CREATE TABLE IF NOT EXISTS ${t.sql} (id TEXT PRIMARY KEY, doc TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    private val Table.sql: String get() = name.lowercase()

    override fun all(table: Table): List<String> {
        val out = ArrayList<String>()
        db.rawQuery("SELECT doc FROM ${table.sql}", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    override fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun write(batch: StoreBatch) {
        if (batch.isEmpty) return
        writer.execute {
            synchronized(failed) {
                while (failed.isNotEmpty()) {
                    if (!apply(failed.first())) {
                        failed.addLast(batch)
                        return@execute
                    }
                    failed.removeFirst()
                }
                if (!apply(batch)) failed.addLast(batch)
            }
        }
    }

    private fun apply(batch: StoreBatch): Boolean = try {
        db.beginTransaction()
        try {
            for (t in batch.clears) db.delete(t.sql, null, null)
            for ((t, items) in batch.puts) {
                val stmt = db.compileStatement("INSERT OR REPLACE INTO ${t.sql} (id, doc) VALUES (?, ?)")
                for ((id, doc) in items) {
                    stmt.clearBindings()
                    stmt.bindString(1, id)
                    stmt.bindString(2, doc)
                    stmt.executeInsert()
                }
                stmt.close()
            }
            for ((t, ids) in batch.deletes) {
                val stmt = db.compileStatement("DELETE FROM ${t.sql} WHERE id = ?")
                for (id in ids) {
                    stmt.clearBindings()
                    stmt.bindString(1, id)
                    stmt.executeUpdateDelete()
                }
                stmt.close()
            }
            for ((k, v) in batch.meta) {
                db.insertWithOnConflict("meta", null, ContentValues().apply { put("key", k); put("value", v) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        true
    } catch (e: Exception) {
        Log.e("ivy", "store write failed: ${e.javaClass.simpleName}")
        false
    }

    override fun flush() {
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            writer.execute { done.countDown() }
            done.await(10, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    override fun close() {
        flush()
        writer.shutdown()
        writer.awaitTermination(5, TimeUnit.SECONDS)
        db.close()
    }
}
