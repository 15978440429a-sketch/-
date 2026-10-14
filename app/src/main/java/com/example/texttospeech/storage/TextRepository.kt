package com.example.texttospeech.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.example.texttospeech.model.PlaybackMode
import com.example.texttospeech.model.Playlist
import com.example.texttospeech.model.SavedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextRepository(context: Context) {
    data class Draft(
        val text: String,
        val playbackMode: PlaybackMode,
        val selectedDocumentId: Long?,
        val selectedPlaylistId: Long?,
        val playsPerItem: Int,
        val revision: Long
    )

    private val resolver = context.applicationContext.contentResolver

    suspend fun loadDraft(): Draft = withContext(Dispatchers.IO) {
        resolver.query(TextStoreContract.DRAFT_URI, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val selectedDocumentIndex =
                    cursor.getColumnIndexOrThrow(TextStoreContract.SELECTED_DOCUMENT_ID)
                val selectedPlaylistIndex =
                    cursor.getColumnIndexOrThrow(TextStoreContract.SELECTED_PLAYLIST_ID)
                return@withContext Draft(
                    text = cursor.getString(cursor.getColumnIndexOrThrow(TextStoreContract.TEXT)),
                    playbackMode = PlaybackMode.fromStored(
                        cursor.getString(cursor.getColumnIndexOrThrow(TextStoreContract.PLAYBACK_MODE))
                    ),
                    selectedDocumentId = if (cursor.isNull(selectedDocumentIndex)) null
                    else cursor.getLong(selectedDocumentIndex),
                    selectedPlaylistId = if (cursor.isNull(selectedPlaylistIndex)) null
                    else cursor.getLong(selectedPlaylistIndex),
                    playsPerItem = cursor.getInt(
                        cursor.getColumnIndexOrThrow(TextStoreContract.PLAYS_PER_ITEM)
                    ).coerceIn(1, 99),
                    revision = cursor.getLong(cursor.getColumnIndexOrThrow(TextStoreContract.REVISION))
                )
            }
        }
        Draft("", PlaybackMode.SEQUENTIAL, null, null, 1, 0)
    }

    suspend fun loadDocuments(): List<SavedText> = withContext(Dispatchers.IO) {
        buildList {
            resolver.query(TextStoreContract.DOCUMENTS_URI, null, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(TextStoreContract.ID)
                val titleIndex = cursor.getColumnIndexOrThrow(TextStoreContract.TITLE)
                val textIndex = cursor.getColumnIndexOrThrow(TextStoreContract.TEXT)
                val updatedIndex = cursor.getColumnIndexOrThrow(TextStoreContract.UPDATED_AT)
                val audioIndex = cursor.getColumnIndexOrThrow(TextStoreContract.OFFLINE_AUDIO_PATH)
                val voiceIndex = cursor.getColumnIndexOrThrow(TextStoreContract.OFFLINE_VOICE_ID)
                val hashIndex = cursor.getColumnIndexOrThrow(TextStoreContract.OFFLINE_TEXT_HASH)
                while (cursor.moveToNext()) {
                    add(
                        SavedText(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex),
                            text = cursor.getString(textIndex),
                            updatedAt = cursor.getLong(updatedIndex),
                            offlineAudioPath = cursor.stringOrNull(audioIndex),
                            offlineVoiceId = cursor.stringOrNull(voiceIndex),
                            offlineTextHash = cursor.stringOrNull(hashIndex)
                        )
                    )
                }
            }
        }
    }

    suspend fun loadPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        val items = mutableMapOf<Long, MutableList<Pair<Int, Long>>>()
        resolver.query(TextStoreContract.PLAYLIST_ITEMS_URI, null, null, null, null)?.use { cursor ->
            val playlistIndex = cursor.getColumnIndexOrThrow(TextStoreContract.PLAYLIST_ID)
            val documentIndex = cursor.getColumnIndexOrThrow(TextStoreContract.DOCUMENT_ID)
            val positionIndex = cursor.getColumnIndexOrThrow(TextStoreContract.POSITION)
            while (cursor.moveToNext()) {
                items.getOrPut(cursor.getLong(playlistIndex)) { mutableListOf() }
                    .add(cursor.getInt(positionIndex) to cursor.getLong(documentIndex))
            }
        }
        buildList {
            resolver.query(TextStoreContract.PLAYLISTS_URI, null, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(TextStoreContract.ID)
                val nameIndex = cursor.getColumnIndexOrThrow(TextStoreContract.NAME)
                val updatedIndex = cursor.getColumnIndexOrThrow(TextStoreContract.UPDATED_AT)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    add(
                        Playlist(
                            id = id,
                            name = cursor.getString(nameIndex),
                            documentIds = items[id].orEmpty().sortedBy { it.first }.map { it.second },
                            updatedAt = cursor.getLong(updatedIndex)
                        )
                    )
                }
            }
        }
    }

    suspend fun saveDraft(
        text: String,
        playbackMode: PlaybackMode,
        selectedId: Long?,
        selectedPlaylistId: Long?,
        playsPerItem: Int,
        revision: Long
    ) = withContext(Dispatchers.IO) {
        saveDraftBlocking(
            text,
            playbackMode,
            selectedId,
            selectedPlaylistId,
            playsPerItem,
            revision
        )
    }

    fun saveDraftBlocking(
        text: String,
        playbackMode: PlaybackMode,
        selectedId: Long?,
        selectedPlaylistId: Long?,
        playsPerItem: Int,
        revision: Long
    ) {
        val values = ContentValues().apply {
            put(TextStoreContract.TEXT, text)
            put(TextStoreContract.PLAYBACK_MODE, playbackMode.storedValue)
            putNullableLong(TextStoreContract.SELECTED_DOCUMENT_ID, selectedId)
            putNullableLong(TextStoreContract.SELECTED_PLAYLIST_ID, selectedPlaylistId)
            put(TextStoreContract.PLAYS_PER_ITEM, playsPerItem.coerceIn(1, 99))
            put(TextStoreContract.REVISION, revision)
        }
        resolver.update(TextStoreContract.DRAFT_URI, values, null, null)
    }

    suspend fun saveDocument(
        id: Long?,
        title: String,
        text: String,
        keepOfflineAudio: Boolean
    ): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(TextStoreContract.TITLE, title.trim().take(60))
            put(TextStoreContract.TEXT, text)
            put(TextStoreContract.UPDATED_AT, System.currentTimeMillis())
            if (!keepOfflineAudio) {
                putNull(TextStoreContract.OFFLINE_AUDIO_PATH)
                putNull(TextStoreContract.OFFLINE_VOICE_ID)
                putNull(TextStoreContract.OFFLINE_TEXT_HASH)
            }
        }
        if (id == null) {
            val uri = checkNotNull(resolver.insert(TextStoreContract.DOCUMENTS_URI, values))
            ContentUris.parseId(uri)
        } else {
            val uri = ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, id)
            check(resolver.update(uri, values, null, null) == 1) { "Document no longer exists" }
            id
        }
    }

    suspend fun attachOfflineAudio(
        documentId: Long,
        path: String,
        voiceId: String,
        textHash: String
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(TextStoreContract.OFFLINE_AUDIO_PATH, path)
            put(TextStoreContract.OFFLINE_VOICE_ID, voiceId)
            put(TextStoreContract.OFFLINE_TEXT_HASH, textHash)
            put(TextStoreContract.UPDATED_AT, System.currentTimeMillis())
        }
        val uri = ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, documentId)
        check(resolver.update(uri, values, null, null) == 1) { "Document no longer exists" }
    }

    suspend fun clearOfflineAudio(documentId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            putNull(TextStoreContract.OFFLINE_AUDIO_PATH)
            putNull(TextStoreContract.OFFLINE_VOICE_ID)
            putNull(TextStoreContract.OFFLINE_TEXT_HASH)
        }
        resolver.update(
            ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, documentId),
            values,
            null,
            null
        )
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, id), null, null)
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(TextStoreContract.NAME, name.trim().take(40))
            put(TextStoreContract.UPDATED_AT, System.currentTimeMillis())
        }
        ContentUris.parseId(checkNotNull(resolver.insert(TextStoreContract.PLAYLISTS_URI, values)))
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(TextStoreContract.PLAYLISTS_URI, id), null, null)
    }

    suspend fun addToPlaylist(playlist: Playlist, documentId: Long) =
        withContext(Dispatchers.IO) {
            if (documentId in playlist.documentIds) return@withContext
            val values = ContentValues().apply {
                put(TextStoreContract.PLAYLIST_ID, playlist.id)
                put(TextStoreContract.DOCUMENT_ID, documentId)
                put(TextStoreContract.POSITION, playlist.documentIds.size)
            }
            resolver.insert(TextStoreContract.PLAYLIST_ITEMS_URI, values)
        }

    suspend fun removeFromPlaylist(playlistId: Long, documentId: Long) =
        withContext(Dispatchers.IO) {
            resolver.delete(
                TextStoreContract.PLAYLIST_ITEMS_URI,
                "${TextStoreContract.PLAYLIST_ID} = ? AND ${TextStoreContract.DOCUMENT_ID} = ?",
                arrayOf(playlistId.toString(), documentId.toString())
            )
        }
}

private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}
