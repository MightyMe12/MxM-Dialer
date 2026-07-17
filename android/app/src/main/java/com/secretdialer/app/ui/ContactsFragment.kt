package com.secretdialer.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.secretdialer.app.ContactCache
import com.secretdialer.app.ContactIndex
import com.secretdialer.app.ContactInfo
import com.secretdialer.app.Debouncer
import com.secretdialer.app.MainActivity
import com.secretdialer.app.R
import com.secretdialer.app.databinding.FragmentListBinding
import java.util.concurrent.Executors

class ContactsFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactListAdapter
    private var contactIndex: ContactIndex = ContactIndex.EMPTY
    private val searchDebouncer = Debouncer(180)
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val contactCacheListener = { index: ContactIndex ->
        if (isAdded) {
            contactIndex = index
            scheduleFilter(binding.searchInput.text?.toString().orEmpty())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ContactListAdapter { contact -> onContactClick(contact) }
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter
        binding.emptyText.text = getString(R.string.no_contacts)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scheduleFilter(s?.toString().orEmpty())
            }
        })

        binding.fabKeypad.visibility = View.VISIBLE
        binding.fabKeypad.setImageResource(R.drawable.ic_add)
        binding.fabKeypad.setOnClickListener {
            (activity as? MainActivity)?.showContactEditor(null, null)
        }

        ContactCache.addListener(requireContext(), contactCacheListener)
    }

    private fun scheduleFilter(query: String) {
        searchDebouncer.submit {
            val snapshot = contactIndex
            searchExecutor.execute {
                val filtered = if (query.isBlank()) snapshot.contacts else snapshot.filterByText(query)
                if (!isAdded) return@execute
                activity?.runOnUiThread {
                    showResults(query, filtered)
                }
            }
        }
    }

    private fun showResults(query: String, filtered: List<ContactInfo>) {
        val b = _binding ?: return
        adapter.searchQuery = query
        adapter.submitList(filtered)
        b.emptyText.text = when {
            contactIndex.contacts.isEmpty() -> getString(R.string.no_contacts)
            query.isNotBlank() && filtered.isEmpty() -> getString(R.string.no_search_results)
            else -> getString(R.string.no_contacts)
        }
        b.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }



    private fun onContactClick(contact: ContactInfo) {
        // Open our custom in-app contact editor dialog
        (activity as? MainActivity)?.showContactEditor(contact.id, null)
    }

    override fun onDestroyView() {
        ContactCache.removeListener(contactCacheListener)
        searchDebouncer.cancel()
        super.onDestroyView()
        _binding = null
    }
}
