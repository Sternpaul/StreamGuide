package com.sternpaul.streamguide.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sternpaul.streamguide.core.Program

class EpgDatabase(context: Context) : SQLiteOpenHelper(context, "streamguide_epg.db", null, 1) {
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
