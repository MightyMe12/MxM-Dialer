package com.secretdialer.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.app.NotificationCompat
import com.secretdialer.app.AppForegroundTracker
import com.secretdialer.app.ContactResolver
import android.media.MediaRecorder
import com.secretdialer.app.InCallActivity
import com.secretdialer.app.Prefs
import com.secretdialer.app.R
import com.secretdialer.app.recorder.CallRecorder

class DialerInCallService : InCallService() {

    private val activeCalls = mutableListOf<Call>()
    var recorder: CallRecorder? = null
    private var recordingNumber: String? = null
    private var recordingName: String? = null
    var routeBeforeRecording: Int? = null
    var forcedSpeakerForRecording = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val oneRingRejectRunnables = mutableMapOf<Call, Runnable>()
    private val ONE_RING_REJECT_DELAY_MS = 1500L
    private var pendingRecordStart: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_ANSWER -> {
                    val incomingCall = activeCalls.find { it.state == Call.STATE_RINGING } ?: CallStateHub.activeCall
                    if (incomingCall != null) {
                        activeCalls.forEach { otherCall ->
                            if (otherCall != incomingCall && (otherCall.state == Call.STATE_ACTIVE || otherCall.state == Call.STATE_DIALING)) {
                                otherCall.hold()
                            }
                        }
                        incomingCall.answer(incomingCall.details.videoState)
                    }
                    cancelIncomingCallNotification()
                }
                ACTION_DECLINE -> {
                    CallStateHub.activeCall?.reject(false, null)
                    cancelIncomingCallNotification()
                }
                ACTION_TOGGLE_MUTE -> {
                    val currentMute = callAudioState?.isMuted ?: false
                    setMuted(!currentMute)
                }
                ACTION_TOGGLE_SPEAKER -> {
                    val currentRoute = callAudioState?.route ?: android.telecom.CallAudioState.ROUTE_EARPIECE
                    val nextRoute = if (currentRoute == android.telecom.CallAudioState.ROUTE_SPEAKER) {
                        android.telecom.CallAudioState.ROUTE_EARPIECE
                    } else {
                        android.telecom.CallAudioState.ROUTE_SPEAKER
                    }
                    setAudioRoute(nextRoute)
                }
                ACTION_END_CALL -> {
                    CallStateHub.activeCall?.disconnect()
                    cancelOngoingCallNotification()
                }
                else -> {}
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallStateHub.activeCall?.let { showOngoingCallNotification(it) }
        CallStateHub.notifyStateChanged()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val number = call.details.handle?.schemeSpecificPart ?: ""
        if (shouldBlockCall(call, number)) {
            android.util.Log.i("DialerInCallService", "Blocking incoming call from: $number")
            call.reject(false, null)
            showBlockNotificationIfNeeded(number)
            return
        }

        if (shouldScheduleOneRingBlock(call, number)) {
            scheduleOneRingBlock(call, number)
        }

        activeCalls.add(call)
        call.registerCallback(callCallback)
        CallStateHub.activeCall = call
        if (call.state == Call.STATE_RINGING && call.details.callDirection == Call.Details.DIRECTION_INCOMING) {
            startFlashlightBlink()
            val showFullScreen = !AppForegroundTracker.isAppInForeground
            showIncomingCallNotification(call, showFullScreen)
            if (showFullScreen) {
                launchInCallUi(call)
            }
        } else {
            launchInCallUi(call)
            showOngoingCallNotification(call)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        cancelOneRingBlock(call)
        stopFlashlightBlink()
        call.unregisterCallback(callCallback)
        activeCalls.remove(call)
        stopRecording()
        cancelIncomingCallNotification()
        cancelOngoingCallNotification()
        if (CallStateHub.activeCall == call) {
            CallStateHub.activeCall = activeCalls.lastOrNull()
        }
        if (activeCalls.size == 1) {
            val remaining = activeCalls.firstOrNull()
            if (remaining != null && remaining.state == Call.STATE_HOLDING) {
                remaining.unhold()
            }
        }
        if (activeCalls.isEmpty()) {
            CallStateHub.activeCall = null
        }
    }

    private fun shouldBlockCall(call: Call, number: String): Boolean {
        // Only apply blocking rules to incoming calls.
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING) return false

        // Allowlist always wins.
        if (Prefs.isAllowlistNumber(this, number)) return false

        val clean = number.filter { it.isDigit() }

        // If we have no reliable number, treat as "private" if enabled.
        if (clean.isEmpty()) return Prefs.isBlockPrivate(this)

        if (Prefs.isBlockAllCalls(this)) return true
        if (Prefs.isNumberBlocked(this, number)) return true

        // Region-based blocking (prefix match on digits).
        val region = Prefs.getBlockRegion(this)
        if (region != "None") {
            val regDigits = region.filter { it.isDigit() }
            if (regDigits.isNotEmpty() && (clean.startsWith(regDigits) || clean.endsWith(regDigits))) return true
        }

        val isSaved = ContactResolver.isSavedContact(this, number)
        if (Prefs.isBlockUnknown(this) && !isSaved) return true

        return false
    }

    private fun shouldScheduleOneRingBlock(call: Call, number: String): Boolean {
        if (call.details.callDirection != Call.Details.DIRECTION_INCOMING) return false
        if (!Prefs.isBlockOneRing(this)) return false
        if (call.state != Call.STATE_RINGING) return false
        if (Prefs.isAllowlistNumber(this, number)) return false
        val isSaved = ContactResolver.isSavedContact(this, number)
        if (isSaved) return false
        // If any "immediate" blocking rule applies, we already reject in onCallAdded.
        return !shouldBlockCall(call, number)
    }

    private fun scheduleOneRingBlock(call: Call, number: String) {
        // Prevent duplicate scheduling for the same Call instance.
        if (oneRingRejectRunnables.containsKey(call)) return

        val runnable = Runnable {
            // If the call left the ringing state, do nothing.
            if (call.state != Call.STATE_RINGING) return@Runnable

            // Reject and notify as a "one ring" block.
            call.reject(false, null)
            showBlockNotificationIfNeeded(number)
            cancelOneRingBlock(call)
        }

        oneRingRejectRunnables[call] = runnable
        mainHandler.postDelayed(runnable, ONE_RING_REJECT_DELAY_MS)
    }

    private fun cancelOneRingBlock(call: Call) {
        oneRingRejectRunnables.remove(call)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun showBlockNotificationIfNeeded(number: String) {
        val mode = Prefs.getBlockNotificationMode(this)
        if (mode == 1) return // "Do not notify"

        val isBlocklist = Prefs.isNumberBlocked(this, number)
        if (mode == 2 && isBlocklist) return // Only notify for non-blocklist numbers.

        val contact = ContactResolver.lookupByNumber(this, number)
        val name = contact?.name ?: number

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BLOCK_CHANNEL_ID,
                "Blocked Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification for blocked calls"
            }
            nm.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(this, BLOCK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Call blocked")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)

        nm.notify(BLOCK_NOTIFICATION_ID, builder.build())
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state != Call.STATE_RINGING) {
                cancelOneRingBlock(call)
            } else {
                // If the call transitions into RINGING, (re)schedule "one ring" blocking if applicable.
                val number = call.details.handle?.schemeSpecificPart ?: ""
                if (shouldScheduleOneRingBlock(call, number)) {
                    scheduleOneRingBlock(call, number)
                }
            }

            if (state != Call.STATE_RINGING) {
                stopFlashlightBlink()
                cancelIncomingCallNotification()
            }
            if (state == Call.STATE_DISCONNECTED) {
                stopRecording()
                cancelIncomingCallNotification()
                cancelOngoingCallNotification()
                val otherHeldCall = activeCalls.find { it != call && it.state == Call.STATE_HOLDING }
                otherHeldCall?.unhold()
            }
            CallStateHub.notifyStateChanged()
            if (state == Call.STATE_RINGING && call.details.callDirection == Call.Details.DIRECTION_INCOMING) {
                val showFullScreen = !AppForegroundTracker.isAppInForeground
                showIncomingCallNotification(call, showFullScreen)
                if (showFullScreen) {
                    launchInCallUi(call)
                }
            } else if (state == Call.STATE_RINGING || state == Call.STATE_DIALING || state == Call.STATE_ACTIVE || state == Call.STATE_CONNECTING) {
                launchInCallUi(call)
                if (state == Call.STATE_ACTIVE || state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
                    showOngoingCallNotification(call)
                }
                if (state == Call.STATE_ACTIVE) {
                    if (CallStateHub.pendingRecordOnConnect) {
                        CallStateHub.pendingRecordOnConnect = false
                        startRecording()
                    }
                    checkAndStartAutoRecording(call)
                }
            }
        }
    }

    private fun launchInCallUi(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        val contact = ContactResolver.lookupByNumber(this, number)
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(InCallActivity.EXTRA_NUMBER, number)
            putExtra(InCallActivity.EXTRA_CONTACT_NAME, contact?.name)
            putExtra(InCallActivity.EXTRA_IS_SAVED_CONTACT, contact != null)
        }
        startActivity(intent)
    }

    private fun showIncomingCallNotification(call: Call, showFullScreen: Boolean) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        val contact = ContactResolver.lookupByNumber(this, number)
        val name = contact?.name ?: number
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up notification for incoming calls"
                enableLights(true)
                enableVibration(true)
                importance = NotificationManager.IMPORTANCE_HIGH
            }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(InCallActivity.EXTRA_NUMBER, number)
            putExtra(InCallActivity.EXTRA_CONTACT_NAME, contact?.name)
            putExtra(InCallActivity.EXTRA_IS_SAVED_CONTACT, contact != null)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Answer PendingIntent
        val answerIntent = Intent(this, DialerInCallService::class.java).apply {
            action = ACTION_ANSWER
        }
        val answerPendingIntent = PendingIntent.getService(
            this,
            1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline PendingIntent
        val declineIntent = Intent(this, DialerInCallService::class.java).apply {
            action = ACTION_DECLINE
        }
        val declinePendingIntent = PendingIntent.getService(
            this,
            2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("Incoming call")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(R.drawable.ic_phone, "Answer", answerPendingIntent)
            .addAction(R.drawable.ic_phone, "Decline", declinePendingIntent)

        if (showFullScreen) {
            builder.setFullScreenIntent(pendingIntent, true)
        } else {
            builder.setContentIntent(pendingIntent)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelIncomingCallNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun showOngoingCallNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        val contact = ContactResolver.lookupByNumber(this, number)
        val name = contact?.name ?: number
        
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ONGOING_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notification for active calls"
                importance = NotificationManager.IMPORTANCE_HIGH
            }
            nm.createNotificationChannel(channel)
        }

        val contentIntent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(InCallActivity.EXTRA_NUMBER, number)
            putExtra(InCallActivity.EXTRA_CONTACT_NAME, contact?.name)
            putExtra(InCallActivity.EXTRA_IS_SAVED_CONTACT, contact != null)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            10,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endCallIntent = Intent(this, DialerInCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endCallPendingIntent = PendingIntent.getService(
            this,
            11,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val audioState = callAudioState
        val isMuted = audioState?.isMuted ?: false
        val isSpeaker = audioState?.route == android.telecom.CallAudioState.ROUTE_SPEAKER

        val muteIntent = Intent(this, DialerInCallService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            12,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakerIntent = Intent(this, DialerInCallService::class.java).apply {
            action = ACTION_TOGGLE_SPEAKER
        }
        val speakerPendingIntent = PendingIntent.getService(
            this,
            13,
            speakerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteLabel = if (isMuted) "Unmute" else "Mute"
        val speakerLabel = if (isSpeaker) "Earpiece" else "Speaker"

        val notification = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(name)
            .setContentText("Active call")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_mic, muteLabel, mutePendingIntent)
            .addAction(R.drawable.ic_speaker, speakerLabel, speakerPendingIntent)
            .addAction(R.drawable.ic_phone, "End", endCallPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(ONGOING_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification)
        }
    }

    private fun cancelOngoingCallNotification() {
        stopForeground(true)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ONGOING_NOTIFICATION_ID)
    }

    fun showOngoingCallNotificationExternal(call: Call) {
        showOngoingCallNotification(call)
    }

    fun isRecordingActive(): Boolean = recorder?.isRecording == true

    fun toggleRecording(): Boolean {
        return if (isRecordingActive()) {
            stopRecording()
            false
        } else {
            startRecording()
        }
    }

    private fun startRecording(): Boolean {
        val call = CallStateHub.activeCall ?: return false
        if (call.state != Call.STATE_ACTIVE) return false
        if (isRecordingActive() || pendingRecordStart != null) return true

        val number = call.details.handle?.schemeSpecificPart ?: return false
        val contact = ContactResolver.lookupByNumber(this, number)
        recordingNumber = number
        recordingName = contact?.name

        try {
            setMuted(false)
        } catch (e: Exception) {
            android.util.Log.w("DialerInCallService", "Could not unmute for recording", e)
        }

        CallStateHub.isRecording = true
        CallStateHub.notifyStateChanged()

        val start = Runnable {
            pendingRecordStart = null
            val active = CallStateHub.activeCall
            if (active == null || active.state != Call.STATE_ACTIVE) {
                CallStateHub.isRecording = false
                CallStateHub.notifyStateChanged()
                return@Runnable
            }
            recorder = CallRecorder(this)
            recorder?.start(number, contact?.name)
            CallStateHub.notifyStateChanged()
        }
        pendingRecordStart = start
        mainHandler.post(start)
        return true
    }

    fun stopRecording() {
        pendingRecordStart?.let { mainHandler.removeCallbacks(it) }
        pendingRecordStart = null
        recorder?.stop()
        recorder = null
        recordingNumber = null
        recordingName = null
        CallStateHub.isRecording = false
        CallStateHub.pendingRecordOnConnect = false
        CallStateHub.notifyStateChanged()
    }

    override fun onDestroy() {
        stopRecording()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun checkAndStartAutoRecording(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        val mode = Prefs.getAutoRecordMode(this)
        if (mode == 0) return // None
        
        val contact = ContactResolver.lookupByNumber(this, number)
        val isSaved = contact != null
        
        val shouldRecord = when (mode) {
            1 -> true // All
            2 -> !isSaved // Unknown only
            3 -> { // Specific numbers only
                val specific = Prefs.getAutoRecordSpecificNumbers(this)
                val cleanNum = number.filter { it.isDigit() }
                specific.any { it.filter { c -> c.isDigit() } == cleanNum }
            }
            else -> false
        }
        
        if (shouldRecord) {
            if (!isRecordingActive()) {
                toggleRecording()
            }
        }
    }

    private var flashHandler: android.os.Handler? = null
    private var flashRunnable: Runnable? = null
    private var isTorchOn = false

    private fun startFlashlightBlink() {
        if (!com.secretdialer.app.Prefs.isFlashAlertEnabled(this)) return
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return

        flashHandler = android.os.Handler(android.os.Looper.getMainLooper())
        flashRunnable = object : Runnable {
            override fun run() {
                try {
                    isTorchOn = !isTorchOn
                    cameraManager.setTorchMode(cameraId, isTorchOn)
                } catch (e: Exception) {}
                flashHandler?.postDelayed(this, 300)
            }
        }
        flashHandler?.post(flashRunnable!!)
    }

    private fun stopFlashlightBlink() {
        flashHandler?.removeCallbacks(flashRunnable ?: return)
        flashHandler = null
        flashRunnable = null
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null && isTorchOn) {
                cameraManager.setTorchMode(cameraId, false)
                isTorchOn = false
            }
        } catch (e: Exception) {}
    }

    companion object {
        const val ACTION_ANSWER = "com.secretdialer.app.action.ANSWER"
        const val ACTION_DECLINE = "com.secretdialer.app.action.DECLINE"
        const val ACTION_TOGGLE_MUTE = "com.secretdialer.app.action.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.secretdialer.app.action.TOGGLE_SPEAKER"
        const val ACTION_END_CALL = "com.secretdialer.app.action.END_CALL"

        private const val CHANNEL_ID = "incoming_call_channel"
        private const val ONGOING_CHANNEL_ID = "ongoing_call_channel"
        private const val NOTIFICATION_ID = 2026
        private const val ONGOING_NOTIFICATION_ID = 2027
        private const val BLOCK_CHANNEL_ID = "blocked_call_channel"
        private const val BLOCK_NOTIFICATION_ID = 2028

        @Volatile
        private var instance: DialerInCallService? = null

        fun getInstance(): DialerInCallService? = instance

        fun getActiveCalls(): List<Call> = instance?.activeCalls ?: emptyList()
    }
}

object CallStateHub {
    var activeCall: Call? = null
    var isRecording: Boolean = false
    var pendingRecordOnConnect: Boolean = false
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyStateChanged() {
        listeners.forEach { it.invoke() }
    }

    fun toggleRecording(): Boolean =
        DialerInCallService.getInstance()?.toggleRecording() ?: false

    fun isRecordingActive(): Boolean =
        DialerInCallService.getInstance()?.isRecordingActive() ?: isRecording
}
