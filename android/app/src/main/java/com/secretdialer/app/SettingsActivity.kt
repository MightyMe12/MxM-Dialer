package com.secretdialer.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.content.ComponentName
import android.provider.Settings
import com.secretdialer.app.service.CallRecordingAccessibilityService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.secretdialer.app.databinding.ActivitySettingsBinding
import java.util.Stack

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val panelStack = Stack<View>()
    private lateinit var specifiedAdapter: SpecifiedNumbersAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup Back Stack Navigation
        panelStack.push(binding.panelMainSettings)
        binding.toolbar.setNavigationOnClickListener { navigateBack() }

        // Setup Main Settings Buttons
        binding.rowSpeedDial.setOnClickListener {
            loadSpeedDialPanel()
            navigateTo(binding.panelSpeedDial, "Speed dial")
        }
        binding.rowAnswerEndCalls.setOnClickListener { navigateTo(binding.panelAnswerEndCalls, "Answer/End calls") }
        binding.rowCallRecordingNav.setOnClickListener {
            updateCallRecordingPanelUi()
            navigateTo(binding.panelCallRecording, "Call recording")
        }
        binding.rowBlockFilterNav.setOnClickListener {
            updateBlockFilterPanelUi()
            navigateTo(binding.panelBlockFilter, "Block & filter")
        }

        // Block & Filter Sub-panel navigation
        binding.rowBlockCallsLink.setOnClickListener { navigateTo(binding.panelBlockCalls, "Block calls") }
        binding.rowBlocklistLink.setOnClickListener {
            loadBlocklistRecycler()
            navigateTo(binding.panelBlocklist, "Blocklist")
        }
        binding.rowAllowlistLink.setOnClickListener {
            loadAllowlistRecycler()
            navigateTo(binding.panelAllowlist, "Allowlist")
        }
        binding.rowBlockNotification.setOnClickListener { showBlockNotificationChooser() }
        binding.rowBlockRegion.setOnClickListener { showRegionBlockChooser() }

        // Block calls switches bindings
        binding.switchBlockAllCalls.isChecked = Prefs.isBlockAllCalls(this)
        binding.switchBlockAllCalls.setOnCheckedChangeListener { _, checked ->
            Prefs.setBlockAllCalls(this, checked)
        }
        binding.switchBlockUnknown.isChecked = Prefs.isBlockUnknown(this)
        binding.switchBlockUnknown.setOnCheckedChangeListener { _, checked ->
            Prefs.setBlockUnknown(this, checked)
        }
        binding.switchBlockOneRing.isChecked = Prefs.isBlockOneRing(this)
        binding.switchBlockOneRing.setOnCheckedChangeListener { _, checked ->
            Prefs.setBlockOneRing(this, checked)
        }
        binding.switchBlockPrivate.isChecked = Prefs.isBlockPrivate(this)
        binding.switchBlockPrivate.setOnCheckedChangeListener { _, checked ->
            Prefs.setBlockPrivate(this, checked)
        }

        // Add actions
        binding.btnBlocklistAdd.setOnClickListener { showAddBlocklistDialog() }
        binding.btnAllowlistAdd.setOnClickListener { showAddAllowlistDialog() }
        binding.rowOperatorSettings.setOnClickListener {
            Toast.makeText(this, "Operator-related settings are managed by your SIM carrier", Toast.LENGTH_SHORT).show()
        }
        binding.rowMoreSettingsNav.setOnClickListener { navigateTo(binding.panelMoreSettings, "More settings") }
        
        // Edge lighting preference switch
        binding.switchEdgeLighting.isChecked = Prefs.isEdgeLightingEnabled(this)
        binding.switchEdgeLighting.setOnCheckedChangeListener { _, checked ->
            Prefs.setEdgeLightingEnabled(this, checked)
        }

        // Mobile Network settings link
        binding.rowMobileNetwork.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                startActivity(intent)
            }
        }

        // Default Dialer Status button
        binding.btnSetDefaultDialer.setOnClickListener {
            DialerHelper.defaultDialerIntent(this)?.let { startActivity(it) }
        }

        // ── Call Recording Panel Switches ──
        val mode = Prefs.getAutoRecordMode(this)
        binding.switchRecordAll.isChecked = (mode == 1)
        binding.switchRecordUnknown.isChecked = (mode == 2)

        binding.switchRecordAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                Prefs.setAutoRecordMode(this, 1)
                binding.switchRecordUnknown.isChecked = false
            } else if (Prefs.getAutoRecordMode(this) == 1) {
                Prefs.setAutoRecordMode(this, 0)
            }
            updateCallRecordingPanelUi()
        }

        binding.switchRecordUnknown.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                Prefs.setAutoRecordMode(this, 2)
                binding.switchRecordAll.isChecked = false
            } else if (Prefs.getAutoRecordMode(this) == 2) {
                Prefs.setAutoRecordMode(this, 0)
            }
            updateCallRecordingPanelUi()
        }

        binding.rowRecordSpecified.setOnClickListener {
            setupSpecifiedNumbersList()
            navigateTo(binding.panelRecordSpecified, "Record specified numbers")
        }

        // Auto Delete Recordings
        updateAutoDeleteText()
        binding.rowAutoDelete.setOnClickListener {
            val options = arrayOf("Never", "After 1 day", "After 7 days", "After 30 days")
            val values = intArrayOf(0, 1, 7, 30)
            val currentDays = Prefs.getAutoDeleteDays(this)
            val selectedIndex = values.indexOf(currentDays).coerceAtLeast(0)
            showModernSelectionDialog("Auto Delete Recordings", options, selectedIndex) { which ->
                Prefs.setAutoDeleteDays(this, values[which])
                updateAutoDeleteText()
            }
        }

        // Recording Source Chooser
        updateRecordingSourceText()
        binding.rowRecordingSource.setOnClickListener {
            val options = arrayOf(
                "System Digital Mix (VOICE_CALL)",
                "VoIP / Communication (VOICE_COMMUNICATION)",
                "Voice Recognition (VOICE_RECOGNITION Bypass)",
                "Standard Microphone (MIC - Requires Speaker)",
                "Camcorder Microphone (CAMCORDER - Clear Speaker)"
            )
            val values = intArrayOf(4, 7, 6, 1, 5)
            val current = Prefs.getRecordingSource(this)
            val selectedIndex = values.indexOf(current).coerceAtLeast(0)
            showModernSelectionDialog("Audio Recording Source", options, selectedIndex) { which ->
                Prefs.setRecordingSource(this, values[which])
                updateRecordingSourceText()
            }
        }

        // Accessibility Helper Chooser
        updateAccessibilityHelperText()
        binding.rowAccessibilityHelper.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find 'MxM Call Recorder Service Helper' and turn it ON.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Accessibility Settings", Toast.LENGTH_SHORT).show()
            }
        }

        // Force Speaker switch binding
        binding.switchForceSpeaker.isChecked = Prefs.isForceSpeakerEnabled(this)
        binding.switchForceSpeaker.setOnCheckedChangeListener { _, checked ->
            Prefs.setForceSpeakerEnabled(this, checked)
        }

        // ── Answer/End Calls Panel Switches ──
        binding.switchPowerButtonEndCall.isChecked = Prefs.isPowerButtonEndCallEnabled(this)
        binding.switchPowerButtonEndCall.setOnCheckedChangeListener { _, checked ->
            Prefs.setPowerButtonEndCallEnabled(this, checked)
        }

        // ── More Settings Panel Switches ──
        binding.switchHapticFeedback.isChecked = Prefs.isHapticFeedbackEnabled(this)
        binding.switchHapticFeedback.setOnCheckedChangeListener { _, checked ->
            Prefs.setHapticFeedbackEnabled(this, checked)
        }

        binding.switchFlipToSilence.isChecked = Prefs.isFlipToSilenceEnabled(this)
        binding.switchFlipToSilence.setOnCheckedChangeListener { _, checked ->
            Prefs.setFlipToSilenceEnabled(this, checked)
        }

        binding.switchFlashAlert.isChecked = Prefs.isFlashAlertEnabled(this)
        binding.switchFlashAlert.setOnCheckedChangeListener { _, checked ->
            Prefs.setFlashAlertEnabled(this, checked)
        }

        binding.rowAnalytics.setOnClickListener { showAnalyticsDashboard() }

        // Initialize Specified Numbers Adapter
        specifiedAdapter = SpecifiedNumbersAdapter { removedNum ->
            val set = Prefs.getAutoRecordSpecificNumbers(this).toMutableSet()
            set.remove(removedNum)
            Prefs.setAutoRecordSpecificNumbers(this, set)
            updateSpecifiedNumbersList()
        }
    }

    private fun navigateTo(panel: View, title: String) {
        binding.panelMainSettings.visibility = View.GONE
        binding.panelCallRecording.visibility = View.GONE
        binding.panelRecordSpecified.visibility = View.GONE
        binding.panelAnswerEndCalls.visibility = View.GONE
        binding.panelMoreSettings.visibility = View.GONE
        binding.panelSpeedDial.visibility = View.GONE
        binding.panelBlockFilter.visibility = View.GONE
        binding.panelBlockCalls.visibility = View.GONE
        binding.panelBlocklist.visibility = View.GONE
        binding.panelAllowlist.visibility = View.GONE

        panel.visibility = View.VISIBLE
        binding.toolbar.title = title
        panelStack.push(panel)
    }

    private fun navigateBack() {
        if (panelStack.size > 1) {
            val current = panelStack.pop()
            current.visibility = View.GONE
            val previous = panelStack.peek()
            previous.visibility = View.VISIBLE

            binding.toolbar.title = when (previous.id) {
                R.id.panelMainSettings -> getString(R.string.settings)
                R.id.panelCallRecording -> "Call recording"
                R.id.panelRecordSpecified -> "Record specified numbers"
                R.id.panelAnswerEndCalls -> "Answer/End calls"
                R.id.panelMoreSettings -> "More settings"
                R.id.panelSpeedDial -> "Speed dial"
                R.id.panelBlockFilter -> "Block & filter"
                R.id.panelBlockCalls -> "Block calls"
                R.id.panelBlocklist -> "Blocklist"
                R.id.panelAllowlist -> "Allowlist"
                else -> getString(R.string.settings)
            }
            updateCallRecordingPanelUi()
            if (previous.id == R.id.panelBlockFilter) {
                updateBlockFilterPanelUi()
            }
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelStack.size > 1) {
            navigateBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityHelperText()
    }

    private fun updateCallRecordingPanelUi() {
        val count = Prefs.getAutoRecordSpecificNumbers(this).size
        binding.tvSpecifiedCount.text = "$count Number(s)"
        updateRecordingSourceText()
        updateAccessibilityHelperText()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?: return false
        for (info in list) {
            val component = ComponentName(info.resolveInfo.serviceInfo.packageName, info.resolveInfo.serviceInfo.name)
            if (component.packageName == packageName && component.className.contains("CallRecordingAccessibilityService")) {
                return true
            }
        }
        return false
    }

    private fun updateAccessibilityHelperText() {
        val enabled = isAccessibilityServiceEnabled()
        binding.tvAccessibilityHelperStatus.text = if (enabled) "Enabled" else "Disabled"
        binding.tvAccessibilityHelperStatus.setTextColor(
            Color.parseColor(if (enabled) "#30D158" else "#8E8E93")
        )
    }

    private fun updateRecordingSourceText() {
        val src = Prefs.getRecordingSource(this)
        binding.tvRecordingSourceValue.text = when (src) {
            4 -> "Digital Mix (VOICE_CALL)"
            7 -> "VoIP/Communication"
            6 -> "Voice Recognition (Bypass)"
            1 -> "Standard Microphone (MIC)"
            5 -> "Camcorder Microphone"
            else -> "VoIP/Communication"
        }
    }

    private fun updateAutoDeleteText() {
        val days = Prefs.getAutoDeleteDays(this)
        binding.tvAutoDeleteValue.text = when (days) {
            0 -> "Never"
            1 -> "After 1 day"
            7 -> "After 7 days"
            30 -> "After 30 days"
            else -> "Never"
        }
    }

    private fun setupSpecifiedNumbersList() {
        binding.recyclerSpecifiedNumbers.layoutManager = LinearLayoutManager(this)
        binding.recyclerSpecifiedNumbers.adapter = specifiedAdapter
        updateSpecifiedNumbersList()

        binding.btnAddNewRecordNumber.setOnClickListener {
            val allContacts = ContactCache.current().contacts
            if (allContacts.isEmpty()) {
                Toast.makeText(this, "No contacts loaded yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showContactPickerDialog(allContacts)
        }
    }

    private fun updateSpecifiedNumbersList() {
        val numbersSet = Prefs.getAutoRecordSpecificNumbers(this)
        val allContacts = ContactCache.current().contacts
        val list = mutableListOf<ContactInfo>()
        for (num in numbersSet) {
            val cleanNum = num.filter { it.isDigit() }
            val matchedContact = allContacts.find { it.number.filter { c -> c.isDigit() } == cleanNum }
            if (matchedContact != null) {
                list.add(matchedContact)
            } else {
                list.add(ContactInfo(0, num, num, null))
            }
        }
        specifiedAdapter.submitList(list)
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private fun showContactPickerDialog(allContacts: List<ContactInfo>) {
        val savedSet = Prefs.getAutoRecordSpecificNumbers(this)
        val selected = mutableMapOf<String, ContactInfo>()
        for (c in allContacts) {
            val key = c.number.filter { it.isDigit() }
            if (savedSet.any { it.filter { ch -> ch.isDigit() } == key }) {
                selected[key] = c
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_SecretDialer_FullDialog).create()
        dialog.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.BOTTOM)
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp + 0.5f).toInt()

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8.dp(), 16.dp(), 16.dp(), 8.dp())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        val btnBack = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            setOnClickListener { dialog.dismiss() }
        }
        val tvTitle = android.widget.TextView(this).apply {
            text = "Select Contacts"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
        }
        val tvSave = android.widget.TextView(this).apply {
            text = "Save (${selected.size})"
            textSize = 15f
            setTextColor(Color.parseColor("#30D158"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            setOnClickListener {
                Prefs.setAutoRecordSpecificNumbers(this@SettingsActivity, selected.values.map { it.number }.toSet())
                if (selected.isNotEmpty()) {
                    Prefs.setAutoRecordMode(this@SettingsActivity, 3)
                }
                updateSpecifiedNumbersList()
                dialog.dismiss()
            }
        }
        topBar.addView(btnBack, android.widget.LinearLayout.LayoutParams(40.dp(), 40.dp()))
        topBar.addView(tvTitle)
        topBar.addView(tvSave)
        root.addView(topBar)

        val searchBox = android.widget.EditText(this).apply {
            hint = "Search contacts…"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val searchWrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 0, 16.dp(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 1.dp() }
        }
        searchWrap.addView(searchBox)
        root.addView(searchWrap)

        val divider = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        root.addView(divider)

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(recyclerView)

        val adapter = ContactPickerAdapter(allContacts, selected) { count ->
            tvSave.text = "Save ($count)"
        }
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim().lowercase()
                val currentContacts = if (q.isEmpty()) allContacts
                else allContacts.filter {
                    it.name.lowercase().contains(q) || it.number.contains(q)
                }
                adapter.update(currentContacts)
            }
        })

        dialog.setView(root)
        dialog.show()
    }

    private class ContactPickerAdapter(
        private var items: List<ContactInfo>,
        private val selected: MutableMap<String, ContactInfo>,
        private val onSelectionChanged: (Int) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ContactPickerAdapter.VH>() {

        class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val avatar: android.widget.FrameLayout = v.findViewById(R.id.avatarContainer)
            val tvInit: TextView = v.findViewById(R.id.tvAvatarInitials)
            val ivPhoto: android.widget.ImageView = v.findViewById(R.id.ivAvatarPhoto)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvSub: TextView = v.findViewById(R.id.tvSub)
            val ivCheck: android.widget.ImageView = v.findViewById(R.id.ivCheck)
        }

        fun update(newList: List<ContactInfo>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val key = item.number.filter { it.isDigit() }
            val isChecked = selected.containsKey(key)

            holder.tvName.text = item.name
            holder.tvSub.text = item.number
            holder.tvInit.text = DialerHelper.getInitials(item.name)
            holder.avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
                DialerHelper.getAvatarColor(item.name)
            )

            if (item.photoUri != null) {
                val bitmap = DialerHelper.loadContactPhoto(holder.itemView.context, item.photoUri)
                if (bitmap != null) {
                    holder.ivPhoto.visibility = View.VISIBLE
                    holder.ivPhoto.setImageBitmap(bitmap)
                    holder.tvInit.visibility = View.GONE
                } else {
                    holder.ivPhoto.visibility = View.GONE
                    holder.tvInit.visibility = View.VISIBLE
                }
            } else {
                holder.ivPhoto.visibility = View.GONE
                holder.tvInit.visibility = View.VISIBLE
            }

            holder.ivCheck.visibility = if (isChecked) View.VISIBLE else View.INVISIBLE

            holder.itemView.setOnClickListener {
                val nowChecked = !selected.containsKey(key)
                if (nowChecked) {
                    selected[key] = item
                } else {
                    selected.remove(key)
                }
                holder.ivCheck.visibility = if (nowChecked) View.VISIBLE else View.INVISIBLE
                onSelectionChanged(selected.size)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun showModernSelectionDialog(
        title: String,
        options: Array<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val dialog = AlertDialog.Builder(this).create()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_modern_dialog)
            setPadding(48, 48, 48, 48)
        }

        val tvTitle = android.widget.TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 32)
        }
        container.addView(tvTitle)

        options.forEachIndexed { index, option ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(16, 24, 16, 24)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    onSelected(index)
                    dialog.dismiss()
                }
            }

            val tvOption = android.widget.TextView(this).apply {
                text = option
                setTextColor(if (index == selectedIndex) Color.parseColor("#30D158") else Color.WHITE)
                textSize = 16f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvOption)

            if (index == selectedIndex) {
                val ivCheck = android.widget.ImageView(this).apply {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#30D158"))
                }
                row.addView(ivCheck)
            }

            container.addView(row)

            if (index < options.size - 1) {
                val sep = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        topMargin = 4
                        bottomMargin = 4
                    }
                    setBackgroundColor(Color.parseColor("#2C2C2E"))
                }
                container.addView(sep)
            }
        }

        val btnCancel = android.widget.TextView(this).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#FF453A"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnCancel)

        dialog.setView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun updateBlockFilterPanelUi() {
        val blockedCount = Prefs.getBlockedNumbers(this).size
        binding.tvBlocklistCount.text = "$blockedCount item" + (if (blockedCount == 1) "" else "s")
        
        val allowCount = Prefs.getAllowlistNumbers(this).size
        binding.tvAllowlistCount.text = "$allowCount item" + (if (allowCount == 1) "" else "s")
        
        val notifMode = Prefs.getBlockNotificationMode(this)
        binding.tvBlockNotificationVal.text = when(notifMode) {
            0 -> "Notify me"
            1 -> "Do not notify"
            else -> "Notify for non-blocklist numbers"
        }
        binding.tvBlockRegionVal.text = Prefs.getBlockRegion(this)
    }

    private fun showBlockNotificationChooser() {
        val options = arrayOf("Notify me", "Do not notify", "Notify for non-blocklist numbers")
        val current = Prefs.getBlockNotificationMode(this)
        showModernSelectionDialog("When calls/messages blocked", options, current) { selected ->
            Prefs.setBlockNotificationMode(this, selected)
            updateBlockFilterPanelUi()
        }
    }

    private fun showRegionBlockChooser() {
        val options = arrayOf("None", "India (+91)", "USA (+1)", "UK (+44)", "Other prefix...")
        val currentStr = Prefs.getBlockRegion(this)
        val selectedIdx = when (currentStr) {
            "None" -> 0
            "+91" -> 1
            "+1" -> 2
            "+44" -> 3
            else -> 4
        }
        showModernSelectionDialog("Block calls by region", options, selectedIdx) { selected ->
            when (selected) {
                0 -> {
                    Prefs.setBlockRegion(this, "None")
                    updateBlockFilterPanelUi()
                }
                1 -> {
                    Prefs.setBlockRegion(this, "+91")
                    updateBlockFilterPanelUi()
                }
                2 -> {
                    Prefs.setBlockRegion(this, "+1")
                    updateBlockFilterPanelUi()
                }
                3 -> {
                    Prefs.setBlockRegion(this, "+44")
                    updateBlockFilterPanelUi()
                }
                4 -> {
                    val builder = AlertDialog.Builder(this)
                    builder.setTitle("Enter custom region prefix")
                    val input = android.widget.EditText(this).apply {
                        hint = "e.g. +33"
                        setHintTextColor(Color.parseColor("#8E8E93"))
                        setTextColor(Color.WHITE)
                        setBackgroundColor(Color.parseColor("#1C1C1E"))
                        setPadding(48, 32, 48, 32)
                    }
                    builder.setView(input)
                    builder.setPositiveButton("Block") { dialog, _ ->
                        val code = input.text.toString().trim()
                        if (code.isNotEmpty()) {
                            Prefs.setBlockRegion(this, code)
                            updateBlockFilterPanelUi()
                        }
                        dialog.dismiss()
                    }
                    builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    builder.show()
                }
            }
        }
    }

    private fun loadBlocklistRecycler() {
        val blocked = Prefs.getBlockedNumbers(this).toList().sorted()
        binding.recyclerBlocklist.layoutManager = LinearLayoutManager(this)
        
        class BlocklistVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        }

        class BlocklistAdapter(private val items: List<String>) : RecyclerView.Adapter<BlocklistVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlocklistVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_number, parent, false)
                return BlocklistVH(v)
            }
            override fun onBindViewHolder(holder: BlocklistVH, position: Int) {
                val num = items[position]
                holder.tvTitle.text = num
                holder.itemView.setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Remove Number")
                        .setMessage("Do you want to unblock $num?")
                        .setPositiveButton("Unblock") { d, _ ->
                            val current = Prefs.getBlockedNumbers(this@SettingsActivity).toMutableSet()
                            current.remove(num)
                            Prefs.setBlockedNumbers(this@SettingsActivity, current)
                            loadBlocklistRecycler()
                            d.dismiss()
                        }
                        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                        .show()
                }
            }
            override fun getItemCount() = items.size
        }
        
        binding.recyclerBlocklist.adapter = BlocklistAdapter(blocked)
    }

    private fun loadAllowlistRecycler() {
        val allowed = Prefs.getAllowlistNumbers(this).toList().sorted()
        binding.recyclerAllowlist.layoutManager = LinearLayoutManager(this)
        
        val allContacts = ContactCache.current().contacts

        class AllowlistVH(v: View) : RecyclerView.ViewHolder(v) {
            val tvTitle: TextView = v.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = v.findViewById(R.id.tvSubtitle)
        }

        class AllowlistAdapter(private val items: List<String>) : RecyclerView.Adapter<AllowlistVH>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AllowlistVH {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_blocked_number, parent, false)
                return AllowlistVH(v)
            }
            override fun onBindViewHolder(holder: AllowlistVH, position: Int) {
                val num = items[position]
                val clean = num.filter { it.isDigit() }
                val contact = allContacts.find { it.number.filter { c -> c.isDigit() } == clean }
                holder.tvTitle.text = contact?.name ?: num
                if (contact != null) {
                    holder.tvSubtitle.text = num
                    holder.tvSubtitle.visibility = View.VISIBLE
                } else {
                    holder.tvSubtitle.visibility = View.GONE
                }
                holder.itemView.setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Remove Contact")
                        .setMessage("Remove ${contact?.name ?: num} from allowlist?")
                        .setPositiveButton("Remove") { d, _ ->
                            val current = Prefs.getAllowlistNumbers(this@SettingsActivity).toMutableSet()
                            current.remove(num)
                            Prefs.setAllowlistNumbers(this@SettingsActivity, current)
                            loadAllowlistRecycler()
                            d.dismiss()
                        }
                        .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
                        .show()
                }
            }
            override fun getItemCount() = items.size
        }
        
        binding.recyclerAllowlist.adapter = AllowlistAdapter(allowed)
    }

    private fun showAddBlocklistDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add to Blocklist")
        val input = android.widget.EditText(this).apply {
            hint = "Enter number"
            setHintTextColor(Color.parseColor("#8E8E93"))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(48, 32, 48, 32)
        }
        builder.setView(input)
        builder.setPositiveButton("Block") { dialog, _ ->
            val num = input.text.toString().trim()
            if (num.isNotEmpty()) {
                val current = Prefs.getBlockedNumbers(this).toMutableSet()
                current.add(num)
                Prefs.setBlockedNumbers(this, current)
                loadBlocklistRecycler()
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun showAddAllowlistDialog() {
        val allContacts = ContactCache.current().contacts
        if (allContacts.isEmpty()) {
            Toast.makeText(this, "No contacts loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_SecretDialer_FullDialog).create()
        dialog.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.BOTTOM)
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp + 0.5f).toInt()

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8.dp(), 16.dp(), 16.dp(), 8.dp())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        val btnBack = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            setOnClickListener { dialog.dismiss() }
        }
        val tvTitle = android.widget.TextView(this).apply {
            text = "Select Contact for Allowlist"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
        }
        topBar.addView(btnBack, android.widget.LinearLayout.LayoutParams(40.dp(), 40.dp()))
        topBar.addView(tvTitle)
        root.addView(topBar)

        val searchBox = android.widget.EditText(this).apply {
            hint = "Search contacts…"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val searchWrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 0, 16.dp(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 1.dp() }
        }
        searchWrap.addView(searchBox)
        root.addView(searchWrap)

        val divider = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        root.addView(divider)

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(recyclerView)

        val adapter = SpeedDialPickerAdapter(allContacts) { contact ->
            val current = Prefs.getAllowlistNumbers(this).toMutableSet()
            current.add(contact.number)
            Prefs.setAllowlistNumbers(this, current)
            loadAllowlistRecycler()
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim().lowercase()
                val currentContacts = if (q.isEmpty()) allContacts
                else allContacts.filter {
                    it.name.lowercase().contains(q) || it.number.contains(q)
                }
                adapter.update(currentContacts)
            }
        })

        dialog.setView(root)
        dialog.show()
    }

    private data class SpeedDialIds(
        val plusId: Int,
        val initialsId: Int,
        val labelId: Int,
        val avatarId: Int,
        val layoutId: Int
    )

    private fun loadSpeedDialPanel() {
        val allContacts = ContactCache.current().contacts
        
        val itemIds = mapOf(
            2 to SpeedDialIds(R.id.plus2, R.id.initials2, R.id.label2, R.id.avatar2, R.id.speedDial2),
            3 to SpeedDialIds(R.id.plus3, R.id.initials3, R.id.label3, R.id.avatar3, R.id.speedDial3),
            4 to SpeedDialIds(R.id.plus4, R.id.initials4, R.id.label4, R.id.avatar4, R.id.speedDial4),
            5 to SpeedDialIds(R.id.plus5, R.id.initials5, R.id.label5, R.id.avatar5, R.id.speedDial5),
            6 to SpeedDialIds(R.id.plus6, R.id.initials6, R.id.label6, R.id.avatar6, R.id.speedDial6),
            7 to SpeedDialIds(R.id.plus7, R.id.initials7, R.id.label7, R.id.avatar7, R.id.speedDial7),
            8 to SpeedDialIds(R.id.plus8, R.id.initials8, R.id.label8, R.id.avatar8, R.id.speedDial8),
            9 to SpeedDialIds(R.id.plus9, R.id.initials9, R.id.label9, R.id.avatar9, R.id.speedDial9)
        )

        for ((key, ids) in itemIds) {
            val imgPlus = findViewById<ImageView>(ids.plusId)
            val tvInitials = findViewById<TextView>(ids.initialsId)
            val tvLabel = findViewById<TextView>(ids.labelId)
            val viewAvatar = findViewById<View>(ids.avatarId)
            val viewLayout = findViewById<View>(ids.layoutId)

            val num = Prefs.getSpeedDial(this, key)
            if (num != null) {
                imgPlus.visibility = View.GONE
                val cleanNum = num.filter { it.isDigit() }
                val contact = allContacts.find { it.number.filter { ch -> ch.isDigit() } == cleanNum }
                val nameToUse = contact?.name ?: num
                tvInitials.text = DialerHelper.getInitials(nameToUse)
                tvInitials.visibility = View.VISIBLE
                tvLabel.text = nameToUse
                viewAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    DialerHelper.getAvatarColor(nameToUse)
                )
                viewLayout.setOnClickListener {
                    showSpeedDialEditOrClearDialog(key)
                }
            } else {
                imgPlus.visibility = View.VISIBLE
                tvInitials.visibility = View.GONE
                tvLabel.text = "Add"
                viewAvatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#2C2C2E")
                )
                viewLayout.setOnClickListener {
                    showSetSpeedDialContactPickerDialog(key)
                }
            }
        }
    }

    private fun showSetSpeedDialContactPickerDialog(key: Int) {
        val allContacts = ContactCache.current().contacts
        if (allContacts.isEmpty()) {
            Toast.makeText(this, "No contacts loaded yet", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = AlertDialog.Builder(this, R.style.Theme_SecretDialer_FullDialog).create()
        dialog.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.BOTTOM)
            decorView.setBackgroundColor(Color.TRANSPARENT)
        }

        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp + 0.5f).toInt()

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8.dp(), 16.dp(), 16.dp(), 8.dp())
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0D0D0D"))
        }
        val btnBack = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            setOnClickListener { dialog.dismiss() }
        }
        val tvTitle = android.widget.TextView(this).apply {
            text = "Select Contact for Key $key"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(8.dp(), 0, 0, 0)
        }
        topBar.addView(btnBack, android.widget.LinearLayout.LayoutParams(40.dp(), 40.dp()))
        topBar.addView(tvTitle)
        root.addView(topBar)

        val searchBox = android.widget.EditText(this).apply {
            hint = "Search contacts…"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val searchWrap = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            setPadding(16.dp(), 0, 16.dp(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 1.dp() }
        }
        searchWrap.addView(searchBox)
        root.addView(searchWrap)

        val divider = View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(Color.parseColor("#2C2C2E"))
        }
        root.addView(divider)

        val recyclerView = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@SettingsActivity)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(recyclerView)

        val adapter = SpeedDialPickerAdapter(allContacts) { contact ->
            Prefs.setSpeedDial(this, key, contact.number)
            loadSpeedDialPanel()
            dialog.dismiss()
        }
        recyclerView.adapter = adapter

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty().trim().lowercase()
                val currentContacts = if (q.isEmpty()) allContacts
                else allContacts.filter {
                    it.name.lowercase().contains(q) || it.number.contains(q)
                }
                adapter.update(currentContacts)
            }
        })

        dialog.setView(root)
        dialog.show()
    }

    private class SpeedDialPickerAdapter(
        private var itemsList: List<ContactInfo>,
        private val onSelected: (ContactInfo) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SpeedDialPickerAdapter.VH>() {

        class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val avatar: android.widget.FrameLayout = v.findViewById(R.id.avatarContainer)
            val tvInit: TextView = v.findViewById(R.id.tvAvatarInitials)
            val ivPhoto: android.widget.ImageView = v.findViewById(R.id.ivAvatarPhoto)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvSub: TextView = v.findViewById(R.id.tvSub)
        }

        fun update(newList: List<ContactInfo>) {
            itemsList = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_contact_picker, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = itemsList[position]
            holder.tvName.text = item.name
            holder.tvSub.text = item.number
            holder.tvInit.text = DialerHelper.getInitials(item.name)
            holder.avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
                DialerHelper.getAvatarColor(item.name)
            )

            if (item.photoUri != null) {
                val bitmap = DialerHelper.loadContactPhoto(holder.itemView.context, item.photoUri)
                if (bitmap != null) {
                    holder.ivPhoto.visibility = View.VISIBLE
                    holder.ivPhoto.setImageBitmap(bitmap)
                    holder.tvInit.visibility = View.GONE
                } else {
                    holder.ivPhoto.visibility = View.GONE
                    holder.tvInit.visibility = View.VISIBLE
                }
            } else {
                holder.ivPhoto.visibility = View.GONE
                holder.tvInit.visibility = View.VISIBLE
            }

            holder.itemView.setOnClickListener {
                onSelected(item)
            }
        }

        override fun getItemCount(): Int = itemsList.size
    }

    private fun showSpeedDialEditOrClearDialog(key: Int) {
        val num = Prefs.getSpeedDial(this, key) ?: return
        AlertDialog.Builder(this)
            .setTitle("Speed Dial Key $key")
            .setMessage("Assigned number: $num")
            .setPositiveButton("Clear") { dialog, _ ->
                Prefs.setSpeedDial(this, key, null)
                loadSpeedDialPanel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showAnalyticsDashboard() {
        val dialog = AlertDialog.Builder(this).create()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_modern_dialog)
            setPadding(48, 48, 48, 48)
        }

        val tvTitle = android.widget.TextView(this).apply {
            text = "Call Logs Analytics"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }
        container.addView(tvTitle)

        // Generate analytics stats from logs
        var totalCalls = 0
        var incoming = 0
        var outgoing = 0
        var missed = 0
        var totalDur = 0L

        try {
            val projection = arrayOf(android.provider.CallLog.Calls.TYPE, android.provider.CallLog.Calls.DURATION)
            contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                projection,
                null, null, null
            )?.use { cursor ->
                totalCalls = cursor.count
                val typeIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                val durIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                while (cursor.moveToNext()) {
                    val t = cursor.getInt(typeIdx)
                    val d = cursor.getLong(durIdx)
                    totalDur += d
                    when (t) {
                        android.provider.CallLog.Calls.INCOMING_TYPE -> incoming++
                        android.provider.CallLog.Calls.OUTGOING_TYPE -> outgoing++
                        android.provider.CallLog.Calls.MISSED_TYPE -> missed++
                    }
                }
            }
        } catch (_: SecurityException) {}

        fun addStat(label: String, valStr: String) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            val tvL = android.widget.TextView(this).apply {
                text = label; setTextColor(Color.parseColor("#8E8E93")); textSize = 15f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvV = android.widget.TextView(this).apply {
                text = valStr; setTextColor(Color.WHITE); textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(tvL)
            row.addView(tvV)
            container.addView(row)
        }

        addStat("Total Calls logged", "$totalCalls")
        addStat("Incoming Calls", "$incoming")
        addStat("Outgoing Calls", "$outgoing")
        addStat("Missed Calls", "$missed")
        val mins = totalDur / 60
        addStat("Total Talk Time", "${mins} mins")

        val btnClose = android.widget.TextView(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 0)
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(btnClose)

        dialog.setView(container)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    // ── Specified Numbers List RecyclerView Adapter ──
    private class SpecifiedNumbersAdapter(
        private val onDeleteClicked: (String) -> Unit
    ) : RecyclerView.Adapter<SpecifiedNumbersAdapter.ViewHolder>() {

        private var items = emptyList<ContactInfo>()

        fun submitList(list: List<ContactInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            fun Int.dp() = (this * dp + 0.5f).toInt()

            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(16.dp(), 12.dp(), 16.dp(), 12.dp())
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val avatar = android.widget.FrameLayout(ctx).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(40.dp(), 40.dp())
                background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_avatar_circle)
                clipToOutline = true
            }
            val tvInit = android.widget.TextView(ctx).apply {
                gravity = android.view.Gravity.CENTER
                textSize = 15f
                setTextColor(Color.WHITE)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            avatar.addView(tvInit)
            row.addView(avatar)

            val textBlock = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12.dp()
                }
            }
            val tvName = android.widget.TextView(ctx).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            val tvNumber = android.widget.TextView(ctx).apply {
                textSize = 13f
                setTextColor(Color.parseColor("#8E8E93"))
            }
            textBlock.addView(tvName)
            textBlock.addView(tvNumber)
            row.addView(textBlock)

            val btnDelete = android.widget.ImageView(ctx).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF453A"))
                layoutParams = android.widget.LinearLayout.LayoutParams(32.dp(), 32.dp())
                setPadding(4.dp(), 4.dp(), 4.dp(), 4.dp())
            }
            row.addView(btnDelete)

            return ViewHolder(row, avatar, tvInit, tvName, tvNumber, btnDelete)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = items[position]
            holder.tvName.text = contact.name
            holder.tvNumber.text = contact.number

            holder.tvInit.text = DialerHelper.getInitials(contact.name)
            holder.avatar.backgroundTintList = android.content.res.ColorStateList.valueOf(
                DialerHelper.getAvatarColor(contact.name)
            )

            holder.btnDelete.setOnClickListener {
                onDeleteClicked(contact.number)
            }
        }

        override fun getItemCount() = items.size

        class ViewHolder(
            view: View,
            val avatar: View,
            val tvInit: TextView,
            val tvName: TextView,
            val tvNumber: TextView,
            val btnDelete: View
        ) : RecyclerView.ViewHolder(view)
    }
}
