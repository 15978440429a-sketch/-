package com.example.texttospeech.storage

import android.net.Uri
import android.provider.BaseColumns

object TextStoreContract {
    const val AUTHORITY = "com.example.texttospeech.textstore"
    const val DRAFT_TABLE = "draft"
    const val DOCUMENTS_TABLE = "documents"
    const val PLAYLISTS_TABLE = "playlists"
    const val PLAYLIST_ITEMS_TABLE = "playlist_items"

    val DRAFT_URI: Uri = Uri.parse("content://$AUTHORITY/$DRAFT_TABLE")
    val DOCUMENTS_URI: Uri = Uri.parse("content://$AUTHORITY/$DOCUMENTS_TABLE")
    val PLAYLISTS_URI: Uri = Uri.parse("content://$AUTHORITY/$PLAYLISTS_TABLE")
    val PLAYLIST_ITEMS_URI: Uri = Uri.parse("content://$AUTHORITY/$PLAYLIST_ITEMS_TABLE")

    const val ID = BaseColumns._ID
    const val TEXT = "text"
    const val TITLE = "title"
    const val UPDATED_AT = "updated_at"
    const val PLAYBACK_MODE = "playback_mode"
    const val SELECTED_DOCUMENT_ID = "selected_document_id"
    const val SELECTED_PLAYLIST_ID = "selected_playlist_id"
    const val PLAYS_PER_ITEM = "plays_per_item"
    const val REVISION = "revision"
    const val OFFLINE_AUDIO_PATH = "offline_audio_path"
    const val OFFLINE_WAV_PATH = "offline_wav_path"
    const val OFFLINE_VOICE_ID = "offline_voice_id"
    const val OFFLINE_TEXT_HASH = "offline_text_hash"
    const val NAME = "name"
    const val PLAYLIST_ID = "playlist_id"
    const val DOCUMENT_ID = "document_id"
    const val POSITION = "position"
}
