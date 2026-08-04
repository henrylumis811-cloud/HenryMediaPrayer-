package com.henrylumis.mediaprayer.ui.signal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.henrylumis.mediaprayer.MainActivity
import com.henrylumis.mediaprayer.databinding.FragmentSignalBinding
import com.henrylumis.mediaprayer.util.Prefs

/**
 * "Signal" tab = settings & controls: theme toggle + sleep timer.
 */
class SignalFragment : Fragment() {

    private var _binding: FragmentSignalBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSignalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        binding.themeSwitch.isChecked = Prefs.isDark(ctx)
        binding.themeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setNightMode(
                ctx,
                if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            requireActivity().recreate()
        }

        val timerOptions = listOf(0, 15, 30, 45, 60)
        binding.sleepTimerGroup.removeAllViews()
        timerOptions.forEach { minutes ->
            val chip = com.google.android.material.chip.Chip(ctx).apply {
                text = if (minutes == 0) "Off" else "${minutes}m"
                isCheckable = true
                setOnClickListener {
                    val activity = activity as? MainActivity
                    if (minutes == 0) activity?.cancelSleepTimer()
                    else activity?.startSleepTimer(minutes)
                }
            }
            binding.sleepTimerGroup.addView(chip)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
