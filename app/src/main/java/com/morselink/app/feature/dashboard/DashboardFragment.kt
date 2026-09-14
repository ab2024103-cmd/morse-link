package com.morselink.app.feature.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.morselink.app.MainActivity
import com.morselink.app.R
import com.morselink.app.core.model.RecentDevice
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.ui.Ui
import com.morselink.app.core.util.DeviceTier
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentDashboardBinding
import com.morselink.app.di.AppServices
import com.morselink.app.feature.transfer.TransferFragment
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

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
        _binding = null
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
