package com.henrylumis.mediaprayer.ui.common

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.henrylumis.mediaprayer.R
import com.henrylumis.mediaprayer.data.Song
import com.henrylumis.mediaprayer.util.TagEditor
import java.util.concurrent.Executors

/** Full metadata editor. File writes are isolated from playback and require the
 * Android system's media-write consent when the file belongs to another app. */
class TagEditorActivity : AppCompatActivity() {
    private lateinit var title: EditText
    private lateinit var artist: EditText
    private lateinit var album: EditText
    private lateinit var albumArtist: EditText
    private lateinit var genre: EditText
    private lateinit var year: EditText
    private lateinit var track: EditText
    private lateinit var disc: EditText
    private lateinit var comment: EditText

    private val executor = Executors.newSingleThreadExecutor()
    private var pending: TagEditor.Result? = null
    private lateinit var mediaUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaUri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: run { finish(); return })
        val songTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val songArtist = intent.getStringExtra(EXTRA_ARTIST).orEmpty()
        val songAlbum = intent.getStringExtra(EXTRA_ALBUM).orEmpty()

        setTitle("EDIT TAGS")
        setContentView(buildContent(songTitle, songArtist, songAlbum))
        loadExistingTags()
    }

    private fun loadExistingTags() {
        executor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, mediaUri)
                val values = mapOf(
                    "albumArtist" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    "genre" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    "year" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                    "track" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    "disc" to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
                )
                runOnUiThread {
                    if (albumArtist.text.isBlank()) albumArtist.setText(values["albumArtist"].orEmpty())
                    if (genre.text.isBlank()) genre.setText(values["genre"].orEmpty())
                    if (year.text.isBlank()) year.setText(values["year"].orEmpty())
                    if (track.text.isBlank()) track.setText(values["track"].orEmpty())
                    if (disc.text.isBlank()) disc.setText(values["disc"].orEmpty())
                }
            } catch (_: Exception) {
                // Some formats expose only a subset of common metadata.
            } finally {
                retriever.release()
            }
        }
    }

    private fun buildContent(songTitle: String, songArtist: String, songAlbum: String): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 28)
        }
        root.addView(TextView(this).apply {
            text = "Changes are written to the audio file's embedded metadata and then refreshed in the library."
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, 0, 0, 18)
        })
        title = field(root, "Title", songTitle)
        artist = field(root, "Artist", songArtist)
        album = field(root, "Album", songAlbum)
        albumArtist = field(root, "Album artist", "")
        genre = field(root, "Genre", "")
        year = field(root, "Year", "")
        track = field(root, "Track number", "")
        disc = field(root, "Disc number", "")
        comment = field(root, "Comment", "")

        val save = Button(this).apply {
            text = "SAVE CHANGES"
            setOnClickListener { saveChanges() }
        }
        root.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 })
        return ScrollView(this).apply { addView(root) }
    }

    private fun field(parent: LinearLayout, label: String, value: String): EditText {
        val edit = EditText(this).apply {
            hint = label
            setText(value)
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            textSize = 16f
            setSingleLine(true)
        }
        parent.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(getColor(R.color.accent_cyan))
            textSize = 11f
            setPadding(0, 10, 0, 2)
        })
        parent.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return edit
    }

    private fun saveChanges() {
        val values = TagEditor.Values(
            title.text.toString(), artist.text.toString(), album.text.toString(), albumArtist.text.toString(),
            genre.text.toString(), year.text.toString(), track.text.toString(), disc.text.toString(), comment.text.toString()
        )
        if (values.title.isBlank()) {
            title.error = "Title cannot be empty"
            title.requestFocus()
            return
        }
        setBusy(true)
        executor.execute {
            try {
                val prepared = TagEditor.prepare(this, mediaUri, values)
                runOnUiThread { attemptWrite(prepared) }
            } catch (t: Throwable) {
                runOnUiThread {
                    setBusy(false)
                    toast("Couldn't prepare the tags: ${t.message ?: "unsupported audio format"}")
                }
            }
        }
    }

    private fun attemptWrite(prepared: TagEditor.Result) {
        executor.execute {
            try {
                TagEditor.writePrepared(this, mediaUri, prepared)
                runOnUiThread {
                    setBusy(false)
                    toast("Tags saved")
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            } catch (security: SecurityException) {
                runOnUiThread {
                    pending = prepared
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val request = MediaStore.createWriteRequest(contentResolver, listOf(mediaUri))
                            startIntentSenderForResult(request.intentSender, WRITE_REQUEST, null, 0, 0, 0, null)
                        } catch (t: Throwable) {
                            pending = null
                            prepared.tempFile.delete()
                            setBusy(false)
                            toast("Android could not request write access")
                        }
                    } else {
                        prepared.tempFile.delete()
                        setBusy(false)
                        toast("Android did not grant write access to this file")
                    }
                }
            } catch (t: Throwable) {
                prepared.tempFile.delete()
                runOnUiThread {
                    setBusy(false)
                    toast("Couldn't save tags: ${t.message ?: "write failed"}")
                }
            }
        }
    }

    @Deprecated("Android compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WRITE_REQUEST) return
        val prepared = pending
        pending = null
        if (resultCode != Activity.RESULT_OK || prepared == null) {
            prepared?.tempFile?.delete()
            setBusy(false)
            toast("Edit cancelled")
            return
        }
        setBusy(true)
        attemptWrite(prepared)
    }

    private fun setBusy(busy: Boolean) {
        title.isEnabled = !busy; artist.isEnabled = !busy; album.isEnabled = !busy
        albumArtist.isEnabled = !busy; genre.isEnabled = !busy; year.isEnabled = !busy
        track.isEnabled = !busy; disc.isEnabled = !busy; comment.isEnabled = !busy
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        executor.shutdownNow()
        pending?.tempFile?.delete()
        pending = null
        super.onDestroy()
    }

    companion object {
        private const val WRITE_REQUEST = 7401
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"

        fun launch(context: android.content.Context, song: Song) {
            context.startActivity(Intent(context, TagEditorActivity::class.java).apply {
                putExtra(EXTRA_URI, song.uriString)
                putExtra(EXTRA_TITLE, song.title)
                putExtra(EXTRA_ARTIST, song.artist)
                putExtra(EXTRA_ALBUM, song.album)
            })
        }
    }
}
