/*
 * VLC for Android — online lyrics dialog.
 *
 * Copyright © VideoLAN authors and contributors.
 * This file is licensed under the same terms as the VLC for Android project.
 */
package org.videolan.vlc.gui.audio

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.videolan.vlc.lyrics.LrcParser
import org.videolan.vlc.lyrics.LyricsRepository
import java.util.Locale

private const val ARG_TITLE = "lyrics_title"
private const val ARG_ARTIST = "lyrics_artist"
private const val ARG_DURATION_MS = "lyrics_duration_ms"

class LyricsDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(title: String, artist: String, durationMs: Long) =
            LyricsDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_ARTIST, artist)
                    putLong(ARG_DURATION_MS, durationMs)
                }
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val titleInput = EditText(context).apply {
            hint = "Song title"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(arguments?.getString(ARG_TITLE).orEmpty())
        }
        val artistInput = EditText(context).apply {
            hint = "Artist"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(true)
            setText(arguments?.getString(ARG_ARTIST).orEmpty())
        }
        val lyricsText = TextView(context).apply {
            text = "Search online for lyrics using both the song title and artist."
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, padding, 0, padding)
        }
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(
                lyricsText,
                ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        container.addView(titleInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(artistInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        container.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (300 * density).toInt()
        ))

        val dialog = AlertDialog.Builder(context)
            .setTitle("Online lyrics")
            .setView(container)
            .setNegativeButton("Close", null)
            .setPositiveButton("Search", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = titleInput.text.toString().trim()
                val artist = artistInput.text.toString().trim()
                if (title.isBlank() || artist.isBlank()) {
                    lyricsText.text = "Enter both the song title and artist to search."
                    return@setOnClickListener
                }

                lyricsText.text = "Searching online lyrics…"
                val durationMs = arguments?.getLong(ARG_DURATION_MS, 0L) ?: 0L
                lifecycleScope.launch {
                    val result = LyricsRepository.search(
                        title = title,
                        artist = artist,
                        durationMs = durationMs.takeIf { it > 0L }
                    )
                    if (!isAdded) return@launch
                    lyricsText.text = when {
                        result == null ->
                            "No matching lyrics were found. Check the title and artist, then try again."
                        !result.syncedLyrics.isNullOrBlank() -> {
                            val lines = LrcParser.parse(result.syncedLyrics)
                            if (lines.isNotEmpty()) {
                                lines.joinToString("\n") { line ->
                                    "[${formatTimestamp(line.timestampMs)}] ${line.text}"
                                }
                            } else {
                                result.syncedLyrics
                            }
                        }
                        !result.plainLyrics.isNullOrBlank() -> result.plainLyrics
                        else -> "Lyrics are not available for this track."
                    }
                }
            }
        }
        return dialog
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val totalSeconds = timestampMs.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
