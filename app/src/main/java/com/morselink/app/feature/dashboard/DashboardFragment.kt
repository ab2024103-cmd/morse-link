package com.morselink.app.feature.dashboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.MainActivity
import android.net.Uri
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.morselink.app.R
import com.morselink.app.core.model.RecentDevice
import com.morselink.app.core.network.TempLink
import com.morselink.app.core.network.TempLinkClient
import com.morselink.app.core.network.TempLinkHost
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.DeviceTier
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentDashboardBinding
import com.morselink.app.di.AppServices
import com.morselink.app.feature.transfer.TransferFragment
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val text = res.data?.getStringExtra(com.morselink.app.feature.transfer.QrScanActivity.EXTRA_RESULT)
            if (!text.isNullOrBlank() && isAdded) handleScannedText(text)
        }
    }

    private fun handleScannedText(raw: String) {
        val t = raw.trim()
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) {
            try {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(t)))
            } catch (_: Exception) {
            }
            return
        }
        if (t.startsWith("morselink://temp?", true)) {
            val params = t.substringAfter("morselink://temp?").split("&")
            val ssid = params.firstOrNull { it.startsWith("ssid=") }
                ?.removePrefix("ssid=")
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            val key = params.firstOrNull { it.startsWith("key=") }
                ?.removePrefix("key=")
                ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            if (!ssid.isNullOrBlank()) showJoinTempLink(ssid, key ?: "")
            return
        }
        val hostPort = t.removePrefix("morselink://connect?")
        val host: String
        var port = com.morselink.app.core.network.LanTransport.TCP_PORT
        if (hostPort.startsWith("host=")) {
            val params = hostPort.split("&")
            host = params.firstOrNull()?.removePrefix("host=") ?: return
            params.firstOrNull { it.startsWith("port=") }?.removePrefix("port=")?.toIntOrNull()?.let { port = it }
        } else {
            val parts = hostPort.split(":")
            host = parts[0]
            parts.getOrNull(1)?.toIntOrNull()?.let { port = it }
        }
        if (host.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            com.morselink.app.core.transfer.TransferEngine.connectManual(host, port)
        }
        (activity as? MainActivity)?.openTransferScreen(com.morselink.app.feature.transfer.TransferFragment.Mode.RECEIVE)
    }

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = AppServices.prefs

        binding.deviceName.text = prefs.deviceName
        binding.avatar.setImageDrawable(Ui.avatarDrawable(requireContext(), prefs.deviceName, prefs.avatarColorIndex))
        binding.avatar.setOnClickListener {
            (activity as? MainActivity)?.openOverlay(
                com.morselink.app.feature.settings.SettingsFragment()
            )
        }

        // Low-end tier: replace the canvas radar with a plain label (spec 13).
        if (DeviceTier.isLowEndDevice) {
            binding.radar.visibility = View.GONE
        } else {
            binding.radar.visibility = View.VISIBLE
        }

        binding.buttonSend.setOnClickListener {
            (activity as? MainActivity)?.openTransferScreen(TransferFragment.Mode.SEND)
        }
        binding.buttonReceive.setOnClickListener {
            (activity as? MainActivity)?.openTransferScreen(TransferFragment.Mode.RECEIVE)
        }
        binding.pcCard.setOnClickListener {
            (activity as? MainActivity)?.openOverlay(com.morselink.app.feature.webshare.WebShareFragment())
        }
        binding.strangerCard.setOnClickListener { showStrangerOptions() }
        binding.buttonPcScan.setOnClickListener {
            try {
                qrScanLauncher.launch(
                    android.content.Intent(requireContext(), com.morselink.app.feature.transfer.QrScanActivity::class.java)
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.transfer_camera_needed, Toast.LENGTH_SHORT).show()
            }
        }
        binding.helpButton.setOnClickListener {
            (activity as? MainActivity)?.openOverlay(com.morselink.app.feature.settings.ConnectionDoctorFragment())
        }

        binding.recentList.layoutManager = LinearLayoutManager(requireContext())
        binding.recentList.adapter = RecentAdapter(AppServices.prefs.recentDevices()) { device ->
            // Recency shortens discovery, never consent (spec Section 6.3).
            if (device.transport == com.morselink.app.core.model.TransportType.LAN_WIFI && device.host != null) {
                lifecycleScope.launch {
                    TransferEngine.connectManual(device.host!!, device.port)
                }
                (activity as? MainActivity)?.openTransferScreen(TransferFragment.Mode.RECEIVE)
            } else {
                (activity as? MainActivity)?.openTransferScreen(TransferFragment.Mode.RECEIVE)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TransferEngine.sessionState.collect { state ->
                        if (state.active) {
                            binding.connectionStatus.text = getString(
                                R.string.dashboard_connected_to, state.peerName ?: ""
                            )
                        } else {
                            binding.connectionStatus.setText(R.string.dashboard_not_connected)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The dashboard keeps discovery warm so peers appear quickly; consent
        // still gates every connection (spec Section 6.1).
        if (Permissions.discoveryPermissions(requireContext()).isNotEmpty() &&
            !hasMediaOrAnyGranted()
        ) {
            // Permission not yet granted; the pairing screen requests it with a
            // rationale. Radar still animates.
        }
    }

    private fun hasMediaOrAnyGranted(): Boolean {
        return Permissions.mediaReadPermissions(requireContext()).isEmpty()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // A running link is NOT ended here: transfers may still be going on.
        // It ends via the dialog's "End link" button (or the next app start).
        tempHostDialog?.dismiss()
        tempHostDialog = null
        joinProgressDialog?.dismiss()
        joinProgressDialog = null
        _binding = null
    }

    // ---------------- temporary stranger link (TempLink) ----------------

    private var tempHost: TempLinkHost? = null
    private var tempHostDialog: AlertDialog? = null
    private var joinProgressDialog: AlertDialog? = null
    private var tempClient: TempLinkClient? = null

    private fun showStrangerOptions() {
        val options = arrayOf(
            getString(R.string.temp_link_create),
            getString(R.string.temp_link_join)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.temp_link_title)
            .setItems(options) { d, which ->
                d.dismiss()
                if (which == 0) createTempLink() else showJoinTempLink(null, null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createTempLink() {
        tempHost?.stop()
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (6 * dp).toInt(), pad, 0)
        }
        val status = TextView(ctx).apply { text = getString(R.string.temp_link_starting) }
        val qrImage = ImageView(ctx).apply {
            adjustViewBounds = true
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (12 * dp).toInt() }
        }
        val creds = TextView(ctx).apply {
            textIsSelectable = true
            textSize = 16f
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (10 * dp).toInt() }
        }
        val note = TextView(ctx).apply {
            text = getString(R.string.temp_link_note)
            textSize = 12f
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
        }
        col.addView(status)
        col.addView(qrImage)
        col.addView(creds)
        col.addView(note)

        tempHostDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.temp_link_title)
            .setView(col)
            .setCancelable(false)
            .setPositiveButton(R.string.temp_link_end) { d, _ ->
                d.dismiss()
                tempHost?.stop()
                tempHost = null
                tempHostDialog = null
            }
            .show()

        tempHost = TempLinkHost(ctx)
        tempHost?.start(
            { ssid, pass, _ ->
                if (!isAdded) {
                    tempHost?.stop()
                    tempHost = null
                    return@start
                }
                status.text = getString(R.string.temp_link_ready)
                val payload = "morselink://temp?ssid=" + Uri.encode(ssid) +
                    "&key=" + Uri.encode(pass)
                com.morselink.app.core.util.Qr.encode(payload, 640)?.let { bmp ->
                    qrImage.setImageBitmap(bmp)
                    qrImage.visibility = View.VISIBLE
                }
                creds.text = getString(R.string.temp_link_hint_ssid) + ": " + ssid +
                    "\n" + getString(R.string.temp_link_hint_password) + ": " + pass
                creds.visibility = View.VISIBLE
                // Discover the other phone the moment it joins the network.
                try {
                    TransferEngine.startDiscovery(com.morselink.app.core.model.TransportType.LAN_WIFI)
                } catch (_: Exception) {
                }
            },
            { reason ->
                tempHostDialog?.dismiss()
                tempHostDialog = null
                tempHost = null
                if (isAdded) Toast.makeText(ctx, reason, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun showJoinTempLink(prefillSsid: String?, prefillKey: String?) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val pad = (18 * dp).toInt()
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (6 * dp).toInt(), pad, 0)
        }
        if (!TempLinkClient(ctx).canJoinProgrammatically()) {
            col.addView(
                TextView(ctx).apply {
                    text = getString(R.string.temp_link_manual_hint)
                    textSize = 12.5f
                    setPadding(0, 0, 0, (10 * dp).toInt())
                }
            )
        }
        val ssidEdit = EditText(ctx).apply {
            hint = getString(R.string.temp_link_hint_ssid)
            setText(prefillSsid ?: "")
            setSingleLine()
        }
        val passEdit = EditText(ctx).apply {
            hint = getString(R.string.temp_link_hint_password)
            setText(prefillKey ?: "")
            setSingleLine()
        }
        col.addView(ssidEdit)
        col.addView(passEdit)

        AlertDialog.Builder(ctx)
            .setTitle(R.string.temp_link_join_title)
            .setView(col)
            .setPositiveButton(R.string.temp_link_join_action) { d, _ ->
                val ssid = ssidEdit.text.toString().trim()
                val pass = passEdit.text.toString()
                d.dismiss()
                if (ssid.isEmpty()) {
                    Toast.makeText(ctx, R.string.temp_link_hint_ssid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                joinTempNetwork(ssid, pass)
            }
            .setNeutralButton(R.string.temp_link_scan) { d, _ ->
                d.dismiss()
                try {
                    qrScanLauncher.launch(
                        android.content.Intent(
                            requireContext(),
                            com.morselink.app.feature.transfer.QrScanActivity::class.java
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), R.string.transfer_camera_needed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun joinTempNetwork(ssid: String, pass: String) {
        val ctx = requireContext()
        val client = TempLinkClient(ctx)
        tempClient = client
        if (!client.canJoinProgrammatically()) {
            Toast.makeText(ctx, R.string.temp_link_manual_hint, Toast.LENGTH_LONG).show()
            return
        }
        val dp = resources.displayMetrics.density
        val col = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), 0)
        }
        col.addView(TextView(ctx).apply { text = getString(R.string.temp_link_joining, ssid) })
        val bar = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
        }
        col.addView(bar)
        joinProgressDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.temp_link_title)
            .setView(col)
            .setCancelable(true)
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                d.dismiss()
                client.leave()
                joinProgressDialog = null
            }
            .show()
        client.join(
            ssid, pass,
            { _ ->
                joinProgressDialog?.dismiss()
                joinProgressDialog = null
                if (isAdded) {
                    Toast.makeText(ctx, getString(R.string.temp_link_connected, ssid), Toast.LENGTH_LONG).show()
                    (activity as? MainActivity)?.openTransferScreen(TransferFragment.Mode.RECEIVE)
                }
            },
            { reason ->
                joinProgressDialog?.dismiss()
                joinProgressDialog = null
                if (isAdded) Toast.makeText(ctx, reason, Toast.LENGTH_LONG).show()
            }
        )
    }

    private class RecentAdapter(
        private val devices: List<RecentDevice>,
        private val onClick: (RecentDevice) -> Unit
    ) : RecyclerView.Adapter<RecentAdapter.Holder>() {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val avatar: ImageView = v.findViewById(R.id.peer_avatar)
            val name: TextView = v.findViewById(R.id.peer_name)
            val transport: TextView = v.findViewById(R.id.peer_transport)
            val root: View = v
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_peer, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = devices.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val d = devices[position]
            holder.avatar.setImageDrawable(Ui.avatarDrawable(holder.itemView.context, d.name, position))
            holder.name.text = d.name
            holder.transport.text = when (d.transport) {
                com.morselink.app.core.model.TransportType.NEARBY_CONNECTIONS -> "Nearby"
                com.morselink.app.core.model.TransportType.LAN_WIFI -> "Wi-Fi"
                else -> ""
            }
            holder.root.setOnClickListener { onClick(d) }
        }
    }
}
