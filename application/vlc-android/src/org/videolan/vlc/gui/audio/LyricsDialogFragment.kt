/*
 * VLC for Android — online lyrics dialog.
 *
 * Licensed under the same terms as the VLC for Android project.
 */
package org.videolan.vlc.gui.audio

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
import org.videolan.vlc.lyrics.TimedLyricLine
import java.util.Locale

private const val ARG_TITLE = "lyrics_title"
private const val ARG_ARTIST = "lyrics_artist"
private const val ARG_DURATION_MS = "lyrics_duration_ms"

class LyricsDialogFragment : DialogFragment() {

    private var lyricsTextView: TextView? = null
    private var lyricsScrollView: ScrollView? = null
    private var syncedLines: List<TimedLyricLine> = emptyList()
    private var playbackPositionMs: Long = 0L
    private var lastActiveLineIndex = -1

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
            setLineSpacing(0f, 1.15f)
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
        lyricsTextView = lyricsText
        lyricsScrollView = scrollView

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
                syncedLines = emptyList()
                lastActiveLineIndex = -1
                val durationMs = arguments?.getLong(ARG_DURATION_MS, 0L) ?: 0L
                lifecycleScope.launch {
                    val result = LyricsRepository.search(
                        title = title,
                        artist = artist,
                        durationMs = durationMs.takeIf { it > 0L }
                    )
                    if (!isAdded) return@launch
                    when {
                        result == null ->
                            lyricsText.text = "No matching lyrics were found. Check the title and artist, then try again."
                        !result.syncedLyrics.isNullOrBlank() -> {
                            syncedLines = LrcParser.parse(result.syncedLyrics)
                            if (syncedLines.isNotEmpty()) {
                                renderSyncedLyrics()
                            } else {
                                lyricsText.text = result.syncedLyrics
                            }
                        }
                        !result.plainLyrics.isNullOrBlank() -> lyricsText.text = result.plainLyrics
                        else -> lyricsText.text = "Lyrics are not available for this track."
                    }
                }
            }
        }
        return dialog
    }

    /** Called by the audio player as its playback position changes. */
    fun updatePlaybackPosition(positionMs: Long) {
        playbackPositionMs = positionMs.coerceAtLeast(0L)
        if (syncedLines.isNotEmpty() && isAdded) renderSyncedLyrics()
    }

    private fun renderSyncedLyrics() {
        val textView = lyricsTextView ?: return
        val builder = SpannableStringBuilder()
        var activeLineIndex = -1

        syncedLines.forEachIndexed { index, line ->
            val nextLineTime = syncedLines.getOrNull(index + 1)?.timestampMs
            val isActiveLine = playbackPositionMs >= line.timestampMs &&
                (nextLineTime == null || playbackPositionMs < nextLineTime)
            if (isActiveLine) activeLineIndex = index

            val prefix = "[${formatTimestamp(line.timestampMs)}] "
            builder.append(prefix)
            val textStart = builder.length
            builder.append(line.text)
            val textEnd = builder.length

            if (isActiveLine && textEnd > textStart) {
                builder.setSpan(
                    StyleSpan(Typeface.BOLD),
                    textStart,
                    textEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builder.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    textStart,
                    textEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (line.words.isNotEmpty()) {
                    var wordStart = textStart
                    line.words.forEachIndexed { wordIndex, word ->
                        val wordEnd = (wordStart + word.text.length).coerceAtMost(textEnd)
                        val nextWordTime = line.words.getOrNull(wordIndex + 1)?.timestampMs
                        val isActiveWord = playbackPositionMs >= word.timestampMs &&
                            (nextWordTime == null || playbackPositionMs < nextWordTime)
                        if (isActiveWord && wordEnd > wordStart) {
                            builder.setSpan(
                                BackgroundColorSpan(0x8848A8FF.toInt()),
                                wordStart,
                                wordEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            builder.setSpan(
                                StyleSpan(Typeface.BOLD),
                                wordStart,
                                wordEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        wordStart = wordEnd
                    }
                }
            }
            builder.append('\n')
        }
        textView.text = builder

        if (activeLineIndex != lastActiveLineIndex && activeLineIndex >= 0) {
            lastActiveLineIndex = activeLineIndex
            val activeOffset = syncedLines.take(activeLineIndex).sumOf {
                "[${formatTimestamp(it.timestampMs)}] ".length + it.text.length + 1
            }
            textView.post {
                val layout = textView.layout ?: return@post
                val lineNumber = layout.getLineForOffset(activeOffset.coerceAtMost(
                    (textView.text?.length ?: 0).coerceAtLeast(1) - 1
                ))
                lyricsScrollView?.smoothScrollTo(0, layout.getLineTop(lineNumber))
            }
        }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        val totalSeconds = timestampMs.coerceAtLeast(0L) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        lyricsTextView = null
        lyricsScrollView = null
        super.onDestroyView()
    }
}
