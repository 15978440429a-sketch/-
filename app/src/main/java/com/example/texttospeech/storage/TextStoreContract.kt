package com.example.texttospeech.storage

import android.net.Uri
import android.provider.BaseColumns

object TextStoreContract {
    const val AUTHORITY = "com.example.texttospeech.textstore"
    const val DRAFT_TABLE = "draft"
    const val DOCUMENTS_TABLE = "documents"

    val DRAFT_URI: Uri = Uri.parse("content://$AUTHORITY/$DRAFT_TABLE")
    val DOCUMENTS_URI: Uri = Uri.parse("content://$AUTHORITY/$DOCUMENTS_TABLE")

    const val ID = BaseColumns._ID
    const val TEXT = "text"
    const val TITLE = "title"
    const val UPDATED_AT = "updated_at"
    const val PLAYBACK_MODE = "playback_mode"
    const val SELECTED_DOCUMENT_ID = "selected_document_id"
    const val REVISION = "revision"
}
