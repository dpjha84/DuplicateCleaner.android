package com.example.duplicatecleaner.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer

/**
 * Remembers each photo's signature so a rescan only decodes new or changed photos.
 * An entry is valid while the file's size and modified time are unchanged. Photos that could not
 * be hashed (corrupt, too small, blank) are remembered too, so they are not retried every scan.
 */
class HashCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "photo_signatures.db", null, VERSION) {

    class Entry(val size: Long, val modified: Long, val signature: PhotoSignature?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sig(path TEXT PRIMARY KEY, size INTEGER NOT NULL, modified INTEGER NOT NULL, " +
                "bits BLOB, color INTEGER NOT NULL, w INTEGER NOT NULL, h INTEGER NOT NULL)"
        )
    }

    /** A new algorithm version makes old signatures meaningless, so start over. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS sig")
        onCreate(db)
    }

    fun loadAll(): HashMap<String, Entry> {
        val out = HashMap<String, Entry>()
        readableDatabase.query("sig", null, null, null, null, null, null).use { c ->
            val iPath = c.getColumnIndexOrThrow("path")
            val iSize = c.getColumnIndexOrThrow("size")
            val iMod = c.getColumnIndexOrThrow("modified")
            val iBits = c.getColumnIndexOrThrow("bits")
            val iColor = c.getColumnIndexOrThrow("color")
            val iW = c.getColumnIndexOrThrow("w")
            val iH = c.getColumnIndexOrThrow("h")
            while (c.moveToNext()) {
                val blob = if (c.isNull(iBits)) null else c.getBlob(iBits)
                val sig = if (blob != null && blob.size == PhotoHash.WORDS * 8) {
                    val longs = LongArray(PhotoHash.WORDS)
                    ByteBuffer.wrap(blob).asLongBuffer().get(longs)
                    PhotoSignature(longs, c.getLong(iColor), c.getInt(iW), c.getInt(iH))
                } else null
                out[c.getString(iPath)] = Entry(c.getLong(iSize), c.getLong(iMod), sig)
            }
        }
        return out
    }

    fun putAll(items: List<Pair<String, Entry>>) {
        if (items.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((path, e) in items) {
                val v = ContentValues(7)
                v.put("path", path)
                v.put("size", e.size)
                v.put("modified", e.modified)
                val s = e.signature
                if (s != null) {
                    val buf = ByteBuffer.allocate(PhotoHash.WORDS * 8)
                    buf.asLongBuffer().put(s.bits)
                    v.put("bits", buf.array())
                    v.put("color", s.color)
                    v.put("w", s.width)
                    v.put("h", s.height)
                } else {
                    v.putNull("bits")
                    val zeroLong: Long = 0L
                    val zeroInt: Int = 0
                    v.put("color", zeroLong)
                    v.put("w", zeroInt)
                    v.put("h", zeroInt)
                }
                db.insertWithOnConflict("sig", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Drops remembered photos that are no longer part of the library. */
    fun retainOnly(paths: Set<String>) {
        val db = writableDatabase
        val stale = ArrayList<String>()
        db.query("sig", arrayOf("path"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) c.getString(0).let { if (it !in paths) stale += it }
        }
        if (stale.isEmpty()) return
        db.beginTransaction()
        try {
            for (p in stale) db.delete("sig", "path = ?", arrayOf(p))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        /** Bump whenever PhotoHash changes how signatures are computed. */
        const val VERSION = 1
    }
}
