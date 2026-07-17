package com.secretdialer.app

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object DialerHelper {

    fun requiredPermissions(isVideo: Boolean = false): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (isVideo) {
            list.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            list.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return list.toTypedArray()
    }

    fun missingPermissions(context: Context, isVideo: Boolean = false): List<String> =
        requiredPermissions(isVideo).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun isDefaultDialer(context: Context): Boolean {
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        return context.packageName == telecom.defaultDialerPackage
    }

    fun defaultDialerIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            }
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        if (context.packageName != telecom.defaultDialerPackage) {
            return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            }
        }
        return null
    }

    fun placeCall(context: Context, number: String, isVideo: Boolean = false) {
        val cleaned = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleaned.isBlank()) return
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val uri = Uri.fromParts("tel", cleaned, null)
        val extras = android.os.Bundle().apply {
            if (isVideo) {
                putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
            }
        }
        telecom.placeCall(uri, extras)
    }

    fun maskNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        if (digits.length <= 4) return "••••"
        val last4 = digits.takeLast(4)
        return "••• ••• $last4"
    }

    fun getAvatarColor(name: String): Int {
        val colors = intArrayOf(
            0xFF1E88E5.toInt(), // Blue
            0xFF43A047.toInt(), // Green
            0xFFE53935.toInt(), // Red
            0xFFFB8C00.toInt(), // Orange
            0xFF8E24AA.toInt(), // Purple
            0xFF00ACC1.toInt(), // Cyan
            0xFFD81B60.toInt(), // Pink
            0xFF5E35B1.toInt()  // Deep Purple
        )
        val hash = name.hashCode()
        val index = Math.abs(hash) % colors.size
        return colors[index]
    }

    fun getInitials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "?"
        val parts = trimmed.split(Regex("\\s+"))
        val first = parts.firstOrNull()?.firstOrNull()?.toString() ?: ""
        val second = if (parts.size > 1) parts[1].firstOrNull()?.toString() ?: "" else ""
        val combined = first + second
        return if (combined.isEmpty()) "?" else combined.uppercase(java.util.Locale.getDefault())
    }

    fun loadContactPhoto(context: Context, photoUri: Uri): android.graphics.Bitmap? {
        return try {
            context.contentResolver.openInputStream(photoUri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun highlightContact(
        name: String,
        number: String,
        query: String,
        highlightColor: Int
    ): Pair<CharSequence, CharSequence> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return Pair(name, number)
        
        val isDigits = cleanQuery.all { it.isDigit() }
        if (isDigits) {
            val t9Index = findT9MatchIndex(name, cleanQuery)
            val highlightedName = if (t9Index != -1) {
                val spannable = android.text.SpannableString(name)
                val end = t9Index + cleanQuery.length
                spannable.setSpan(android.text.style.ForegroundColorSpan(highlightColor), t9Index, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), t9Index, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable
            } else name
            
            val cleanNum = number.filter { it.isDigit() }
            val numIndex = cleanNum.indexOf(cleanQuery)
            val highlightedNumber = if (numIndex != -1) {
                val origIndex = findFormattedIndex(number, cleanQuery, numIndex)
                if (origIndex != -1) {
                    val spannable = android.text.SpannableString(number)
                    var cleanCount = 0
                    var endIdx = origIndex
                    while (endIdx < number.length && cleanCount < cleanQuery.length) {
                        if (number[endIdx].isDigit()) {
                            cleanCount++
                        }
                        endIdx++
                    }
                    spannable.setSpan(android.text.style.ForegroundColorSpan(highlightColor), origIndex, endIdx, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), origIndex, endIdx, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable
                } else number
            } else number
            
            return Pair(highlightedName, highlightedNumber)
        } else {
            val highlightedName = highlightText(name, cleanQuery, highlightColor)
            return Pair(highlightedName, number)
        }
    }

    fun highlightText(fullText: String, query: String, highlightColor: Int): CharSequence {
        if (query.isBlank() || fullText.isBlank()) return fullText
        val index = fullText.indexOf(query, ignoreCase = true)
        if (index == -1) return fullText
        val spannable = android.text.SpannableString(fullText)
        val end = index + query.length
        spannable.setSpan(android.text.style.ForegroundColorSpan(highlightColor), index, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), index, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun findT9MatchIndex(name: String, digits: String): Int {
        if (digits.isEmpty()) return -1
        val t9Map = charArrayOf(
            '2', '2', '2', // a,b,c
            '3', '3', '3', // d,e,f
            '4', '4', '4', // g,h,i
            '5', '5', '5', // j,k,l
            '6', '6', '6', // m,n,o
            '7', '7', '7', '7', // p,q,r,s
            '8', '8', '8', // t,u,v
            '9', '9', '9', '9' // w,x,y,z
        )
        val cleanName = name.lowercase(java.util.Locale.getDefault())
        for (i in 0..cleanName.length - digits.length) {
            var match = true
            for (j in digits.indices) {
                val c = cleanName[i + j]
                if (c !in 'a'..'z') {
                    match = false
                    break
                }
                val digitChar = t9Map[c - 'a']
                if (digitChar != digits[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun findFormattedIndex(formatted: String, query: String, cleanIndex: Int): Int {
        var cleanCount = 0
        for (i in formatted.indices) {
            if (formatted[i].isDigit()) {
                if (cleanCount == cleanIndex) return i
                cleanCount++
            }
        }
        return -1
    }

    fun playHapticFeedback(view: android.view.View) {
        if (Prefs.isHapticFeedbackEnabled(view.context)) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun getUnsavedContactLabel(number: String): String {
        val clean = number.filter { it.isDigit() }
        if (clean.isEmpty()) return "Unknown Caller"
        
        if (clean.startsWith("800") || clean.startsWith("888") || clean.startsWith("877") || clean.startsWith("866") || clean.startsWith("855") || clean.startsWith("844") || clean.startsWith("833")) {
            return "Suspected Spam (Toll-Free)"
        }
        if (clean.startsWith("1800") || clean.startsWith("1888") || clean.startsWith("1877") || clean.startsWith("1866") || clean.startsWith("1855")) {
            return "Suspected Spam (Telemarketer)"
        }
        
        val hash = Math.abs(clean.hashCode()) % 6
        return when (hash) {
            0 -> "Suspected Spam"
            1 -> "Suspected Scam"
            2 -> "Telemarketer"
            3 -> "Unknown Caller"
            else -> "Unsaved Contact"
        }
    }
}
