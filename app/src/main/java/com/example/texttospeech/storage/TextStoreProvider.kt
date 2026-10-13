package com.example.texttospeech.storage

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

class TextStoreProvider : ContentProvider() {
    private lateinit var database: Database

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        database = Database(providerContext)
        database.writableDatabase
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val db = database.readableDatabase
        val cursor = when (MATCHER.match(uri)) {
            DRAFT -> db.query(
                TextStoreContract.DRAFT_TABLE,
                DRAFT_COLUMNS,
                "${TextStoreContract.ID} = 1",
                null,
                null,
                null,
                null
            )

            DOCUMENTS -> db.query(
                TextStoreContract.DOCUMENTS_TABLE,
                DOCUMENT_COLUMNS,
                null,
                null,
                null,
                null,
                "${TextStoreContract.ID} ASC"
            )

            DOCUMENT -> db.query(
                TextStoreContract.DOCUMENTS_TABLE,
                DOCUMENT_COLUMNS,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString()),
                null,
                null,
                null
            )

            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (MATCHER.match(uri) != DOCUMENTS) {
            throw IllegalArgumentException("Insert is only supported for documents")
        }
        val id = database.writableDatabase.insertOrThrow(
            TextStoreContract.DOCUMENTS_TABLE,
            null,
            ContentValues(values ?: ContentValues())
        )
        val result = ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, id)
        notifyChanged(result)
        return result
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val safeValues = ContentValues(values ?: ContentValues())
        val count = when (MATCHER.match(uri)) {
            DRAFT -> {
                val db = database.writableDatabase
                val revision = safeValues.getAsLong(TextStoreContract.REVISION) ?: 0L
                val updated = db.update(
                    TextStoreContract.DRAFT_TABLE,
                    safeValues,
                    "${TextStoreContract.ID} = 1 AND ${TextStoreContract.REVISION} <= ?",
                    arrayOf(revision.toString())
                )
                updated
            }

            DOCUMENT -> database.writableDatabase.update(
                TextStoreContract.DOCUMENTS_TABLE,
                safeValues,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString())
            )

            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        if (count > 0) notifyChanged(uri)
        return count
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        if (MATCHER.match(uri) != DOCUMENT) {
            throw IllegalArgumentException("Delete is only supported for one document")
        }
        val count = database.writableDatabase.delete(
            TextStoreContract.DOCUMENTS_TABLE,
            "${TextStoreContract.ID} = ?",
            arrayOf(ContentUris.parseId(uri).toString())
        )
        if (count > 0) notifyChanged(uri)
        return count
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        DRAFT -> "vnd.android.cursor.item/vnd.${TextStoreContract.AUTHORITY}.draft"
        DOCUMENTS -> "vnd.android.cursor.dir/vnd.${TextStoreContract.AUTHORITY}.document"
        DOCUMENT -> "vnd.android.cursor.item/vnd.${TextStoreContract.AUTHORITY}.document"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun notifyChanged(uri: Uri) {
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(uri, null)
        if (MATCHER.match(uri) == DOCUMENT) {
            resolver.notifyChange(TextStoreContract.DOCUMENTS_URI, null)
        }
    }

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "text_library.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE ${TextStoreContract.DRAFT_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY,
                    ${TextStoreContract.TEXT} TEXT NOT NULL DEFAULT '',
                    ${TextStoreContract.PLAYBACK_MODE} TEXT NOT NULL DEFAULT 'sequential',
                    ${TextStoreContract.SELECTED_DOCUMENT_ID} INTEGER,
                    ${TextStoreContract.REVISION} INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE ${TextStoreContract.DOCUMENTS_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${TextStoreContract.TITLE} TEXT NOT NULL,
                    ${TextStoreContract.TEXT} TEXT NOT NULL,
                    ${TextStoreContract.UPDATED_AT} INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO ${TextStoreContract.DRAFT_TABLE} " +
                    "(${TextStoreContract.ID}, ${TextStoreContract.TEXT}, ${TextStoreContract.PLAYBACK_MODE}) " +
                    "VALUES (1, '', 'sequential')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        private const val DRAFT = 1
        private const val DOCUMENTS = 2
        private const val DOCUMENT = 3

        private val DRAFT_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.TEXT,
            TextStoreContract.PLAYBACK_MODE,
            TextStoreContract.SELECTED_DOCUMENT_ID,
            TextStoreContract.REVISION
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.TITLE,
            TextStoreContract.TEXT,
            TextStoreContract.UPDATED_AT
        )
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.DRAFT_TABLE, DRAFT)
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.DOCUMENTS_TABLE, DOCUMENTS)
            addURI(TextStoreContract.AUTHORITY, "${TextStoreContract.DOCUMENTS_TABLE}/#", DOCUMENT)
        }
    }
}
