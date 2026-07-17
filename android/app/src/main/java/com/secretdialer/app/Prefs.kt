package com.secretdialer.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "mxm_dialer_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun maskSavedContactNumbers(context: Context): Boolean =
        prefs(context).getBoolean("mask_numbers", true)

    fun setMaskSavedContactNumbers(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("mask_numbers", enabled).apply()
    }

    fun isDefaultDialerPromptShown(context: Context): Boolean =
        prefs(context).getBoolean("default_dialer_prompt_shown", false)

    fun setDefaultDialerPromptShown(context: Context) {
        prefs(context).edit().putBoolean("default_dialer_prompt_shown", true).apply()
    }

    fun getAutoRecordMode(context: Context): Int =
        prefs(context).getInt("auto_record_mode", 0)

    fun setAutoRecordMode(context: Context, mode: Int) {
        prefs(context).edit().putInt("auto_record_mode", mode).apply()
    }

    fun getAutoRecordSpecificNumbers(context: Context): Set<String> =
        prefs(context).getStringSet("auto_record_specific_numbers", emptySet()) ?: emptySet()

    fun setAutoRecordSpecificNumbers(context: Context, numbers: Set<String>) {
        prefs(context).edit().putStringSet("auto_record_specific_numbers", numbers).apply()
    }

    fun getAutoDeleteDays(context: Context): Int =
        prefs(context).getInt("auto_delete_days", 0)

    fun setAutoDeleteDays(context: Context, days: Int) {
        prefs(context).edit().putInt("auto_delete_days", days).apply()
    }

    fun getSavedTab(context: Context): Int =
        prefs(context).getInt("saved_tab", 1)

    fun setSavedTab(context: Context, tab: Int) {
        prefs(context).edit().putInt("saved_tab", tab).apply()
    }

    fun isHapticFeedbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean("haptic_feedback", true)

    fun setHapticFeedbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("haptic_feedback", enabled).apply()
    }

    fun getBlockedNumbers(context: Context): Set<String> =
        prefs(context).getStringSet("blocked_numbers", emptySet()) ?: emptySet()

    fun setBlockedNumbers(context: Context, blocked: Set<String>) {
        prefs(context).edit().putStringSet("blocked_numbers", blocked).apply()
    }

    fun isNumberBlocked(context: Context, number: String): Boolean {
        val clean = number.filter { it.isDigit() }
        if (clean.isEmpty()) return false
        return getBlockedNumbers(context).any { blockedNum ->
            val blockedClean = blockedNum.filter { it.isDigit() }
            blockedClean.isNotEmpty() && (blockedClean == clean || clean.endsWith(blockedClean) || blockedClean.endsWith(clean))
        }
    }

    fun isPowerButtonEndCallEnabled(context: Context): Boolean =
        prefs(context).getBoolean("power_end_call", false)

    fun setPowerButtonEndCallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("power_end_call", enabled).apply()
    }

    fun getSpeedDial(context: Context, key: Int): String? =
        prefs(context).getString("speed_dial_$key", null)

    fun setSpeedDial(context: Context, key: Int, number: String?) {
        prefs(context).edit().putString("speed_dial_$key", number).apply()
    }

    fun isFlashAlertEnabled(context: Context): Boolean =
        prefs(context).getBoolean("flash_alert", false)

    fun setFlashAlertEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("flash_alert", enabled).apply()
    }

    fun isFlipToSilenceEnabled(context: Context): Boolean =
        prefs(context).getBoolean("flip_silence", false)

    fun setFlipToSilenceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("flip_silence", enabled).apply()
    }

    fun isEdgeLightingEnabled(context: Context): Boolean =
        prefs(context).getBoolean("edge_lighting", false)

    fun setEdgeLightingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("edge_lighting", enabled).apply()
    }

    fun getAllowlistNumbers(context: Context): Set<String> =
        prefs(context).getStringSet("allowlist_numbers", emptySet()) ?: emptySet()

    fun setAllowlistNumbers(context: Context, numbers: Set<String>) {
        prefs(context).edit().putStringSet("allowlist_numbers", numbers).apply()
    }

    fun isAllowlistNumber(context: Context, number: String): Boolean {
        val clean = number.filter { it.isDigit() }
        if (clean.isEmpty()) return false
        return getAllowlistNumbers(context).any { allowNum ->
            val allowClean = allowNum.filter { it.isDigit() }
            allowClean.isNotEmpty() && (allowClean == clean || clean.endsWith(allowClean) || allowClean.endsWith(clean))
        }
    }

    fun isBlockAllCalls(context: Context): Boolean =
        prefs(context).getBoolean("block_all_calls", false)

    fun setBlockAllCalls(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("block_all_calls", value).apply()
    }

    fun isBlockUnknown(context: Context): Boolean =
        prefs(context).getBoolean("block_unknown", false)

    fun setBlockUnknown(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("block_unknown", value).apply()
    }

    fun isBlockOneRing(context: Context): Boolean =
        prefs(context).getBoolean("block_one_ring", false)

    fun setBlockOneRing(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("block_one_ring", value).apply()
    }

    fun isBlockPrivate(context: Context): Boolean =
        prefs(context).getBoolean("block_private", false)

    fun setBlockPrivate(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("block_private", value).apply()
    }

    fun getBlockRegion(context: Context): String =
        prefs(context).getString("block_region", "None") ?: "None"

    fun setBlockRegion(context: Context, value: String) {
        prefs(context).edit().putString("block_region", value).apply()
    }

    fun getBlockNotificationMode(context: Context): Int =
        prefs(context).getInt("block_notification_mode", 0)

    fun setBlockNotificationMode(context: Context, mode: Int) {
        prefs(context).edit().putInt("block_notification_mode", mode).apply()
    }

    fun getRecordingSource(context: Context): Int =
        prefs(context).getInt("recording_source", 7) // Default to VOICE_COMMUNICATION

    fun setRecordingSource(context: Context, source: Int) {
        prefs(context).edit().putInt("recording_source", source).apply()
    }

    fun isForceSpeakerEnabled(context: Context): Boolean =
        prefs(context).getBoolean("force_speaker_recording", true)

    fun setForceSpeakerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("force_speaker_recording", enabled).apply()
    }
}
