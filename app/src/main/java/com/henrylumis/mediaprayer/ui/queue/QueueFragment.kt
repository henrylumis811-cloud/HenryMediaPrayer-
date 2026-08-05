package com.henrylumis.mediaprayer.ui.queue

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentQueueBinding

class QueueFragment : Fragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: QueueAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

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
            onMove = { from, to -> activity.moveQueueItem(from, to) }
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
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                adapter.moveLocally(from, to)
                adapter.onDragMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.queueList)

        refreshQueue()
        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        // Picks up track transitions (e.g. auto-advance to next song) so the
        // highlighted "now playing" row in the queue stays accurate.
        refreshRunnable = object : Runnable {
            override fun run() {
                refreshQueue()
                handler.postDelayed(this, 1500)
            }
        }
        handler.post(refreshRunnable!!)
    }

    private fun refreshQueue() {
        val activity = activity as? MainActivity ?: return
        if (_binding == null) return
        val queue = activity.getQueue()
        adapter.submitList(queue, activity.currentQueueIndex())
        binding.queueEmptyState.visibility = if (queue.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        refreshRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroyView()
        _binding = null
    }
}
