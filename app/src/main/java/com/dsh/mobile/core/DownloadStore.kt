package com.dsh.mobile.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap

/** Index only files created by Pal or explicitly picked by the user. Never scan Downloads. */
class DownloadStore private constructor(private val context: Context) : SQLiteOpenHelper(context, "downloads.db", null, 1) {
    data class File(val id: Long, val uri: String, val name: String, val mime: String, val bytes: Long,
                    val time: Long, val session: String, val conversation: String, val added: Long)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE files(id INTEGER PRIMARY KEY AUTOINCREMENT,uri TEXT UNIQUE NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,bytes INTEGER NOT NULL,time INTEGER NOT NULL,session TEXT NOT NULL,conversation TEXT NOT NULL,added INTEGER NOT NULL,pending INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private val active = mutableSetOf<Long>()
    private var recovered = false
    @Synchronized private fun recover() {
        if (recovered) return
        recovered = true
        readableDatabase.rawQuery("SELECT id,uri FROM files WHERE pending=1", null).use { c ->
            while(c.moveToNext()) if(c.getLong(0) !in active) runCatching {
                context.contentResolver.delete(Uri.parse(c.getString(1)), null, null)
                writableDatabase.delete("files", "id=?", arrayOf(c.getLong(0).toString()))
            }
        }
    }
    fun mime(name: String, supplied: String): String = supplied.substringBefore(';').takeIf {
        it != "application/octet-stream" && it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+"))
    } ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    @Synchronized fun begin(name: String, mime: String, session: String, conversation: String): File {
        check(Build.VERSION.SDK_INT >= 29)
        recover()
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("Download destination unavailable")
        return try { add(uri, name, mime, 0, 0, session, conversation, true).also { active.add(it.id) } }
        catch(t: Throwable) { context.contentResolver.delete(uri, null, null); throw t }
    }
    @Synchronized fun trackDestination(uri: Uri, name: String, mime: String, session: String, conversation: String): File {
        recover()
        return add(uri, name, mime, 0, 0, session, conversation, true).also { active.add(it.id) }
    }
    private fun add(uri: Uri, name: String, mime: String, bytes: Long, time: Long, session: String, conversation: String, pending: Boolean): File {
        val added = System.currentTimeMillis()
        val id = writableDatabase.insertOrThrow("files", null, ContentValues().apply {
            put("uri", uri.toString()); put("name", name); put("mime", mime); put("bytes", bytes)
            put("time", time); put("session", session); put("conversation", conversation); put("added", added); put("pending", if(pending) 1 else 0)
        })
        return File(id, uri.toString(), name, mime, bytes, time, session, conversation, added)
    }
    @Synchronized fun complete(file: File, bytes: Long): File {
        check(file.id in active)
        val uri = Uri.parse(file.uri)
        if(Build.VERSION.SDK_INT >= 29 && uri.authority == MediaStore.AUTHORITY) {
            check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0)
        }
        var name = file.name
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if(it.moveToFirst()) name=it.getString(0) ?: name }
        val time=System.currentTimeMillis()
        writableDatabase.update("files", ContentValues().apply { put("bytes", bytes); put("time", time); put("name", name); put("pending", 0) }, "id=?", arrayOf(file.id.toString()))
        active.remove(file.id)
        return file.copy(name=name, bytes=bytes, time=time)
    }
    @Synchronized fun abort(file: File) {
        active.remove(file.id)
        // A failed removal remains pending for a later recovery, never shown as a completed file.
        runCatching {
            context.contentResolver.delete(Uri.parse(file.uri), null, null)
            writableDatabase.delete("files", "id=?", arrayOf(file.id.toString()))
        }.onFailure { recovered=false }
    }
    @Synchronized fun list(): List<File> {
        recover()
        return readableDatabase.rawQuery("SELECT id,uri,name,mime,bytes,time,session,conversation,added FROM files WHERE pending=0", null).use { c ->
            buildList { while(c.moveToNext()) add(File(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getString(6),c.getString(7),c.getLong(8))) }
        }
    }
    @Synchronized fun importFile(uri: Uri): Boolean {
        require(uri.scheme == "content")
        recover()
        readableDatabase.rawQuery("SELECT id FROM files WHERE uri=?", arrayOf(uri.toString())).use { if(it.moveToFirst()) return false }
        var name="文件"; var size=0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            check(it.moveToFirst()); name=it.getString(0) ?: name; if(!it.isNull(1)) size=it.getLong(1)
        }
        add(uri,name,context.contentResolver.getType(uri) ?: mime(name,""),size,0,"","",false)
        return true
    }
    @Synchronized fun delete(file: File): Boolean = runCatching {
        val uri=Uri.parse(file.uri)
        // DocumentsProvider supports selected SAF documents; MediaStore supports app-owned rows.
        if(DocumentsContract.isDocumentUri(context, uri)) {
            check(DocumentsContract.deleteDocument(context.contentResolver,uri))
        } else check(context.contentResolver.delete(uri,null,null)>0)
        writableDatabase.delete("files","id=? AND pending=0",arrayOf(file.id.toString()))
        true
    }.getOrElse { error ->
        RunLog.warn("Download", "selected-file deletion refused provider=${Uri.parse(file.uri).authority} reason=${error.javaClass.simpleName}")
        // Remove a stale index only when the provider confirms the file is absent.
        val missing = runCatching { context.contentResolver.query(Uri.parse(file.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null,null,null)?.use { !it.moveToFirst() } ?: false }.getOrDefault(false)
        if(missing) writableDatabase.delete("files","id=? AND pending=0",arrayOf(file.id.toString()))
        missing
    }
    @Synchronized fun forget(files: List<File>) {
        writableDatabase.beginTransaction()
        try { files.forEach {writableDatabase.delete("files","id=? AND pending=0",arrayOf(it.id.toString()))};writableDatabase.setTransactionSuccessful() }
        finally {writableDatabase.endTransaction()}
    }
    fun open(file: File) {
        val intent=Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(file.uri),file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent,"打开文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    companion object {
        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore = instance ?: synchronized(this) { instance ?: DownloadStore(context.applicationContext).also { instance=it } }
    }
}
