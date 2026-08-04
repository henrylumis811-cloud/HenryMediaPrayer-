package com.henrylumis.mediaprayer.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.henrylumis.mediaprayer.ui.altar.AltarFragment
import com.henrylumis.mediaprayer.ui.library.LibraryFragment
import com.henrylumis.mediaprayer.ui.signal.SignalFragment
import com.henrylumis.mediaprayer.ui.verses.VersesFragment

class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 4
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> AltarFragment()
        1 -> LibraryFragment()
        2 -> VersesFragment()
        else -> SignalFragment()
    }
}
