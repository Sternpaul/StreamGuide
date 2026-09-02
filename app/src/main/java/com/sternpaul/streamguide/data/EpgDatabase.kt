package com.sternpaul.streamguide.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sternpaul.streamguide.core.Program

data class EpgStorageSnapshot(
    val countsByChannelId: Map<String, Int>,
    val totalPrograms: Int,
    val currentlyAiringPrograms: Int,
    val upcomingPrograms24h: Int,
    val guideStartEpochMs: Long,
    val guideEndEpochMs: Long
)

class EpgDatabase(context: Context) : SQLiteOpenHelper(context, "streamguide_epg.db", null, 1) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.execSQL("PRAGMA synchronous=NORMAL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE programs (channel_id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX programs_channel_time ON programs(channel_id, start_ms, end_ms)")
        db.execSQL("CREATE INDEX programs_time ON programs(start_ms, end_ms)")
        db.execSQL("CREATE TABLE programs_staging (channel_id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun replacePrograms(programs: Sequence<Program>): Int = replacePrograms { emit -> programs.forEach(emit) }

    fun replacePrograms(producer: ((Program) -> Unit) -> Unit): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("programs_staging", null, null)
            val statement = db.compileStatement("INSERT INTO programs_staging(channel_id,title,description,start_ms,end_ms) VALUES(?,?,?,?,?)")
            var count = 0
            producer { program ->
                statement.clearBindings()
                statement.bindString(1, program.channelId)
                statement.bindString(2, program.title)
                statement.bindString(3, program.description)
                statement.bindLong(4, program.startEpochMs)
                statement.bindLong(5, program.endEpochMs)
                statement.executeInsert()
                count++
            }
            require(count > 0) { "EPG contained no valid programmes" }
            db.delete("programs", null, null)
            db.execSQL("INSERT INTO programs SELECT channel_id,title,description,start_ms,end_ms FROM programs_staging")
            db.delete("programs_staging", null, null)
            db.setTransactionSuccessful()
            return count
        } finally {
            db.endTransaction()
        }
    }

    fun query(channelIds: Collection<String>, startMs: Long, endMs: Long, limit: Int = 5_000): List<Program> {
        val ids = channelIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val args = (ids + listOf(endMs.toString(), startMs.toString(), limit.toString())).toTypedArray()
        val sql = "SELECT channel_id,title,description,start_ms,end_ms FROM programs WHERE channel_id IN ($placeholders) AND start_ms < ? AND end_ms > ? ORDER BY start_ms LIMIT ?"
        return readableDatabase.rawQuery(sql, args).use(::readPrograms)
    }

    fun all(limit: Int = Int.MAX_VALUE): List<Program> = readableDatabase.rawQuery(
        "SELECT channel_id,title,description,start_ms,end_ms FROM programs ORDER BY start_ms LIMIT ?",
        arrayOf(limit.toString())
    ).use(::readPrograms)

    fun searchChannelIds(query: String, limit: Int = 10_000): Set<String> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return emptySet()
        val escaped = normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return readableDatabase.rawQuery(
            "SELECT DISTINCT channel_id FROM programs WHERE title LIKE ? ESCAPE '\\' COLLATE NOCASE LIMIT ?",
            arrayOf("%$escaped%", limit.toString())
        ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM programs", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun distinctChannelIds(): Set<String> = readableDatabase.rawQuery("SELECT DISTINCT channel_id FROM programs", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    fun diagnostics(nowEpochMs: Long): EpgStorageSnapshot {
        val counts = readableDatabase.rawQuery(
            "SELECT channel_id, COUNT(*) FROM programs GROUP BY channel_id",
            null
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1)) } }
        val end24h = nowEpochMs + 24L * 3_600_000L
        return readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(start_ms), MAX(end_ms), " +
                "SUM(CASE WHEN start_ms <= ? AND end_ms > ? THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN start_ms > ? AND start_ms <= ? THEN 1 ELSE 0 END) FROM programs",
            arrayOf(nowEpochMs.toString(), nowEpochMs.toString(), nowEpochMs.toString(), end24h.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) EpgStorageSnapshot(counts, 0, 0, 0, 0, 0)
            else EpgStorageSnapshot(
                countsByChannelId = counts,
                totalPrograms = cursor.getInt(0),
                guideStartEpochMs = if (cursor.isNull(1)) 0 else cursor.getLong(1),
                guideEndEpochMs = if (cursor.isNull(2)) 0 else cursor.getLong(2),
                currentlyAiringPrograms = cursor.getInt(3),
                upcomingPrograms24h = cursor.getInt(4)
            )
        }
    }

    fun clear() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("programs", null, null)
            writableDatabase.delete("programs_staging", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun readPrograms(cursor: Cursor): List<Program> = buildList {
        while (cursor.moveToNext()) add(Program(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getLong(4)))
    }
}
