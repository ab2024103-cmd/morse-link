package com.morselink.app.feature.transfer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.MainActivity
import com.morselink.app.R
import com.morselink.app.core.model.DiscoveredPeer
import com.morselink.app.core.model.TransportType
import com.morselink.app.core.network.LanTransport
import com.morselink.app.core.network.NearbyTransport
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.Fmt
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentTransferBinding
import com.morselink.app.feature.filemanager.FileManagerFragment
import kotlinx.coroutines.launch

/**
 * Pairing + live transfer screen (spec Section 10.7). Opened with an explicit
 * mode parameter — intent is never inferred from incidental state (spec 10.4).
 */
class TransferFragment : Fragment() {

    enum class Mode { SEND, RECEIVE }

    private var _binding: FragmentTransferBinding? = null
    private val binding get() = _binding!!

    private val qrScanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val text = res.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
            if (!text.isNullOrBlank()) handleScannedText(text)
        }
    }

    private var mode = Mode.SEND
    private var sendingAdapter: TransferItemsAdapter? = null
    private var receivingAdapter: TransferItemsAdapter? = null
    private var pairingCollapsed = false
    private var lastPairingFaceConnected = false
    private var peerClickPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = (arguments?.getString("mode") ?: "SEND").let {
            if (it == "RECEIVE") Mode.RECEIVE else Mode.SEND
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTransferBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.title = getString(
            if (mode == Mode.SEND) R.string.transfer_title_send else R.string.transfer_title_receive
        )
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.peersList.layoutManager = LinearLayoutManager(requireContext())
        binding.peersList.adapter = PeersAdapter(emptyList()) { peer -> connectTo(peer) }

        binding.sendingList.layoutManager = LinearLayoutManager(requireContext())
        binding.receivingList.layoutManager = LinearLayoutManager(requireContext())
        sendingAdapter = TransferItemsAdapter(emptyList())
        receivingAdapter = TransferItemsAdapter(emptyList())
        binding.sendingList.adapter = sendingAdapter
        binding.receivingList.adapter = receivingAdapter

        binding.pairingToggle.setOnClickListener {
            pairingCollapsed = !pairingCollapsed
            binding.pairingBody.visibility = if (pairingCollapsed) View.GONE else View.VISIBLE
        }

        binding.buttonSwitchTransport.setOnClickListener {
            val current = TransferEngine.discoveryTransport
            if (current == TransportType.LAN_WIFI) {
                startDiscoveryWithPermission(TransportType.NEARBY_CONNECTIONS)
            } else {
                startDiscoveryWithPermission(TransportType.LAN_WIFI)
            }
        }

        binding.buttonScanQr.setOnClickListener {
            try {
                qrScanLauncher.launch(Intent(requireContext(), QrScanActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.transfer_camera_needed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.buttonManualConnect.setOnClickListener {
            val hostPort = binding.manualHost.text.toString().trim()
            if (hostPort.isEmpty()) return@setOnClickListener
            val parts = hostPort.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: LanTransport.TCP_PORT
            showConnecting(host, host)
            viewLifecycleOwner.lifecycleScope.launch {
                val ok = TransferEngine.connectManual(host, port)
                if (!ok) {
                    hideConnecting()
                    Toast.makeText(requireContext(), R.string.transfer_no_devices, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.buttonDoctor.setOnClickListener {
            (activity as? MainActivity)?.openOverlay(
                com.morselink.app.feature.settings.ConnectionDoctorFragment()
            )
        }

        binding.buttonChooseFiles.setOnClickListener { openPicker() }
        binding.buttonQueue.setOnClickListener { showQueue() }
        binding.buttonPauseAll.setOnClickListener {
            val anyPaused = TransferEngine.itemsSnapshot().any {
                it.state == com.morselink.app.core.model.TransferItemState.PAUSED
            }
            if (anyPaused) TransferEngine.resumeAll() else TransferEngine.pauseAll()
        }
        binding.buttonMinimize.setOnClickListener {
            // The session stays alive at the process level; only this screen closes.
            parentFragmentManager.popBackStack()
        }
        binding.buttonEndSession.setOnClickListener {
            Ui.confirm(
                requireContext(),
                getString(R.string.transfer_stop_session),
                getString(R.string.transfer_stop_session) + "?"
            ) {
                TransferEngine.closeSession("user ended session")
            }
        }

        observeState()
        Ui.maybeShowTip(this, "pairing", getString(R.string.transfer_tip))

        // Kick off discovery with the primary transport.
        startDiscoveryWithPermission(null)
    }

    private fun startDiscoveryWithPermission(prefer: TransportType?) {
        val missing = Permissions.discoveryPermissions(requireContext())
        if (missing.isEmpty()) {
            TransferEngine.startDiscovery(prefer)
            refreshTransportUi(prefer)
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.permission_denied_title)
            .setMessage(R.string.onboarding_permissions_body)
            .setPositiveButton(R.string.action_ok) { d, _ ->
                d.dismiss()
                requestPermissions(missing.toTypedArray(), RC_DISCOVERY)
            }
            .setNegativeButton(R.string.action_cancel) { d, _ -> d.dismiss() }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == RC_DISCOVERY) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all {
                it == PackageManager.PERMISSION_GRANTED
            }
            if (allGranted) {
                TransferEngine.startDiscovery(null)
                refreshTransportUi(null)
            } else {
                val deniedForever = permissions.all {
                    !shouldShowRequestPermissionRationale(it)
                }
                showDeniedDialog(deniedForever)
            }
        }
    }

    private fun showDeniedDialog(deniedForever: Boolean) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.permission_denied_title)
            .setMessage(
                if (deniedForever) {
                    getString(R.string.permission_denied_body) + " " +
                        getString(R.string.onboarding_permissions_body)
                } else {
                    getString(R.string.permission_denied_body)
                }
            )
            .setPositiveButton(R.string.action_ok, null)
        if (deniedForever) {
            builder.setNegativeButton(R.string.action_open_settings) { d, _ ->
                d.dismiss()
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", requireContext().packageName, null)
                    )
                )
            }
        }
        builder.show()
    }

    private fun refreshTransportUi(prefer: TransportType?) {
        val usingLan = TransferEngine.discoveryTransport == TransportType.LAN_WIFI ||
            prefer == TransportType.LAN_WIFI ||
            (!NearbyTransport.isAvailable() && prefer == null)
        if (usingLan) {
            binding.pairingTransportLabel.setText(R.string.transfer_searching_lan)
            binding.buttonSwitchTransport.setText(R.string.transfer_switch_nearby)
            val addr = LanTransport.preferredAddress()
            if (addr != null) {
                binding.pairingManualAddress.visibility = View.VISIBLE
                binding.pairingManualAddress.text = getString(
                    R.string.transfer_manual_connect
                ) + ": $addr:${LanTransport.TCP_PORT}"
            }
        } else {
            binding.pairingTransportLabel.setText(R.string.transfer_searching_nearby)
            binding.buttonSwitchTransport.setText(R.string.transfer_switch_lan)
            binding.pairingManualAddress.visibility = View.GONE
        }
        binding.manualConnectRow.visibility = if (usingLan) View.VISIBLE else View.GONE
    }

    /**
     * QR payload formats (m4): "host", "host:port",
     * "morselink://connect?host=H&port=P" for LAN connects, or a WebShare
     * URL (http://ip:33455/#t=token) which opens in the browser.
     */
    private fun handleScannedText(raw: String) {
        val t = raw.trim()
        if (t.startsWith("morselink://", ignoreCase = true)) {
            val host = Regex("host=([^&]+)").find(t)?.groupValues?.get(1)
            val port = Regex("port=(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                ?: LanTransport.TCP_PORT
            if (!host.isNullOrBlank()) {
                connectScanned(host, port)
            } else {
                Toast.makeText(requireContext(), R.string.transfer_no_devices, Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (t.startsWith("http://", ignoreCase = true) || t.startsWith("https://", ignoreCase = true)) {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setMessage(R.string.transfer_scan_open_web)
                .setPositiveButton(R.string.action_yes) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(t)))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), R.string.transfer_no_devices, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        val parts = t.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: LanTransport.TCP_PORT
        if (host.isNotBlank()) connectScanned(host, port)
    }

    private fun connectScanned(host: String, port: Int) {
        showConnecting(host, host)
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = TransferEngine.connectManual(host, port)
            if (!ok) {
                hideConnecting()
                Toast.makeText(requireContext(), R.string.transfer_no_devices, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun connectTo(peer: DiscoveredPeer) {
        if (peerClickPending) return
        peerClickPending = true
        showConnecting(peer.name, peer.deviceId)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                TransferEngine.connectPeer(peer)
            } finally {
                peerClickPending = false
            }
        }
    }

    /** Sender-side feedback while the peer handshake/consent round-trip runs. */
    private var connectingPeerId: String? = null

    private fun showConnecting(name: String, peerId: String?) {
        connectingPeerId = peerId
        binding.connectingRow.visibility = View.VISIBLE
        binding.connectingLabel.text = getString(R.string.connecting_to, name) +
            "\n" + getString(R.string.connecting_hint)
        // Safety: never spin forever if nothing comes back.
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(30000)
            if (connectingPeerId == peerId) hideConnecting()
        }
    }

    private fun hideConnecting() {
        connectingPeerId = null
        binding.connectingRow.visibility = View.GONE
    }

    private fun openPicker() {
        (activity as? MainActivity)?.openOverlay(FileManagerFragment.newInstance(pickMode = true))
    }

    private fun showQueue() {
        QueueSheet().show(childFragmentManager, "queue")
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TransferEngine.sessionState.collect { state ->
                        renderSession(state.active, state.peerName, state.transport)
                    }
                }
                launch {
                    TransferEngine.peers.collect { peers ->
                        renderPeers(peers)
                    }
                }
                launch {
                    TransferEngine.items.collect { items ->
                        val sending = items.filter { it.direction == com.morselink.app.core.model.TransferDirection.SENDING }
                        val receiving = items.filter { it.direction == com.morselink.app.core.model.TransferDirection.RECEIVING }
                        // Update the existing adapters: reassigning new ones
                        // every 200 ms reset scroll and flickered (m9).
                        sendingAdapter?.update(sending)
                        receivingAdapter?.update(receiving)
                        renderStatusLine(sending + receiving)
                    }
                }
            }
        }
    }

    private fun renderSession(active: Boolean, peerName: String?, transport: TransportType?) {
        if (active) hideConnecting()
        val title = if (active) {
            getString(R.string.transfer_connected_to, peerName ?: "")
        } else {
            getString(R.string.transfer_scan_connect)
        }
        binding.pairingTitle.text = title
        binding.pairingTransportLabel.text = when {
            active && transport == TransportType.NEARBY_CONNECTIONS -> getString(R.string.transfer_via_nearby)
            active && transport == TransportType.LAN_WIFI -> getString(R.string.transfer_via_lan)
            TransferEngine.discoveryTransport == TransportType.LAN_WIFI -> getString(R.string.transfer_searching_lan)
            else -> getString(R.string.transfer_searching_nearby)
        }
        // Auto re-expand when the card's face changes — new information the
        // user has not seen yet (spec Section 10.7).
        if (active != lastPairingFaceConnected) {
            lastPairingFaceConnected = active
            pairingCollapsed = false
            binding.pairingBody.visibility = View.VISIBLE
        }
        if (!active) {
            // Keep discovering while waiting on this screen.
            if (TransferEngine.discoveryTransport == null && Permissions.discoveryPermissions(requireContext()).isEmpty()) {
                TransferEngine.startDiscovery(null)
            }
        }
    }

    private fun renderPeers(peers: List<DiscoveredPeer>) {
        binding.peersList.adapter = PeersAdapter(peers) { peer -> connectTo(peer) }
        binding.peersEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        binding.peersLabel.text = if (peers.isEmpty()) {
            getString(R.string.transfer_no_devices)
        } else {
            getString(R.string.transfer_searching_lan)
        }
    }

    private fun renderStatusLine(items: List<com.morselink.app.core.model.TransferItem>) {
        val active = items.filter {
            it.state == com.morselink.app.core.model.TransferItemState.IN_PROGRESS
        }
        if (active.isEmpty()) {
            binding.statusLine.visibility = View.GONE
            return
        }
        val speed = active.sumOf { it.speedBps }
        val remaining = active.sumOf { it.totalBytes - it.bytesTransferred }
        val eta = if (speed > 0) remaining / speed else -1
        binding.statusLine.visibility = View.VISIBLE
        binding.statusLine.text = "${Fmt.speed(speed)}   •   ${Fmt.eta(eta)}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class PeersAdapter(
        private val peers: List<DiscoveredPeer>,
        private val onClick: (DiscoveredPeer) -> Unit
    ) : RecyclerView.Adapter<PeersAdapter.Holder>() {

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

        override fun getItemCount(): Int = peers.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val p = peers[position]
            holder.avatar.setImageDrawable(Ui.avatarDrawable(holder.itemView.context, p.name, position))
            holder.name.text = p.name
            holder.transport.text = when (p.transport) {
                TransportType.NEARBY_CONNECTIONS -> "Nearby"
                TransportType.LAN_WIFI -> "Wi-Fi"
                else -> ""
            }
            holder.root.setOnClickListener { onClick(p) }
        }
    }

    companion object {
        private const val RC_DISCOVERY = 4101

        fun newInstance(mode: Mode): TransferFragment {
            val f = TransferFragment()
            f.arguments = Bundle().apply { putString("mode", mode.name) }
            return f
        }
    }
}
