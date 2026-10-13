package com.example.texttospeech.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import com.example.texttospeech.model.PlaybackMode
import com.example.texttospeech.model.SavedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextRepository(context: Context) {
    data class Draft(
        val text: String,
        val playbackMode: PlaybackMode,
        val selectedDocumentId: Long?,
        val revision: Long
    )

    private val resolver = context.applicationContext.contentResolver

    suspend fun loadDraft(): Draft = withContext(Dispatchers.IO) {
        resolver.query(TextStoreContract.DRAFT_URI, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val selectedIndex = cursor.getColumnIndexOrThrow(TextStoreContract.SELECTED_DOCUMENT_ID)
                return@withContext Draft(
                    text = cursor.getString(cursor.getColumnIndexOrThrow(TextStoreContract.TEXT)),
                    playbackMode = PlaybackMode.fromStored(
                        cursor.getString(cursor.getColumnIndexOrThrow(TextStoreContract.PLAYBACK_MODE))
                    ),
                    selectedDocumentId = if (cursor.isNull(selectedIndex)) null else cursor.getLong(selectedIndex),
                    revision = cursor.getLong(cursor.getColumnIndexOrThrow(TextStoreContract.REVISION))
                )
            }
        }
        Draft("", PlaybackMode.SEQUENTIAL, null, 0)
    }

    suspend fun loadDocuments(): List<SavedText> = withContext(Dispatchers.IO) {
        buildList {
            resolver.query(TextStoreContract.DOCUMENTS_URI, null, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(TextStoreContract.ID)
                val titleIndex = cursor.getColumnIndexOrThrow(TextStoreContract.TITLE)
                val textIndex = cursor.getColumnIndexOrThrow(TextStoreContract.TEXT)
                val updatedIndex = cursor.getColumnIndexOrThrow(TextStoreContract.UPDATED_AT)
                while (cursor.moveToNext()) {
                    add(
                        SavedText(
                            id = cursor.getLong(idIndex),
                            title = cursor.getString(titleIndex),
                            text = cursor.getString(textIndex),
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
        revision: Long
    ) =
        withContext(Dispatchers.IO) {
            saveDraftBlocking(text, playbackMode, selectedId, revision)
        }

    fun saveDraftBlocking(
        text: String,
        playbackMode: PlaybackMode,
        selectedId: Long?,
        revision: Long
    ) {
        val values = ContentValues().apply {
            put(TextStoreContract.TEXT, text)
            put(TextStoreContract.PLAYBACK_MODE, playbackMode.storedValue)
            if (selectedId == null) putNull(TextStoreContract.SELECTED_DOCUMENT_ID)
            else put(TextStoreContract.SELECTED_DOCUMENT_ID, selectedId)
            put(TextStoreContract.REVISION, revision)
        }
        resolver.update(TextStoreContract.DRAFT_URI, values, null, null)
    }

    suspend fun saveDocument(id: Long?, text: String): Long = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(TextStoreContract.TITLE, makeTitle(text))
            put(TextStoreContract.TEXT, text)
            put(TextStoreContract.UPDATED_AT, System.currentTimeMillis())
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

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(TextStoreContract.DOCUMENTS_URI, id), null, null)
    }

    private fun makeTitle(text: String): String {
        val firstContentLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: "未命名文稿"
        return if (firstContentLine.length <= 24) firstContentLine else firstContentLine.take(24) + "…"
    }
}
