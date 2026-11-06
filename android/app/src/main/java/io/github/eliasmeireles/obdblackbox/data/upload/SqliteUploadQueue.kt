package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo

class SqliteUploadQueue(context: Context) : UploadQueue {
    private val db = Helper(context).writableDatabase

    @Volatile private var logInserts = 0

    override fun addSession(info: SessionInfo) {
        val values = ContentValues().apply {
            put("id", info.id)
            put("source", info.kind.name)
            put("vehicle", info.vehicle)
            put("started_at", info.startedAtMs)
            put("registered", 0)
        }
        db.insertWithOnConflict("sessions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    override fun enqueue(sessionId: String, timestampMs: Long, json: String) {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("t", timestampMs)
            put("json", json)
        }
        db.insert("samples", null, values)
    }

    override fun unregisteredSessions(): List<SessionInfo> =
        db.rawQuery("SELECT id, source, started_at, vehicle FROM sessions WHERE registered = 0 ORDER BY started_at", null).use { c ->
            buildList { while (c.moveToNext()) add(SessionInfo(c.getString(0), SourceKind.valueOf(c.getString(1)), c.getLong(2), c.getString(3))) }
        }

    override fun markRegistered(sessionId: String) = setRegistered(sessionId, 1)

    override fun markUnregistered(sessionId: String) = setRegistered(sessionId, 0)

    override fun nextBatch(limit: Int): List<QueuedSample> {
        val sql = "SELECT id, session_id, json FROM samples WHERE session_id = " +
            "(SELECT session_id FROM samples ORDER BY id LIMIT 1) ORDER BY id LIMIT ?"
        return db.rawQuery(sql, arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(QueuedSample(c.getLong(0), c.getString(1), c.getString(2))) }
        }
    }

    override fun remove(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.delete("samples", "id IN (${ids.joinToString(",")})", null)
    }

    override fun pendingCount(): Long = DatabaseUtils.queryNumEntries(db, "samples") + DatabaseUtils.queryNumEntries(db, "diagnostics")

    override fun enqueueDiagnostic(sessionId: String, json: String) {
        val id = db.insertOrThrow("diagnostics", null, ContentValues().apply {
            put("session_id", sessionId)
            put("json", json)
        })
        check(id > 0) { text(R.string.msg_nao_foi_possivel_guardar_o_diagnostico) }
    }

    override fun nextDiagnostics(limit: Int): List<QueuedDiagnostic> =
        db.rawQuery("SELECT id, session_id, json FROM diagnostics ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(QueuedDiagnostic(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    override fun removeDiagnostics(ids: List<Long>) {
        if (ids.isNotEmpty()) db.delete("diagnostics", "id IN (${ids.joinToString(",")})", null)
    }

    override fun enqueueLog(json: String) {
        db.insert("logs", null, ContentValues().apply { put("json", json) })
        if (++logInserts % TRIM_EVERY == 0) db.execSQL("DELETE FROM logs WHERE id <= (SELECT MAX(id) FROM logs) - $MAX_LOGS")
    }

    override fun nextLogs(limit: Int): List<QueuedLog> =
        db.rawQuery("SELECT id, json FROM logs ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { c ->
            buildList { while (c.moveToNext()) add(QueuedLog(c.getLong(0), c.getString(1))) }
        }

    override fun removeLogs(ids: List<Long>) {
        if (ids.isEmpty()) return
        db.delete("logs", "id IN (${ids.joinToString(",")})", null)
    }

    private fun setRegistered(sessionId: String, value: Int) {
        db.update("sessions", ContentValues().apply { put("registered", value) }, "id = ?", arrayOf(sessionId))
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "upload-queue.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, started_at INTEGER NOT NULL, vehicle TEXT NOT NULL DEFAULT '', registered INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, t INTEGER NOT NULL, json TEXT NOT NULL)")
            createLogs(db)
            createDiagnostics(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createLogs(db)
            if (oldVersion < 3) createDiagnostics(db)
            if (oldVersion < 4) db.execSQL("ALTER TABLE sessions ADD COLUMN vehicle TEXT NOT NULL DEFAULT ''")
        }

        private fun createDiagnostics(db: SQLiteDatabase) = db.execSQL(
            "CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, json TEXT NOT NULL)",
        )

        private fun createLogs(db: SQLiteDatabase) = db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL)")
    }

    private companion object {
        const val MAX_LOGS = 20_000
        const val TRIM_EVERY = 200
    }
}
