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
                selection,
                selectionArgs,
                null,
                null,
                sortOrder ?: "${TextStoreContract.ID} ASC"
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

            PLAYLISTS -> db.query(
                TextStoreContract.PLAYLISTS_TABLE,
                PLAYLIST_COLUMNS,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder ?: "${TextStoreContract.ID} ASC"
            )

            PLAYLIST -> db.query(
                TextStoreContract.PLAYLISTS_TABLE,
                PLAYLIST_COLUMNS,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString()),
                null,
                null,
                null
            )

            PLAYLIST_ITEMS -> db.query(
                TextStoreContract.PLAYLIST_ITEMS_TABLE,
                PLAYLIST_ITEM_COLUMNS,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
                    ?: "${TextStoreContract.PLAYLIST_ID} ASC, ${TextStoreContract.POSITION} ASC"
            )

            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val safeValues = ContentValues(values ?: ContentValues())
        val (table, baseUri) = when (MATCHER.match(uri)) {
            DOCUMENTS -> TextStoreContract.DOCUMENTS_TABLE to TextStoreContract.DOCUMENTS_URI
            PLAYLISTS -> TextStoreContract.PLAYLISTS_TABLE to TextStoreContract.PLAYLISTS_URI
            PLAYLIST_ITEMS ->
                TextStoreContract.PLAYLIST_ITEMS_TABLE to TextStoreContract.PLAYLIST_ITEMS_URI
            else -> throw IllegalArgumentException("Insert is not supported for $uri")
        }
        val id = database.writableDatabase.insertOrThrow(table, null, safeValues)
        val result = ContentUris.withAppendedId(baseUri, id)
        notifyChanged(baseUri)
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
                val revision = safeValues.getAsLong(TextStoreContract.REVISION) ?: 0L
                database.writableDatabase.update(
                    TextStoreContract.DRAFT_TABLE,
                    safeValues,
                    "${TextStoreContract.ID} = 1 AND ${TextStoreContract.REVISION} <= ?",
                    arrayOf(revision.toString())
                )
            }

            DOCUMENT -> database.writableDatabase.update(
                TextStoreContract.DOCUMENTS_TABLE,
                safeValues,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString())
            )

            PLAYLIST -> database.writableDatabase.update(
                TextStoreContract.PLAYLISTS_TABLE,
                safeValues,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString())
            )

            else -> throw IllegalArgumentException("Update is not supported for $uri")
        }
        if (count > 0) notifyChanged(uri)
        return count
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        val count = when (MATCHER.match(uri)) {
            DOCUMENT -> database.writableDatabase.delete(
                TextStoreContract.DOCUMENTS_TABLE,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString())
            )

            PLAYLIST -> database.writableDatabase.delete(
                TextStoreContract.PLAYLISTS_TABLE,
                "${TextStoreContract.ID} = ?",
                arrayOf(ContentUris.parseId(uri).toString())
            )

            PLAYLIST_ITEMS -> database.writableDatabase.delete(
                TextStoreContract.PLAYLIST_ITEMS_TABLE,
                selection,
                selectionArgs
            )

            else -> throw IllegalArgumentException("Delete is not supported for $uri")
        }
        if (count > 0) notifyChanged(uri)
        return count
    }

    override fun getType(uri: Uri): String = when (MATCHER.match(uri)) {
        DRAFT -> "vnd.android.cursor.item/vnd.${TextStoreContract.AUTHORITY}.draft"
        DOCUMENTS -> "vnd.android.cursor.dir/vnd.${TextStoreContract.AUTHORITY}.document"
        DOCUMENT -> "vnd.android.cursor.item/vnd.${TextStoreContract.AUTHORITY}.document"
        PLAYLISTS -> "vnd.android.cursor.dir/vnd.${TextStoreContract.AUTHORITY}.playlist"
        PLAYLIST -> "vnd.android.cursor.item/vnd.${TextStoreContract.AUTHORITY}.playlist"
        PLAYLIST_ITEMS -> "vnd.android.cursor.dir/vnd.${TextStoreContract.AUTHORITY}.playlist_item"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun notifyChanged(uri: Uri) {
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(uri, null)
        when (MATCHER.match(uri)) {
            DOCUMENT -> resolver.notifyChange(TextStoreContract.DOCUMENTS_URI, null)
            PLAYLIST -> resolver.notifyChange(TextStoreContract.PLAYLISTS_URI, null)
            PLAYLIST_ITEMS -> resolver.notifyChange(TextStoreContract.PLAYLISTS_URI, null)
        }
    }

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "text_library.db", null, DATABASE_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            createDraft(db)
            createDocuments(db)
            createPlaylists(db)
            db.execSQL(
                "INSERT INTO ${TextStoreContract.DRAFT_TABLE} " +
                    "(${TextStoreContract.ID}, ${TextStoreContract.TEXT}, " +
                    "${TextStoreContract.PLAYBACK_MODE}) VALUES (1, '', 'sequential')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    "ALTER TABLE ${TextStoreContract.DRAFT_TABLE} ADD COLUMN " +
                        "${TextStoreContract.SELECTED_PLAYLIST_ID} INTEGER"
                )
                db.execSQL(
                    "ALTER TABLE ${TextStoreContract.DRAFT_TABLE} ADD COLUMN " +
                        "${TextStoreContract.PLAYS_PER_ITEM} INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE ${TextStoreContract.DOCUMENTS_TABLE} ADD COLUMN " +
                        "${TextStoreContract.OFFLINE_AUDIO_PATH} TEXT"
                )
                db.execSQL(
                    "ALTER TABLE ${TextStoreContract.DOCUMENTS_TABLE} ADD COLUMN " +
                        "${TextStoreContract.OFFLINE_VOICE_ID} TEXT"
                )
                db.execSQL(
                    "ALTER TABLE ${TextStoreContract.DOCUMENTS_TABLE} ADD COLUMN " +
                        "${TextStoreContract.OFFLINE_TEXT_HASH} TEXT"
                )
                createPlaylists(db)
            }
        }

        private fun createDraft(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE ${TextStoreContract.DRAFT_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY,
                    ${TextStoreContract.TEXT} TEXT NOT NULL DEFAULT '',
                    ${TextStoreContract.PLAYBACK_MODE} TEXT NOT NULL DEFAULT 'sequential',
                    ${TextStoreContract.SELECTED_DOCUMENT_ID} INTEGER,
                    ${TextStoreContract.SELECTED_PLAYLIST_ID} INTEGER,
                    ${TextStoreContract.PLAYS_PER_ITEM} INTEGER NOT NULL DEFAULT 1,
                    ${TextStoreContract.REVISION} INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        private fun createDocuments(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE ${TextStoreContract.DOCUMENTS_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${TextStoreContract.TITLE} TEXT NOT NULL,
                    ${TextStoreContract.TEXT} TEXT NOT NULL,
                    ${TextStoreContract.UPDATED_AT} INTEGER NOT NULL,
                    ${TextStoreContract.OFFLINE_AUDIO_PATH} TEXT,
                    ${TextStoreContract.OFFLINE_VOICE_ID} TEXT,
                    ${TextStoreContract.OFFLINE_TEXT_HASH} TEXT
                )
                """.trimIndent()
            )
        }

        private fun createPlaylists(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ${TextStoreContract.PLAYLISTS_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${TextStoreContract.NAME} TEXT NOT NULL,
                    ${TextStoreContract.UPDATED_AT} INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ${TextStoreContract.PLAYLIST_ITEMS_TABLE} (
                    ${TextStoreContract.ID} INTEGER PRIMARY KEY AUTOINCREMENT,
                    ${TextStoreContract.PLAYLIST_ID} INTEGER NOT NULL,
                    ${TextStoreContract.DOCUMENT_ID} INTEGER NOT NULL,
                    ${TextStoreContract.POSITION} INTEGER NOT NULL,
                    UNIQUE (${TextStoreContract.PLAYLIST_ID}, ${TextStoreContract.DOCUMENT_ID}),
                    FOREIGN KEY (${TextStoreContract.PLAYLIST_ID}) REFERENCES ${TextStoreContract.PLAYLISTS_TABLE}(${TextStoreContract.ID}) ON DELETE CASCADE,
                    FOREIGN KEY (${TextStoreContract.DOCUMENT_ID}) REFERENCES ${TextStoreContract.DOCUMENTS_TABLE}(${TextStoreContract.ID}) ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    companion object {
        private const val DATABASE_VERSION = 2
        private const val DRAFT = 1
        private const val DOCUMENTS = 2
        private const val DOCUMENT = 3
        private const val PLAYLISTS = 4
        private const val PLAYLIST = 5
        private const val PLAYLIST_ITEMS = 6

        private val DRAFT_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.TEXT,
            TextStoreContract.PLAYBACK_MODE,
            TextStoreContract.SELECTED_DOCUMENT_ID,
            TextStoreContract.SELECTED_PLAYLIST_ID,
            TextStoreContract.PLAYS_PER_ITEM,
            TextStoreContract.REVISION
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.TITLE,
            TextStoreContract.TEXT,
            TextStoreContract.UPDATED_AT,
            TextStoreContract.OFFLINE_AUDIO_PATH,
            TextStoreContract.OFFLINE_VOICE_ID,
            TextStoreContract.OFFLINE_TEXT_HASH
        )
        private val PLAYLIST_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.NAME,
            TextStoreContract.UPDATED_AT
        )
        private val PLAYLIST_ITEM_COLUMNS = arrayOf(
            TextStoreContract.ID,
            TextStoreContract.PLAYLIST_ID,
            TextStoreContract.DOCUMENT_ID,
            TextStoreContract.POSITION
        )
        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.DRAFT_TABLE, DRAFT)
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.DOCUMENTS_TABLE, DOCUMENTS)
            addURI(TextStoreContract.AUTHORITY, "${TextStoreContract.DOCUMENTS_TABLE}/#", DOCUMENT)
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.PLAYLISTS_TABLE, PLAYLISTS)
            addURI(TextStoreContract.AUTHORITY, "${TextStoreContract.PLAYLISTS_TABLE}/#", PLAYLIST)
            addURI(TextStoreContract.AUTHORITY, TextStoreContract.PLAYLIST_ITEMS_TABLE, PLAYLIST_ITEMS)
        }
    }
}
