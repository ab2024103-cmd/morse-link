package com.morselink.app.feature.onboarding

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.materialswitch.MaterialSwitch
import com.morselink.app.R
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.ActivityOnboardingBinding
import com.morselink.app.di.AppServices

/**
 * Five-page onboarding wizard (spec Section 10.15): welcome, device name,
 * permissions (with disclosure), transfer modes, crash-log opt-in.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val pages = listOf(Page.WELCOME, Page.NAME, Page.PERMISSIONS, Page.MODES, Page.PRIVACY)

    private enum class Page { WELCOME, NAME, PERMISSIONS, MODES, PRIVACY }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = OnboardingAdapter()
        binding.pager.isUserInputEnabled = false

        binding.buttonNext.setOnClickListener { advance() }
        binding.buttonSkip.setOnClickListener { finishFlow() }

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                renderButtons(position)
            }
        })
        renderButtons(0)
    }

    private fun renderButtons(position: Int) {
        val last = position == pages.size - 1
        binding.buttonNext.setText(if (last) R.string.onboarding_done else R.string.action_next)
        binding.buttonSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
    }

    private fun advance() {
        val pos = binding.pager.currentItem
        when (pages[pos]) {
            Page.NAME -> {
                val input = findPageInput(pos)
                val name = input?.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    AppServices.prefs.deviceName = name
                }
            }
            Page.PRIVACY -> {
                finishFlow()
                return
            }
            else -> {}
        }
        if (pos < pages.size - 1) {
            binding.pager.setCurrentItem(pos + 1, true)
        }
    }

    private fun finishFlow() {
        AppServices.prefs.onboardingDone = true
        setResult(RESULT_OK)
        finish()
    }

    private fun findPageInput(pos: Int): EditText? {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(pos)
        return holder?.itemView?.findViewById(R.id.page_input)
    }

    private fun findPageSwitch(pos: Int): MaterialSwitch? {
        val rv = binding.pager.getChildAt(0) as? RecyclerView ?: return null
        val holder = rv.findViewHolderForAdapterPosition(pos)
        return holder?.itemView?.findViewById(R.id.page_switch)
    }

    inner class OnboardingAdapter : RecyclerView.Adapter<OnboardingAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (pages[position]) {
                Page.WELCOME -> {
                    holder.icon.setImageResource(R.drawable.ic_logo)
                    holder.title.setText(R.string.onboarding_welcome_title)
                    holder.body.setText(R.string.onboarding_welcome_body)
                }
                Page.NAME -> {
                    holder.icon.setImageResource(R.drawable.ic_phone)
                    holder.title.setText(R.string.onboarding_name_title)
                    holder.body.setText(R.string.onboarding_name_body)
                    holder.input.visibility = View.VISIBLE
                    holder.input.setText(AppServices.prefs.deviceName)
                    holder.input.setSelection(holder.input.text.length)
                }
                Page.PERMISSIONS -> {
                    holder.icon.setImageResource(R.drawable.ic_bluetooth)
                    holder.title.setText(R.string.onboarding_permissions_title)
                    holder.body.setText(R.string.onboarding_permissions_body)
                }
                Page.MODES -> {
                    holder.icon.setImageResource(R.drawable.ic_computer)
                    holder.title.setText(R.string.onboarding_modes_title)
                    holder.body.setText(R.string.onboarding_modes_body)
                }
                Page.PRIVACY -> {
                    holder.icon.setImageResource(R.drawable.ic_pulse)
                    holder.title.setText(R.string.onboarding_crash_title)
                    holder.body.setText(R.string.onboarding_crash_body)
                    holder.switch.visibility = View.VISIBLE
                    holder.switch.isChecked = AppServices.prefs.crashLogsEnabled
                    holder.switch.setOnCheckedChangeListener { _, checked ->
                        AppServices.prefs.crashLogsEnabled = checked
                    }
                }
            }
        }

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.page_icon)
            val title: TextView = v.findViewById(R.id.page_title)
            val body: TextView = v.findViewById(R.id.page_body)
            val input: EditText = v.findViewById(R.id.page_input)
            val switch: MaterialSwitch = v.findViewById(R.id.page_switch)
        }
    }
}
