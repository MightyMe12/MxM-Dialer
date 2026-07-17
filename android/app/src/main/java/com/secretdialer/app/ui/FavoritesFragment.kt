package com.secretdialer.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.secretdialer.app.ContactCache
import com.secretdialer.app.MainActivity
import com.secretdialer.app.databinding.FragmentListBinding

class FavoritesFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ContactListAdapter
    private val contactCacheListener = { index: com.secretdialer.app.ContactIndex ->
        if (isAdded) {
            val contacts = index.contacts.take(12)
            val b = _binding
            if (b != null) {
                b.recycler.post {
                    adapter.submitList(contacts)
                    b.emptyText.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
                }
            }
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
        binding.searchLayout.visibility = View.GONE
        binding.emptyText.text = getString(com.secretdialer.app.R.string.no_favorites)

        ContactCache.addListener(requireContext(), contactCacheListener)
    }

    private fun onContactClick(contact: com.secretdialer.app.ContactInfo) {
        (activity as? MainActivity)?.placeCall(contact.number)
    }

    override fun onDestroyView() {
        ContactCache.removeListener(contactCacheListener)
        super.onDestroyView()
        _binding = null
    }
}
