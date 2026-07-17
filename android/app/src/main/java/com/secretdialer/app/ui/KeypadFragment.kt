package com.secretdialer.app.ui

import android.os.Bundle
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
import com.secretdialer.app.databinding.FragmentKeypadBinding
import java.util.concurrent.Executors

class KeypadFragment : com.google.android.material.bottomsheet.BottomSheetDialogFragment() {

    interface DialListener {
        fun onDial(number: String)
        fun onNumberChanged(number: String)
    }

    private var _binding: FragmentKeypadBinding? = null
    private val binding get() = _binding!!
    private val digits = StringBuilder()
    private lateinit var matchAdapter: ContactListAdapter
    private var contactIndex: ContactIndex = ContactIndex.EMPTY
    private val matchDebouncer = Debouncer(60)
    private val matchExecutor = Executors.newSingleThreadExecutor()
    private var searchSequence = 0L
    private val contactCacheListener = { index: ContactIndex ->
        if (isAdded) {
            contactIndex = index
            updateMatches(digits.toString())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeypadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        matchAdapter = ContactListAdapter { contact ->
            // Tap fills the number field — long-press or call button to dial
            setNumber(contact.number.filter { it.isDigit() || it == '+' })
        }
        binding.matchList.layoutManager = LinearLayoutManager(requireContext())
        binding.matchList.setHasFixedSize(true)
        binding.matchList.itemAnimator = null
        binding.matchList.adapter = matchAdapter

        // Register AFTER matchAdapter is assigned so the immediate callback in addListener()
        // doesn't crash on a null adapter
        ContactCache.addListener(requireContext(), contactCacheListener)

        val keys = listOf(
            binding.key1 to "1",
            binding.key2 to "2",
            binding.key3 to "3",
            binding.key4 to "4",
            binding.key5 to "5",
            binding.key6 to "6",
            binding.key7 to "7",
            binding.key8 to "8",
            binding.key9 to "9",
            binding.keyStar to "*",
            binding.key0 to "0",
            binding.keyHash to "#"
        )
        keys.forEach { (btn, digit) ->
            btn.setOnClickListener {
                appendDigit(digit)
                com.secretdialer.app.DialerHelper.playHapticFeedback(it)
            }
            val isSpeedDialDigit = digit.length == 1 && digit[0] in '2'..'9'
            if (isSpeedDialDigit) {
                btn.setOnLongClickListener {
                    val keyVal = digit.toInt()
                    val num = com.secretdialer.app.Prefs.getSpeedDial(requireContext(), keyVal)
                    if (num != null) {
                        com.secretdialer.app.DialerHelper.playHapticFeedback(it)
                        (activity as? com.secretdialer.app.MainActivity)?.placeCall(num)
                        dismiss()
                        true
                    } else {
                        android.widget.Toast.makeText(requireContext(), "No speed dial set for key $digit. Set in Settings.", android.widget.Toast.LENGTH_SHORT).show()
                        false
                    }
                }
            }
        }
        binding.btnDelete.setOnClickListener {
            deleteLast()
            com.secretdialer.app.DialerHelper.playHapticFeedback(it)
        }
        binding.btnDelete.setOnLongClickListener {
            clearAll()
            true
        }
        binding.btnCall.setOnClickListener {
            val number = digits.toString()
            if (number.isNotBlank()) {
                com.secretdialer.app.DialerHelper.playHapticFeedback(it)
                (activity as? DialListener)?.onDial(number)
                dismiss()
            }
        }

        binding.btnRestoreKeypad.setOnClickListener {
            com.secretdialer.app.DialerHelper.playHapticFeedback(it)
            binding.keypadGrid.visibility = View.VISIBLE
            binding.btnCall.visibility = View.VISIBLE
            binding.btnDelete.visibility = if (digits.isEmpty()) View.INVISIBLE else View.VISIBLE
            binding.btnRestoreKeypad.visibility = View.GONE
        }

        binding.matchList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10 && binding.keypadGrid.visibility == View.VISIBLE) {
                    binding.keypadGrid.visibility = View.GONE
                    binding.btnCall.visibility = View.GONE
                    binding.btnDelete.visibility = View.GONE
                    binding.btnRestoreKeypad.visibility = View.VISIBLE
                }
            }
        })

        updateDisplay()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.consumePendingDialNumber()?.let { setNumber(it) }
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.peekHeight = resources.displayMetrics.heightPixels
                bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    fun setNumber(number: String) {
        digits.clear()
        digits.append(number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' })
        _binding?.let { b ->
            b.keypadGrid.visibility = View.VISIBLE
            b.btnCall.visibility = View.VISIBLE
            b.btnDelete.visibility = if (digits.isEmpty()) View.INVISIBLE else View.VISIBLE
            b.btnRestoreKeypad.visibility = View.GONE
        }
        updateDisplay()
    }

    private fun appendDigit(d: String) {
        if (digits.length >= 20) return
        digits.append(d)
        updateDisplay()
    }

    private fun deleteLast() {
        if (digits.isNotEmpty()) {
            digits.deleteCharAt(digits.length - 1)
            updateDisplay()
        }
    }

    private fun clearAll() {
        digits.clear()
        _binding?.let { b ->
            b.keypadGrid.visibility = View.VISIBLE
            b.btnCall.visibility = View.VISIBLE
            b.btnDelete.visibility = View.INVISIBLE
            b.btnRestoreKeypad.visibility = View.GONE
        }
        updateDisplay()
    }

    private fun updateDisplay() {
        val raw = digits.toString()
        binding.tvNumber.text = formatDisplay(raw)
        binding.btnDelete.visibility = if (raw.isEmpty()) View.INVISIBLE else View.VISIBLE
        updateMatches(raw)
        (activity as? DialListener)?.onNumberChanged(raw)
    }

    private fun updateMatches(raw: String) {
        val digitQuery = raw.filter { it.isDigit() }
        if (digitQuery.isEmpty()) {
            binding.matchList.visibility = View.GONE
            matchAdapter.submitList(emptyList())
            return
        }
        val currentSeq = ++searchSequence
        matchDebouncer.submit {
            val snapshot = contactIndex
            matchExecutor.execute {
                if (currentSeq != searchSequence) return@execute
                val frequencies = com.secretdialer.app.ContactResolver.queryCallFrequencies(requireContext())
                val matches = snapshot.filterByKeypad(digitQuery, frequencies = frequencies)
                if (!isAdded) return@execute
                if (currentSeq != searchSequence) return@execute
                activity?.runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    if (currentSeq == searchSequence) {
                        matchAdapter.searchQuery = digitQuery
                        matchAdapter.submitList(matches)
                        b.matchList.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun formatDisplay(raw: String): String {
        if (raw.isEmpty()) return ""
        val onlyDigits = raw.filter { it.isDigit() }
        if (onlyDigits.length == 10 && !raw.contains("+")) {
            return "${onlyDigits.take(3)} ${onlyDigits.drop(3).take(3)} ${onlyDigits.takeLast(4)}"
        }
        return raw
    }

    override fun onDestroyView() {
        ContactCache.removeListener(contactCacheListener)
        matchDebouncer.cancel()
        super.onDestroyView()
        _binding = null
    }
}
