package com.morselink.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.morselink.app.databinding.ActivityMainBinding
import com.morselink.app.feature.dashboard.DashboardFragment
import com.morselink.app.feature.filemanager.FileManagerFragment
import com.morselink.app.feature.history.HistoryFragment
import com.morselink.app.feature.settings.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            switchTo(DashboardFragment(), "connect")
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connect -> switchTo(DashboardFragment(), "connect")
                R.id.nav_files -> switchTo(FileManagerFragment(), "files")
                R.id.nav_history -> switchTo(HistoryFragment(), "history")
                R.id.nav_settings -> switchTo(SettingsFragment(), "settings")
            }
            true
        }
    }

    private fun switchTo(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host, fragment, tag)
            .commit()
    }
}
