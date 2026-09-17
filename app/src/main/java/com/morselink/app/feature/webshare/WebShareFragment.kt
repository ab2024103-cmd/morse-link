package com.morselink.app.feature.webshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.morselink.app.R
import com.morselink.app.core.webshare.WebShareController
import com.morselink.app.core.util.Qr
import com.morselink.app.databinding.FragmentWebshareBinding
import kotlinx.coroutines.launch

/**
 * WebShare host screen (spec Section 10.10): pick hotspot or LAN mode, show
 * the QR + URL, and expose teardown. The URL embeds the per-session token —
 * scanning it is the pairing act.
 */
class WebShareFragment : Fragment() {

    private var _binding: FragmentWebshareBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebshareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        binding.buttonStartHotspot.setOnClickListener { confirmHotspotThenStart() }
        binding.buttonStartLan.setOnClickListener { WebShareController.start(false) }
        binding.buttonStop.setOnClickListener { WebShareController.stop() }
        binding.buttonCopyAddress.setOnClickListener { copyText(binding.addressValue.text.toString()) }
        binding.buttonCopyToken.setOnClickListener { copyText(binding.tokenValue.text.toString()) }
        binding.addressValue.setOnClickListener { copyText(binding.addressValue.text.toString()) }
        binding.tokenValue.setOnClickListener { copyText(binding.tokenValue.text.toString()) }
        binding.qrCode.setOnClickListener { showFullscreenQr() }
        binding.buttonDoctor.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_right, R.anim.fade_out,
                    R.anim.fade_in, R.anim.slide_out_right
                )
                .replace(id, com.morselink.app.feature.settings.ConnectionDoctorFragment(), "overlay_doctor")
                .addToBackStack("overlay_doctor")
                .commit()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    WebShareController.state.collect { render(it) }
                }
            }
        }
        render(WebShareController.state.value)
    }

    private fun confirmHotspotThenStart() {
        val ssid = try {
            val wm = requireContext().applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val s = wm?.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
            if (s.isNullOrBlank() || s == "<unknown ssid>") getString(R.string.webshare_this_network) else s
        } catch (_: Exception) {
            getString(R.string.webshare_this_network)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.webshare_confirm_title)
            .setMessage(getString(R.string.webshare_confirm_message, ssid))
            .setPositiveButton(R.string.action_yes) { d, _ ->
                d.dismiss()
                WebShareController.start(true)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(state: com.morselink.app.core.webshare.WebShareState) {
        val running = state.running
        binding.startButtons.visibility = if (running) View.GONE else View.VISIBLE
        binding.runningPanel.visibility = if (running) View.VISIBLE else View.GONE
        binding.errorLabel.visibility = if (!running && state.error != null) View.VISIBLE else View.GONE
        binding.errorLabel.text = state.error ?: ""

        if (!running) return

        binding.statusLabel.setText(
            if (state.hotspotMode) R.string.webshare_running else R.string.webshare_running
        )
        binding.hotspotCreds.visibility = if (state.hotspotMode) View.VISIBLE else View.GONE
        binding.ssidValue.text = state.ssid ?: ""
        binding.passwordValue.text = state.password ?: ""
        binding.manualNote.visibility = if (state.manualSetup) View.VISIBLE else View.GONE
        if (state.manualSetup) {
            binding.manualNote.setText(R.string.webshare_manual_setup)
        }

        val url = state.url
        if (url != null) {
            // Short, readable pieces instead of one long URL (m4/m14):
            // address + token are what a person actually types on the PC.
            val address = url
                .substringAfter("://", url)
                .substringBefore("/#t=")
            val token = url.substringAfter("#t=", "")
            binding.addressValue.text = address
            binding.tokenValue.text = token
            binding.urlValue.text = url
            val qr = Qr.encode(url, 640)
            if (qr != null) {
                binding.qrCode.setImageBitmap(qr)
                binding.qrCode.visibility = View.VISIBLE
            } else {
                binding.qrCode.visibility = View.GONE
            }
        }
    }

    /** Big, scannable-from-a-distance QR — laptops with webcams can read it. */
    private fun showFullscreenQr() {
        val url = WebShareController.state.value.url ?: return
        val qr = Qr.encode(url, 1080) ?: return
        val img = android.widget.ImageView(requireContext()).apply {
            adjustViewBounds = true
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        img.setImageBitmap(qr)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.webshare_title)
            .setView(img)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyText(value: String) {
        if (value.isBlank()) return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MorseLink", value))
        Toast.makeText(requireContext(), R.string.webshare_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyUrl() {
        val url = WebShareController.state.value.url ?: return
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MorseLink", url))
        Toast.makeText(requireContext(), R.string.webshare_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
