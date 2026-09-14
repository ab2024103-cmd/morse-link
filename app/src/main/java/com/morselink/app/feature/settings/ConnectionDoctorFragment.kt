package com.morselink.app.feature.settings

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.app.R
import com.morselink.app.core.transfer.TransferEngine
import com.morselink.app.core.util.Permissions
import com.morselink.app.databinding.FragmentDoctorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connection Doctor (spec Section 10.14): one tap runs every environment
 * check transfers depend on and offers a direct fix for each problem found.
 */
class ConnectionDoctorFragment : Fragment() {

    private var _binding: FragmentDoctorBinding? = null
    private val binding get() = _binding!!

    enum class Verdict { OK, WARN, FAIL }

    data class CheckResult(
        val label: String,
        val verdict: Verdict,
        val detail: String,
        val fix: (() -> Unit)? = null
    )

    private val results = ArrayList<CheckResult>()
    private var adapter: ChecksAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDoctorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.checksList.layoutManager = LinearLayoutManager(requireContext())
        adapter = ChecksAdapter(results)
        binding.checksList.adapter = adapter

        binding.buttonRun.setOnClickListener { runChecks() }
        runChecks()
    }

    private fun runChecks() {
        binding.buttonRun.isEnabled = false
        results.clear()
        adapter?.notifyDataSetChanged()
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val out = ArrayList<CheckResult>()

            // 1. Wi-Fi enabled
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiEnabled = wifi?.isWifiEnabled == true
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_wifi),
                    if (wifiEnabled) Verdict.OK else Verdict.FAIL,
                    if (wifiEnabled) getString(R.string.doctor_ok) else getString(R.string.doctor_fail),
                    if (!wifiEnabled) ({ startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }) else null
                )
            )

            // 2. Location services (needed for Wi-Fi scan results on many builds)
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationOn = lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true ||
                lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_location),
                    if (locationOn) Verdict.OK else Verdict.WARN,
                    if (locationOn) getString(R.string.doctor_ok) else getString(R.string.doctor_warn),
                    if (!locationOn) ({ startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) else null
                )
            )

            // 3. Google Play services / Nearby availability
            val gmsAvailable = withContext(Dispatchers.IO) {
                try {
                    ctx.packageManager.getPackageInfo("com.google.android.gms", 0) != null
                } catch (_: Exception) {
                    false
                }
            }
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_gms),
                    if (gmsAvailable) Verdict.OK else Verdict.WARN,
                    if (gmsAvailable) getString(R.string.doctor_ok) else getString(R.string.doctor_warn)
                )
            )

            // 4. Media permissions
            val missingMedia = Permissions.mediaReadPermissions(ctx)
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_media),
                    if (missingMedia.isEmpty()) Verdict.OK else Verdict.FAIL,
                    if (missingMedia.isEmpty()) getString(R.string.doctor_ok) else getString(R.string.doctor_fail),
                    if (missingMedia.isNotEmpty()) ({
                        requestPermissions(missingMedia.toTypedArray(), 4701)
                    }) else null
                )
            )

            // 5. Notifications (Android 13+)
            if (Build.VERSION.SDK_INT >= 33) {
                val notifGranted = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                out.add(
                    CheckResult(
                        getString(R.string.doctor_check_notifications),
                        if (notifGranted) Verdict.OK else Verdict.WARN,
                        if (notifGranted) getString(R.string.doctor_ok) else getString(R.string.doctor_warn),
                        if (!notifGranted) ({
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            )
                        }) else null
                    )
                )
            }

            // 6. Battery optimization
            val batteryOk = Permissions.isIgnoringBatteryOptimizations(ctx)
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_battery),
                    if (batteryOk) Verdict.OK else Verdict.WARN,
                    if (batteryOk) getString(R.string.doctor_ok) else getString(R.string.doctor_warn),
                    if (!batteryOk) ({
                        try {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        } catch (_: Exception) {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) else null
                )
            )

            // 7. WebShare port free (only relevant when it could run)
            val portFree = withContext(Dispatchers.IO) {
                try {
                    java.net.ServerSocket(33455).use { true }
                } catch (_: Exception) {
                    false
                }
            }
            val webShareRunning = TransferEngine.webShareActive
            out.add(
                CheckResult(
                    getString(R.string.doctor_check_port),
                    if (portFree || webShareRunning) Verdict.OK else Verdict.WARN,
                    when {
                        portFree -> getString(R.string.doctor_ok)
                        webShareRunning -> getString(R.string.doctor_ok) + " (WebShare running)"
                        else -> getString(R.string.doctor_warn)
                    }
                )
            )

            results.clear()
            results.addAll(out)
            adapter?.notifyDataSetChanged()
            binding.buttonRun.isEnabled = true
        }
    }

    private inner class ChecksAdapter(private val rows: List<CheckResult>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ChecksAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_check, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = rows[position]
            holder.label.text = r.label
            holder.status.text = r.detail
            val color = when (r.verdict) {
                Verdict.OK -> R.color.status_success
                Verdict.WARN -> R.color.status_warning
                Verdict.FAIL -> R.color.status_error
            }
            val icon = when (r.verdict) {
                Verdict.OK -> R.drawable.ic_check_circle
                Verdict.WARN -> R.drawable.ic_info
                Verdict.FAIL -> R.drawable.ic_error
            }
            holder.icon.setImageResource(icon)
            holder.icon.setColorFilter(ContextCompat.getColor(requireContext(), color))
            holder.status.setTextColor(ContextCompat.getColor(requireContext(), color))
            if (r.fix != null) {
                holder.fix.visibility = View.VISIBLE
                holder.fix.setOnClickListener { r.fix.invoke() }
            } else {
                holder.fix.visibility = View.GONE
            }
        }

        inner class Holder(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.check_icon)
            val label: TextView = v.findViewById(R.id.check_label)
            val status: TextView = v.findViewById(R.id.check_status)
            val fix: Button = v.findViewById(R.id.check_fix)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
