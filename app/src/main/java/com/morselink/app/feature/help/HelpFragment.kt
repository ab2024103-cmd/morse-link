package com.morselink.app.feature.help

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.morselink.app.R
import com.morselink.app.databinding.FragmentHelpBinding
import com.morselink.app.feature.settings.ConnectionDoctorFragment

/** Help / FAQ with live search over a small static article set (spec 10.13). */
class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    data class Article(val titleRes: Int, val bodyRes: Int)

    private val articles = listOf(
        Article(R.string.article_not_found_title, R.string.article_not_found_body),
        Article(R.string.article_stall_title, R.string.article_stall_body),
        Article(R.string.article_browser_title, R.string.article_browser_body),
        Article(R.string.article_apk_title, R.string.article_apk_body),
        Article(R.string.article_hotspot_title, R.string.article_hotspot_body)
    )

    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        binding.articlesList.layoutManager = LinearLayoutManager(requireContext())
        binding.helpSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                query = s?.toString()?.trim() ?: ""
                render()
            }
        })
        render()
    }

    private fun render() {
        val filtered = if (query.isEmpty()) articles else {
            articles.filter {
                getString(it.titleRes).contains(query, true) ||
                    getString(it.bodyRes).contains(query, true)
            }
        }
        binding.articlesList.adapter = ArticleAdapter(filtered)
    }

    private inner class ArticleAdapter(private val items: List<Article>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<ArticleAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_article, parent, false)
            return Holder(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val a = items[position]
            holder.title.setText(a.titleRes)
            holder.body.setText(a.bodyRes)
        }

        inner class Holder(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.article_title)
            val body: TextView = v.findViewById(R.id.article_body)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
