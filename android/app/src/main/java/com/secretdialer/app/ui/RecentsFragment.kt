package com.secretdialer.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.secretdialer.app.ContactCache
import com.secretdialer.app.ContactResolver
import com.secretdialer.app.DialerHelper
import com.secretdialer.app.MainActivity
import com.secretdialer.app.R
import com.secretdialer.app.databinding.FragmentListBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class RecentCall(
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long = 0L,
    val photoUri: android.net.Uri? = null
)

data class RecentGroup(
    val id: String,
    val number: String,
    val name: String?,
    val photoUri: android.net.Uri?,
    val calls: List<RecentCall>
)

class RecentsFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: RecentsAdapter
    private val loadExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var loadGeneration = 0L
    @Volatile private var lastLoadAtMs = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = RecentsAdapter(
            onItemClick = { group -> (activity as? MainActivity)?.placeCall(group.number) },
            onInfoClick = { group -> showODialerDetailScreen(group) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.setHasFixedSize(true)
        binding.recycler.itemAnimator = null
        binding.recycler.adapter = adapter
        binding.searchLayout.visibility = View.GONE
        binding.emptyText.text = getString(R.string.no_recents)

        binding.fabKeypad.visibility = View.VISIBLE
        binding.fabKeypad.setOnClickListener {
            val keypad = KeypadFragment()
            keypad.show(childFragmentManager, "keypad")
        }

        loadRecents(force = true)
    }

    override fun onResume() {
        super.onResume()
        registerCallLogObserver()
        loadRecents(force = false)
    }

    override fun onPause() {
        unregisterCallLogObserver()
        super.onPause()
    }

    private var callLogObserver: android.database.ContentObserver? = null

    private fun registerCallLogObserver() {
        if (callLogObserver == null) {
            val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    loadRecents(force = true)
                }
            }
            callLogObserver = observer
            try {
                requireContext().contentResolver.registerContentObserver(
                    android.provider.CallLog.Calls.CONTENT_URI,
                    true,
                    observer
                )
            } catch (_: Exception) {}
        }
    }

    private fun unregisterCallLogObserver() {
        callLogObserver?.let {
            try {
                requireContext().contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {}
        }
        callLogObserver = null
    }

    private fun loadRecents(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadAtMs < 1500L) return
        lastLoadAtMs = now

        val ctx = requireContext().applicationContext
        val gen = ++loadGeneration
        loadExecutor.execute {
            val list = mutableListOf<RecentCall>()
            try {
                val nameIndex = ContactCache.current()
                // O(1) lookup map instead of scanning all contacts per call-log row.
                val photoByDigits = HashMap<String, android.net.Uri?>(nameIndex.contacts.size * 2)
                for (c in nameIndex.contacts) {
                    val digits = c.number.filter { it.isDigit() }
                    if (digits.isNotEmpty() && !photoByDigits.containsKey(digits)) {
                        photoByDigits[digits] = c.photoUri
                    }
                }

                val projection = arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                )
                // LIMIT keeps large call histories fast across OEMs.
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC"
                )?.use { cursor ->
                    val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                    val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                    val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                    val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                    val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                    var count = 0
                    while (cursor.moveToNext() && count < 120) {
                        val number = cursor.getString(numIdx) ?: continue
                        var name = cursor.getString(nameIdx)
                        if (name.isNullOrBlank()) {
                            name = nameIndex.nameForNumber(number)
                        }
                        val cleanNum = number.filter { it.isDigit() }
                        val photoUri = if (cleanNum.isNotEmpty()) photoByDigits[cleanNum] else null
                        val duration = if (durIdx >= 0) cursor.getLong(durIdx) else 0L
                        list.add(
                            RecentCall(
                                number = number,
                                name = name,
                                type = cursor.getInt(typeIdx),
                                date = cursor.getLong(dateIdx),
                                duration = duration,
                                photoUri = photoUri
                            )
                        )
                        count++
                    }
                }
            } catch (_: SecurityException) {
                // Call log permission not granted yet
            }

            if (gen != loadGeneration) return@execute

            // Group calls globally by number (latest first)
            val groupsMap = LinkedHashMap<String, MutableList<RecentCall>>()
            for (call in list) {
                val key = call.number.filter { it.isDigit() }.let { if (it.isEmpty()) call.number else it }
                var groupList = groupsMap[key]
                if (groupList == null) {
                    groupList = mutableListOf()
                    groupsMap[key] = groupList
                }
                groupList.add(call)
            }

            val groups = ArrayList<RecentGroup>(groupsMap.size)
            groupsMap.forEach { (_, callList) ->
                val first = callList.first()
                groups.add(
                    RecentGroup(
                        id = "${first.number}_${first.date}",
                        number = first.number,
                        name = first.name,
                        photoUri = first.photoUri,
                        calls = callList.toList()
                    )
                )
            }

            if (!isAdded || gen != loadGeneration) return@execute
            activity?.runOnUiThread {
                if (gen != loadGeneration) return@runOnUiThread
                val b = _binding ?: return@runOnUiThread
                adapter.submitList(groups)
                b.emptyText.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ── ODialer-style detail screen opened as a full-screen bottom sheet ──
    private fun showODialerDetailScreen(group: RecentGroup) {
        val ctx = requireContext()
        val isSaved = group.name != null

        // Build full-screen bottom-sheet style dialog
        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx, R.style.Theme_SecretDialer_FullDialog).create()
        dialog.window?.apply {
            setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            setGravity(android.view.Gravity.BOTTOM)
            decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#0D0D0D"))
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top Bar: back arrow ──
        val topBar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(12.dp, 12.dp, 12.dp, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val btnBack = android.widget.ImageView(ctx).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(btnBack, android.widget.LinearLayout.LayoutParams(48.dp, 48.dp))
        root.addView(topBar)

        // ── Scrollable content ──
        val scrollView = android.widget.ScrollView(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 32.dp)
        }

        // ── Avatar ──
        val avatarFrame = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp }
        }
        val avatarContainer = android.widget.FrameLayout(ctx).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(96.dp, 96.dp, android.view.Gravity.CENTER_HORIZONTAL)
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_avatar_circle)
            clipToOutline = true
        }
        val tvInitials = android.widget.TextView(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = android.view.Gravity.CENTER
            textSize = 34f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = DialerHelper.getInitials(group.name ?: group.number)
        }
        val color = DialerHelper.getAvatarColor(group.name ?: group.number)
        avatarContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

        val ivPhoto = android.widget.ImageView(ctx).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        if (group.photoUri != null) {
            val bmp = DialerHelper.loadContactPhoto(ctx, group.photoUri)
            if (bmp != null) { ivPhoto.setImageBitmap(bmp); ivPhoto.visibility = View.VISIBLE; tvInitials.visibility = View.GONE }
        }
        avatarContainer.addView(tvInitials)
        avatarContainer.addView(ivPhoto)
        avatarFrame.addView(avatarContainer)
        content.addView(avatarFrame)

        // ── Name ──
        val tvDisplayName = android.widget.TextView(ctx).apply {
            text = group.name ?: group.number
            textSize = 28f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20.dp }
        }
        content.addView(tvDisplayName)

        // ── 3 Action Buttons: Call | Message | Video ──
        val actionRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 24.dp }
        }
        fun makeActionBtn(icon: Int, label: String, onClick: () -> Unit): android.widget.LinearLayout {
            val btn = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_action_card)
                setPadding(0, 16.dp, 0, 16.dp)
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
            val img = android.widget.ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
            val tv = android.widget.TextView(ctx).apply {
                text = label; textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#AEAEB2"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 6.dp }
            }
            btn.addView(img, android.widget.LinearLayout.LayoutParams(24.dp, 24.dp))
            btn.addView(tv)
            return btn
        }
        val callBtn = makeActionBtn(R.drawable.ic_phone, "Call") {
            dialog.dismiss()
            (activity as? MainActivity)?.placeCall(group.number)
        }
        val msgBtn = makeActionBtn(R.drawable.ic_message, "Message") {
            dialog.dismiss()
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${group.number}")))
        }
        val videoBtn = makeActionBtn(R.drawable.ic_video, "Video") {
            dialog.dismiss()
            (activity as? MainActivity)?.placeCall(group.number, isVideo = true)
        }
        val btnLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 4.dp; marginEnd = 4.dp
        }
        actionRow.addView(callBtn, btnLp)
        actionRow.addView(msgBtn, btnLp)
        actionRow.addView(videoBtn, btnLp)
        content.addView(actionRow)

        // ── Call Logs section card ──
        val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val logsCard = makeCard(ctx)

        // Header row: "Call logs" | "View all"
        val logsHeaderRow = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(16.dp, 12.dp, 16.dp, 8.dp)
        }
        val tvLogsLabel = android.widget.TextView(ctx).apply {
            text = "Call logs"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        logsHeaderRow.addView(tvLogsLabel)
        logsCard.addView(logsHeaderRow)

        val displayCalls = group.calls.take(5)
        displayCalls.forEachIndexed { index, call ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            }

            // Date/time row with type arrow
            val topRow = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val arrowIcon = android.widget.ImageView(ctx).apply {
                val (arrowRes, arrowColor) = when (call.type) {
                    CallLog.Calls.INCOMING_TYPE -> Pair(R.drawable.ic_call_received, "#30D158")
                    CallLog.Calls.MISSED_TYPE -> Pair(R.drawable.ic_call_missed, "#FF453A")
                    else -> Pair(R.drawable.ic_call_made, "#AEAEB2")
                }
                setImageResource(arrowRes)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(arrowColor))
                layoutParams = android.widget.LinearLayout.LayoutParams(18.dp, 18.dp).apply { marginEnd = 8.dp }
            }
            val tvDateTime = android.widget.TextView(ctx).apply {
                text = dateFmt.format(Date(call.date))
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            topRow.addView(arrowIcon)
            topRow.addView(tvDateTime)
            row.addView(topRow)

            // Sub info: number + type + duration
            val typeLabel = when (call.type) {
                CallLog.Calls.INCOMING_TYPE -> "Incoming"
                CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                CallLog.Calls.MISSED_TYPE -> "Missed"
                else -> "Call"
            }
            val durText = if (call.duration > 0) "  ${call.duration}s" else if (call.type == CallLog.Calls.MISSED_TYPE) "" else "  (Not connected)"
            val tvSub = android.widget.TextView(ctx).apply {
                text = "VoLTE  ${call.number}   $typeLabel$durText"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                setPadding(26.dp, 4.dp, 0, 0)
            }
            row.addView(tvSub)
            logsCard.addView(row)

            if (index < displayCalls.size - 1) {
                val sep = View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = 16.dp; marginEnd = 16.dp }
                    setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E"))
                }
                logsCard.addView(sep)
            }
        }
        content.addView(logsCard, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16.dp })

        // ── Phone numbers card ──
        val phoneCard = makeCard(ctx)
        val phoneNumbers = if (isSaved) {
            // Try to load all numbers for this contact from contacts DB
            loadContactNumbers(ctx, group.number)
        } else {
            listOf(Pair(group.number, "Mobile"))
        }
        phoneNumbers.forEachIndexed { idx, (num, label) ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            }
            val tvNum = android.widget.TextView(ctx).apply {
                text = num
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
            }
            val tvLabel = android.widget.TextView(ctx).apply {
                text = "$label | India"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                setPadding(0, 4.dp, 0, 0)
            }
            row.addView(tvNum)
            row.addView(tvLabel)
            phoneCard.addView(row)
            if (idx < phoneNumbers.size - 1) {
                val sep = View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply { marginStart = 16.dp }
                    setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E"))
                }
                phoneCard.addView(sep)
            }
        }
        content.addView(phoneCard, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16.dp })

        // ── WhatsApp section ──
        val waCard = makeCard(ctx)
        val tvWa = android.widget.TextView(ctx).apply {
            text = "Open WhatsApp chat"
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#25D366"))
            setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            isClickable = true; isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                try {
                    val cleanNum = group.number.filter { it.isDigit() || it == '+' }
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$cleanNum")))
                } catch (_: Exception) {}
            }
        }
        waCard.addView(tvWa)
        content.addView(waCard, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 80.dp })

        scrollView.addView(content)
        root.addView(scrollView)

        // ── Bottom action bar: Favourites | Edit | Share | More ──
        val bottomBar = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
            setPadding(0, 12.dp, 0, 20.dp)
        }
        fun makeBottomBtn(icon: Int, label: String, onClick: () -> Unit): android.widget.LinearLayout {
            val btn = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isClickable = true; isFocusable = true
                setOnClickListener { onClick() }
            }
            val img = android.widget.ImageView(ctx).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
            val tv = android.widget.TextView(ctx).apply {
                text = label; textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#AEAEB2"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4.dp }
            }
            btn.addView(img, android.widget.LinearLayout.LayoutParams(24.dp, 24.dp))
            btn.addView(tv)
            return btn
        }
        val bbLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val favBtn = makeBottomBtn(R.drawable.ic_star, "Favourites") { /* TODO: toggle favourite */ }
        val editBtn = makeBottomBtn(R.drawable.ic_edit, "Edit") {
            dialog.dismiss()
            val resolvedId = ContactResolver.lookupByNumber(ctx, group.number)?.id
            if (resolvedId != null && resolvedId > 0) {
                (activity as? MainActivity)?.showContactEditor(resolvedId, null)
            } else {
                (activity as? MainActivity)?.showContactEditor(null, group.number)
            }
        }
        val shareBtn = makeBottomBtn(R.drawable.ic_share, "Share") {
            dialog.dismiss()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${group.name ?: ""}\n${group.number}")
            }
            startActivity(Intent.createChooser(shareIntent, "Share contact"))
        }
        val moreBtn = makeBottomBtn(R.drawable.ic_more_vert, "More") { /* no-op */ }

        bottomBar.addView(favBtn, bbLp)
        bottomBar.addView(editBtn, bbLp)
        bottomBar.addView(shareBtn, bbLp)
        bottomBar.addView(moreBtn, bbLp)

        root.addView(bottomBar)

        dialog.setView(root)
        dialog.show()
    }

    // Launch dialog to save new contact or add to existing
    private fun showSaveNumberDialog(number: String) {
        val ctx = requireContext()
        val items = arrayOf("Create new contact", "Add to existing contact")
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Save number $number")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                            type = ContactsContract.RawContacts.CONTENT_TYPE
                            putExtra(ContactsContract.Intents.Insert.PHONE, number)
                            putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        }
                        startActivity(intent)
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
                        startActivityForResult(intent, REQUEST_PICK_CONTACT)
                        pendingNumberToAdd = number
                    }
                }
            }
            .show()
    }

    private var pendingNumberToAdd: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_CONTACT && resultCode == android.app.Activity.RESULT_OK) {
            val contactUri = data?.data ?: return
            val number = pendingNumberToAdd ?: return
            pendingNumberToAdd = null
            val intent = Intent(Intent.ACTION_EDIT).apply {
                this.data = contactUri
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            }
            startActivity(intent)
        }
    }

    private fun loadContactNumbers(ctx: android.content.Context, number: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        try {
            val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(lookupUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val contactId = c.getLong(0)
                    val phonesCursor = ctx.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId.toString()),
                        null
                    )
                    phonesCursor?.use { pc ->
                        while (pc.moveToNext()) {
                            val n = pc.getString(0) ?: continue
                            val typeInt = pc.getInt(1)
                            val label = when (typeInt) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                                else -> "Other"
                            }
                            results.add(Pair(n, label))
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return results.ifEmpty { listOf(Pair(number, "Mobile")) }
    }

    private fun getContactLookupUri(ctx: android.content.Context, number: String): Uri? {
        return try {
            val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(lookupUri, arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup._ID), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val lookupKey = c.getString(0)
                    val id = c.getLong(1)
                    ContactsContract.Contacts.getLookupUri(id, lookupKey)
                } else null
            }
        } catch (_: Exception) { null }
    }

    private fun makeCard(ctx: android.content.Context): android.widget.LinearLayout {
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card_rounded)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val REQUEST_PICK_CONTACT = 9001
    }
}

class RecentsAdapter(
    private val onItemClick: (RecentGroup) -> Unit,
    private val onInfoClick: (RecentGroup) -> Unit
) : ListAdapter<RecentGroup, RecentsAdapter.VH>(DIFF) {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), timeFmt, onItemClick, onInfoClick)
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name = itemView.findViewById<TextView>(R.id.tvName)
        private val sub = itemView.findViewById<TextView>(R.id.tvSub)
        private val avatarContainer = itemView.findViewById<View>(R.id.avatarContainer)
        private val tvAvatarInitials = itemView.findViewById<TextView>(R.id.tvAvatarInitials)
        private val ivAvatarPhoto = itemView.findViewById<android.widget.ImageView>(R.id.ivAvatarPhoto)
        private val btnInfo = itemView.findViewById<View>(R.id.btnInfo)

        fun bind(
            item: RecentGroup,
            fmt: SimpleDateFormat,
            onItemClick: (RecentGroup) -> Unit,
            onInfoClick: (RecentGroup) -> Unit
        ) {
            val countSuffix = if (item.calls.size > 1) " (${item.calls.size})" else ""
            // Fix: show actual phone number for unsaved contacts, not a label
            val baseName = item.name ?: item.number
            val displayName = baseName + countSuffix
            name.text = displayName

            // Only flag red if the name itself contains spam markers (not just because unsaved)
            if (item.name != null && (item.name.contains("Spam", ignoreCase = true) || item.name.contains("Scam", ignoreCase = true))) {
                name.setTextColor(android.graphics.Color.parseColor("#FF453A"))
            } else {
                name.setTextColor(android.graphics.Color.WHITE)
            }

            val latestCall = item.calls.firstOrNull()
            val typeArrow = when (latestCall?.type) {
                CallLog.Calls.INCOMING_TYPE -> "↙"
                CallLog.Calls.OUTGOING_TYPE -> "↗"
                CallLog.Calls.MISSED_TYPE -> "↙"
                else -> ""
            }
            val typeColor = when (latestCall?.type) {
                CallLog.Calls.MISSED_TYPE -> android.graphics.Color.parseColor("#FF453A")
                CallLog.Calls.INCOMING_TYPE -> android.graphics.Color.parseColor("#30D158")
                else -> android.graphics.Color.parseColor("#8E8E93")
            }
            // Show number below name only if it's a saved contact (so number is sub-info)
            val numLine = if (item.name != null) item.number else ""
            val timeText = latestCall?.let { fmt.format(Date(it.date)) } ?: ""
            sub.text = if (numLine.isNotEmpty()) numLine else "$typeArrow $timeText"
            sub.setTextColor(android.graphics.Color.parseColor("#8E8E93"))

            itemView.setOnClickListener { onItemClick(item) }
            btnInfo.setOnClickListener { onInfoClick(item) }

            val initials = com.secretdialer.app.DialerHelper.getInitials(item.name ?: item.number)
            tvAvatarInitials.text = initials
            val color = com.secretdialer.app.DialerHelper.getAvatarColor(item.name ?: item.number)
            avatarContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

            if (item.photoUri != null) {
                val bitmap = com.secretdialer.app.DialerHelper.loadContactPhoto(itemView.context, item.photoUri)
                if (bitmap != null) {
                    ivAvatarPhoto.visibility = View.VISIBLE
                    ivAvatarPhoto.setImageBitmap(bitmap)
                    tvAvatarInitials.visibility = View.GONE
                } else {
                    ivAvatarPhoto.visibility = View.GONE
                    tvAvatarInitials.visibility = View.VISIBLE
                }
            } else {
                ivAvatarPhoto.visibility = View.GONE
                tvAvatarInitials.visibility = View.VISIBLE
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecentGroup>() {
            override fun areItemsTheSame(a: RecentGroup, b: RecentGroup): Boolean = a.id == b.id
            override fun areContentsTheSame(a: RecentGroup, b: RecentGroup): Boolean = a == b
        }
    }
}
