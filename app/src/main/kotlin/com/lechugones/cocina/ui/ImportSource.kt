package com.lechugones.cocina.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/** What another app handed us to import (SPEC § 4.3, inputs). */
sealed interface ImportSource {
    data class Files(
        val uris: List<Uri>,
    ) : ImportSource

    data class Text(
        val text: String,
    ) : ImportSource
}

/** Holds an import request until the import screen picks it up. */
@Singleton
class IncomingImports
    @Inject
    constructor() {
        private val pendingSource = MutableStateFlow<ImportSource?>(null)
        val pending: StateFlow<ImportSource?> = pendingSource.asStateFlow()

        fun submit(source: ImportSource) {
            pendingSource.value = source
        }

        fun consume(): ImportSource? = pendingSource.getAndUpdate { null }

        /** Extracts an import request from a share (`SEND`, `SEND_MULTIPLE`) or `VIEW` intent. */
        fun submitFrom(intent: Intent?): Boolean {
            val source = fromIntent(intent ?: return false) ?: return false
            submit(source)
            return true
        }

        private fun fromIntent(intent: Intent): ImportSource? =
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    when {
                        stream != null -> ImportSource.Files(listOf(stream))
                        !text.isNullOrBlank() -> ImportSource.Text(text)
                        else -> null
                    }
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    IntentCompat
                        .getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { ImportSource.Files(it) }
                }

                Intent.ACTION_VIEW -> {
                    intent.data?.let { ImportSource.Files(listOf(it)) }
                }

                else -> {
                    null
                }
            }
    }
