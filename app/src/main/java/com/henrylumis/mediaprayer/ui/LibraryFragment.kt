package com.henrylumis.mediaprayer.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.henrylumis.mediaprayer.data.Track
import com.henrylumis.mediaprayer.data.TrackRepository
import com.henrylumis.mediaprayer.databinding.FragmentLibraryBinding
import com.henrylumis.mediaprayer.player.PlayerManager
import com.henrylumis.mediaprayer.util.Format
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TrackAdapter
    private var tracks: List<Track> = emptyList()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addFiles(uris)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TrackAdapter(
            onClick = { index -> playIndex(index) },
            onRemove = { track -> removeTrack(track) }
        )
        binding.trackRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.trackRecyclerView.adapter = adapter

        binding.dropzone.setOnClickListener {
            pickFiles.launch(arrayOf("audio/*"))
        }

        loadTracks()
    }

    private fun loadTracks() {
        viewLifecycleOwner.lifecycleScope.launch {
            tracks = TrackRepository.getInstance(requireContext()).getAll()
            render()
        }
    }

    private fun render() {
        val activeId = PlayerManager.tracks.getOrNull(PlayerManager.currentIndex())?.id
        adapter.submitList(tracks, activeId)
        binding.trackCountText.text = "${tracks.size} " + if (tracks.size == 1) "track" else "tracks"
        binding.emptyState.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        binding.trackRecyclerView.visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addFiles(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = TrackRepository.getInstance(requireContext())
            for (uri in uris) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (t: Throwable) {
                    // Some providers don't support persistable permissions; playback
                    // will still work for this session even if it fails.
                }

                var title = "Untitled offering"
                var artist = "Unknown"
                var durationMs = 0L
                var format = "audio"
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(requireContext(), uri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { title = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { artist = it }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                        durationMs = it.toLongOrNull() ?: 0L
                    }
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                        format = it.substringAfterLast('/').uppercase()
                    }
                    retriever.release()
                } catch (t: Throwable) {
                    // Fall back to the file name below.
                }

                if (title == "Untitled offering") {
                    val docName = queryDisplayName(uri)
                    if (docName != null) title = Format.niceNameFromFile(docName)
                }

                val track = Track(
                    id = Format.newId(),
                    uriString = uri.toString(),
                    title = title,
                    artist = artist,
                    durationMs = durationMs,
                    format = format
                )
                repo.upsert(track)
            }
            loadTracks()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun playIndex(index: Int) {
        PlayerManager.setQueue(tracks, index)
        render()
    }

    private fun removeTrack(track: Track) {
        viewLifecycleOwner.lifecycleScope.launch {
            TrackRepository.getInstance(requireContext()).delete(track.id)
            loadTracks()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
