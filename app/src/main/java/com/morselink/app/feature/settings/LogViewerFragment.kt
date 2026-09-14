package com.morselink.app.feature.settings

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.morselink.app.R
import com.morselink.app.core.logging.LogStore
import com.morselink.app.databinding.FragmentLogviewerBinding
import java.io.File

/** Read-only log viewer with export/clear (spec Section 10.12). */
class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogviewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogviewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.buttonExport.setOnClickListener { export() }
        binding.buttonClear.setOnClickListener {
            LogStore.clear(requireContext())
            render()
            Toast.makeText(requireContext(), R.string.pref_log_cleared, Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun render() {
        val crashes = LogStore.crashTail(500)
        val text = buildString {
            append(LogStore.tail(1500).joinToString("\n"))
            if (crashes.isNotEmpty()) {
                append("\n\n───── ")
                append(getString(R.string.log_crash_reports))
                append(" ─────\n")
                append(crashes.joinToString("\n"))
            }
        }
        binding.logText.text = text
        // Newest lines are at the bottom — keep them in view.
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun export() {
        try {
            val file: File = LogStore.exportFile(requireContext())
                ?: File(requireContext().cacheDir, "morselink_log.txt")
            if (!file.exists()) throw IllegalStateException("no log")
            val uri = FileProvider.getUriForFile(
                requireContext(), requireContext().packageName + ".fileprovider", file
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    getString(R.string.pref_log_export)
                )
            )
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.state_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
