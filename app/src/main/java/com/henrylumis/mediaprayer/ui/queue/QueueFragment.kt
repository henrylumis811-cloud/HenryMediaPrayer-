package com.henrylumis.mediaprayer.ui.queue

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentQueueBinding

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: QueueAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    // Drag state: only ONE move command is ever sent to the player, exactly
    // when the finger lifts -- not on every row crossed during the drag.
    // Sending a command per-row (the old behavior) flooded the session with
    // async moves that could resolve out of order, which is what caused
    // dragging to visually "snap back" after jumping a couple of songs.
    private var isDragging = false
    private var dragStartPosition = -1

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = activity as? MainActivity ?: return

        adapter = QueueAdapter(
            onClick = { position -> activity.playQueueIndex(position) },
            onRemove = { position ->
                activity.removeQueueItem(position)
                refreshQueue()
            },
            onMove = { _, _ -> } // no longer used per-step; see clearView below
        )
        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Purely a local, optimistic visual reorder -- cheap and instant,
                // no player/session calls happen here.
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.moveLocally(from, to)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    isDragging = true
                    dragStartPosition = viewHolder.bindingAdapterPosition
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (isDragging) {
                    val finalPosition = viewHolder.bindingAdapterPosition
                    if (dragStartPosition != -1 && finalPosition != -1 && finalPosition != dragStartPosition) {
                        activity.moveQueueItem(dragStartPosition, finalPosition)
                    }
                    isDragging = false
                    dragStartPosition = -1
                    // Give the session a moment to apply the move, then resync
                    // with the authoritative queue order.
                    handler.postDelayed({ refreshQueue() }, 300)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.queueList)

        refreshQueue()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        // Picks up track transitions (e.g. auto-advance to next song) so the
        // highlighted "now playing" row in the queue stays accurate. Skipped
        // entirely while a drag is in progress so it can't overwrite it mid-gesture.
        refreshRunnable = object : Runnable {
            override fun run() {
                if (!isDragging) refreshQueue()
                handler.postDelayed(this, 1500)
            }
        }
        handler.post(refreshRunnable!!)
    }

    private fun refreshQueue() {
        val activity = activity as? MainActivity ?: return
        if (_binding == null) return
        val queue = activity.getQueue()
        // If a manual crossfade is currently fading toward a track, highlight
        // that one instantly instead of waiting for the real switch to land.
        val pendingId = activity.pendingTrack?.mediaId
        val highlightIndex = if (pendingId != null) {
            queue.indexOfFirst { it.mediaId == pendingId }.let { if (it >= 0) it else activity.currentQueueIndex() }
        } else {
            activity.currentQueueIndex()
        }
        adapter.submitList(queue, highlightIndex)
        binding.queueEmptyState.visibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
