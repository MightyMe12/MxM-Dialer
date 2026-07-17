package com.secretdialer.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.secretdialer.app.databinding.ActivityMainBinding
import com.secretdialer.app.ui.ContactsFragment
import com.secretdialer.app.ui.FavoritesFragment
import com.secretdialer.app.ui.KeypadFragment
import com.secretdialer.app.ui.RecentsFragment

class MainActivity : AppCompatActivity(), KeypadFragment.DialListener {

    private lateinit var binding: ActivityMainBinding
    var pendingDialNumber: String? = null
        private set

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
        } else {
            ContactCache.invalidate()
            ContactCache.preload(this)
            promptDefaultDialerIfNeeded()
        }
    }

    private val defaultDialerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!DialerHelper.isDefaultDialer(this)) {
            Toast.makeText(this, R.string.set_default_dialer_hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        ContactCache.preload(this)
        binding.btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        handleDialIntent(intent)
        binding.root.post { requestPermissionsIfNeeded() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun setupTabs() {
        binding.pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> FavoritesFragment()
                1 -> RecentsFragment()
                else -> ContactsFragment()
            }
        }
        binding.pager.isUserInputEnabled = true
        binding.pager.offscreenPageLimit = 1
        val savedTab = Prefs.getSavedTab(this).coerceIn(0, 2)
        binding.pager.setCurrentItem(savedTab, false)

        binding.pager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                Prefs.setSavedTab(this@MainActivity, position)
                if (position == 1) {
                    markMissedCallsAsRead()
                }
            }
        })

        TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_favorites)
                1 -> getString(R.string.tab_recents)
                else -> getString(R.string.tab_contacts)
            }
            tab.setIcon(
                when (position) {
                    0 -> R.drawable.ic_star
                    1 -> R.drawable.ic_clock
                    else -> R.drawable.ic_person
                }
            )
        }.attach()
    }

    override fun onResume() {
        super.onResume()
        updateMissedCallBadge()
        // If already on Recents tab, mark them read
        if (binding.pager.currentItem == 1) {
            markMissedCallsAsRead()
        }
    }

    fun updateMissedCallBadge() {
        val count = getUnreadMissedCallsCount()
        val recentsTab = binding.tabLayout.getTabAt(1)
        if (recentsTab != null) {
            if (count > 0) {
                val badge = recentsTab.orCreateBadge
                badge.number = count
                badge.backgroundColor = android.graphics.Color.RED
                badge.isVisible = true
            } else {
                recentsTab.removeBadge()
            }
        }
    }

    private fun getUnreadMissedCallsCount(): Int {
        var count = 0
        try {
            val projection = arrayOf(android.provider.CallLog.Calls._ID)
            val selection = "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.IS_READ} = ?"
            val selectionArgs = arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), "0")
            contentResolver.query(
                android.provider.CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                count = cursor.count
            }
        } catch (_: SecurityException) {}
        return count
    }

    fun markMissedCallsAsRead() {
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.CallLog.Calls.IS_READ, 1)
            }
            val selection = "${android.provider.CallLog.Calls.TYPE} = ? AND ${android.provider.CallLog.Calls.IS_READ} = ?"
            val selectionArgs = arrayOf(android.provider.CallLog.Calls.MISSED_TYPE.toString(), "0")
            contentResolver.update(
                android.provider.CallLog.Calls.CONTENT_URI,
                values,
                selection,
                selectionArgs
            )
            updateMissedCallBadge()
        } catch (_: SecurityException) {}
    }

    private fun handleDialIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data?.scheme == "tel") {
            val number = data.schemeSpecificPart ?: return
            pendingDialNumber = number
            binding.pager.setCurrentItem(3, true)
            if (intent.action == Intent.ACTION_CALL) {
                placeCall(number)
            }
        }
    }

    fun consumePendingDialNumber(): String? {
        val n = pendingDialNumber
        pendingDialNumber = null
        return n
    }

    private fun requestPermissionsIfNeeded() {
        val missing = DialerHelper.requiredPermissions().filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            promptDefaultDialerIfNeeded()
        }
    }

    private fun promptDefaultDialerIfNeeded() {
        if (DialerHelper.isDefaultDialer(this)) return
        if (Prefs.isDefaultDialerPromptShown(this)) return
        Prefs.setDefaultDialerPromptShown(this)
        DialerHelper.defaultDialerIntent(this)?.let { defaultDialerLauncher.launch(it) }
    }

    override fun onDial(number: String) {
        placeCall(number)
    }

    override fun onNumberChanged(number: String) {}

    fun placeCall(number: String, isVideo: Boolean = false) {
        if (!DialerHelper.isDefaultDialer(this)) {
            Toast.makeText(this, R.string.set_default_dialer_hint, Toast.LENGTH_LONG).show()
            DialerHelper.defaultDialerIntent(this)?.let { defaultDialerLauncher.launch(it) }
            return
        }
        val missing = DialerHelper.missingPermissions(this, isVideo)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        DialerHelper.placeCall(this, number, isVideo)
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.isAppInForeground = true
    }

    override fun onStop() {
        AppForegroundTracker.isAppInForeground = false
        super.onStop()
    }

    private fun getContactDetails(contactId: Long): Triple<String, String, String?>? {
        val resolver = contentResolver
        var name = ""
        var phone = ""
        var birthday: String? = null

        val uri = android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?"
        val selectionArgs = arrayOf(contactId.toString())
        
        try {
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: ""
                    phone = cursor.getString(1) ?: ""
                }
            }
        } catch (_: Exception) {}

        if (name.isBlank() && phone.isBlank()) {
            try {
                val nameUri = android.provider.ContactsContract.Data.CONTENT_URI
                resolver.query(
                    nameUri,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME),
                    "${android.provider.ContactsContract.Data.CONTACT_ID} = ? AND ${android.provider.ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(contactId.toString(), android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0) ?: ""
                    }
                }
            } catch (_: Exception) {}
        }

        birthday = ContactEditorHelper.getContactBirthday(this, contactId)
        return Triple(name, phone, birthday)
    }

    fun showContactEditor(contactId: Long?, initialNumber: String? = null) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val bindingDialog = com.secretdialer.app.databinding.DialogContactEditorBinding.inflate(layoutInflater)
        builder.setView(bindingDialog.root)
        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var selectedBirthday: String? = null

        if (contactId != null) {
            bindingDialog.dialogTitle.text = "Edit Contact"
            val details = getContactDetails(contactId)
            if (details != null) {
                bindingDialog.etName.setText(details.first)
                bindingDialog.etPhone.setText(details.second)
                selectedBirthday = details.third
                if (!selectedBirthday.isNullOrBlank()) {
                    bindingDialog.tvBirthdayDisplay.text = selectedBirthday
                    bindingDialog.btnClearDate.visibility = android.view.View.VISIBLE
                }
            }
        } else {
            bindingDialog.dialogTitle.text = "Add Contact"
            if (initialNumber != null) {
                bindingDialog.etPhone.setText(initialNumber)
            }
        }

        bindingDialog.btnPickDate.setOnClickListener {
            val today = java.util.Calendar.getInstance()
            var startYear = today.get(java.util.Calendar.YEAR)
            var startMonth = today.get(java.util.Calendar.MONTH)
            var startDay = today.get(java.util.Calendar.DAY_OF_MONTH)

            if (!selectedBirthday.isNullOrBlank()) {
                val parts = selectedBirthday!!.split("-")
                if (parts.size == 3) {
                    startYear = parts[0].toInt()
                    startMonth = parts[1].toInt() - 1
                    startDay = parts[2].toInt()
                } else if (parts.size == 2) {
                    startMonth = parts[0].toInt() - 1
                    startDay = parts[1].toInt()
                }
            }

            android.app.DatePickerDialog(this, { _, y, m, d ->
                val displayMonth = m + 1
                selectedBirthday = String.format("%04d-%02d-%02d", y, displayMonth, d)
                bindingDialog.tvBirthdayDisplay.text = String.format("%02d/%02d/%04d", d, displayMonth, y)
                bindingDialog.btnClearDate.visibility = android.view.View.VISIBLE
            }, startYear, startMonth, startDay).show()
        }

        bindingDialog.btnClearDate.setOnClickListener {
            selectedBirthday = null
            bindingDialog.tvBirthdayDisplay.text = "No date selected"
            bindingDialog.btnClearDate.visibility = android.view.View.GONE
        }

        bindingDialog.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        bindingDialog.btnSave.setOnClickListener {
            val name = bindingDialog.etName.text.toString().trim()
            val phone = bindingDialog.etPhone.text.toString().trim()
            if (name.isBlank()) {
                bindingDialog.etName.error = "Name cannot be empty"
                return@setOnClickListener
            }
            if (phone.isBlank()) {
                bindingDialog.etPhone.error = "Phone number cannot be empty"
                return@setOnClickListener
            }

            val success = ContactEditorHelper.saveContact(this, contactId, name, phone, selectedBirthday)
            if (success) {
                android.widget.Toast.makeText(this, "Contact saved successfully", android.widget.Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                android.widget.Toast.makeText(this, "Failed to save contact", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }
}
