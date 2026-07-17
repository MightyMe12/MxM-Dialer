package com.secretdialer.app

import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.secretdialer.app.databinding.ActivityInCallBinding
import com.secretdialer.app.service.CallStateHub
import java.util.Locale

class InCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInCallBinding
    private var number: String = ""
    private var contactName: String? = null
    private var isSavedContact: Boolean = false

    private val stateListener = { refreshUi() }

    private var displaySurface: android.view.Surface? = null
    private var previewSurface: android.view.Surface? = null
    private var lastBoundVideoCall: android.telecom.InCallService.VideoCall? = null
    private var pulseAnimator: android.animation.AnimatorSet? = null

    private val videoCallCallback = object : android.telecom.InCallService.VideoCall.Callback() {
        override fun onSessionModifyRequestReceived(videoProfile: android.telecom.VideoProfile?) {
            if (videoProfile != null && android.telecom.VideoProfile.isVideo(videoProfile.videoState)) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this@InCallActivity, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val responseProfile = android.telecom.VideoProfile(videoProfile.videoState)
                    CallStateHub.activeCall?.videoCall?.sendSessionModifyResponse(responseProfile)
                    Toast.makeText(this@InCallActivity, "Video call connected", Toast.LENGTH_SHORT).show()
                }
            }
        }
        override fun onSessionModifyResponseReceived(status: Int, requestProfile: android.telecom.VideoProfile?, responseProfile: android.telecom.VideoProfile?) {}
        override fun onCallSessionEvent(event: Int) {}
        override fun onPeerDimensionsChanged(width: Int, height: Int) {}
        override fun onVideoQualityChanged(videoQuality: Int) {}
        override fun onCallDataUsageChanged(dataUsage: Long) {}
        override fun onCameraCapabilitiesChanged(cameraCapabilities: android.telecom.VideoProfile.CameraCapabilities?) {}
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val call = CallStateHub.activeCall ?: return postNext()
            if (call.state == Call.STATE_ACTIVE) {
                val recording = CallStateHub.isRecordingActive()
                binding.tvStatus.text = statusText(Call.STATE_ACTIVE, recording)
            }
            postNext()
        }

        private fun postNext() {
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        binding = ActivityInCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        number = intent.getStringExtra(EXTRA_NUMBER) ?: ""
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
        isSavedContact = intent.getBooleanExtra(EXTRA_IS_SAVED_CONTACT, false)

        binding.btnEndCall.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            endCall()
        }
        setupSwipeToActGesture()
        binding.btnMute.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            toggleMute()
        }
        binding.btnSpeaker.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            showAudioRouteSelectorDialog()
        }
        binding.btnDeclineMessage.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            showQuickSmsDeclineDialog()
        }
        binding.btnKeypad.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            toggleKeypad()
        }
        binding.btnRecord.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            toggleRecording()
        }
        binding.btnAddCall.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            val calls = com.secretdialer.app.service.DialerInCallService.getActiveCalls()
            if (calls.size >= 2) {
                mergeCalls()
            } else {
                addCall()
            }
        }
        binding.btnInlineMerge.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            mergeCalls()
        }
        binding.btnHold.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            toggleHold()
        }
        binding.btnVideo.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            upgradeToVideo()
        }
        binding.secondaryCallContainer.setOnClickListener {
            DialerHelper.playHapticFeedback(it)
            switchCalls()
        }

        // Bind avatar name initials, colors, and query photos
        val nameToUse = contactName ?: formatNumber(number)
        binding.tvAvatarInitials.text = DialerHelper.getInitials(nameToUse)
        val color = DialerHelper.getAvatarColor(nameToUse)
        binding.avatarContainer.backgroundTintList = android.content.res.ColorStateList.valueOf(color)

        val contactInfo = ContactResolver.lookupByNumber(this, number)
        if (contactInfo?.photoUri != null) {
            val bitmap = DialerHelper.loadContactPhoto(this, contactInfo.photoUri)
            if (bitmap != null) {
                binding.ivAvatarPhoto.visibility = View.VISIBLE
                binding.ivAvatarPhoto.setImageBitmap(bitmap)
                binding.tvAvatarInitials.visibility = View.GONE
            } else {
                binding.ivAvatarPhoto.visibility = View.GONE
                binding.tvAvatarInitials.visibility = View.VISIBLE
            }
        } else {
            binding.ivAvatarPhoto.visibility = View.GONE
            binding.tvAvatarInitials.visibility = View.VISIBLE
        }

        // Setup DTMF Key Listeners
        val dtmfKeys = listOf(
            binding.dtmfKey1 to '1',
            binding.dtmfKey2 to '2',
            binding.dtmfKey3 to '3',
            binding.dtmfKey4 to '4',
            binding.dtmfKey5 to '5',
            binding.dtmfKey6 to '6',
            binding.dtmfKey7 to '7',
            binding.dtmfKey8 to '8',
            binding.dtmfKey9 to '9',
            binding.dtmfKeyStar to '*',
            binding.dtmfKey0 to '0',
            binding.dtmfKeyHash to '#'
        )
        dtmfKeys.forEach { (view, digit) ->
            setupDtmfKey(view, digit)
        }

        setupProximitySensor()
        registerPowerButtonReceiver()

        // Setup Video Call Surface Listeners
        val displayListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                displaySurface = android.view.Surface(st)
                checkAndSetVideoSurfaces()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                displaySurface?.release()
                displaySurface = null
                checkAndSetVideoSurfaces()
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        binding.videoDisplayView.surfaceTextureListener = displayListener

        val previewListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                previewSurface = android.view.Surface(st)
                checkAndSetVideoSurfaces()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                checkAndSetVideoSurfaces()
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        binding.videoPreviewView.surfaceTextureListener = previewListener

        refreshUi()
    }

    override fun onStart() {
        super.onStart()
        CallStateHub.addListener(stateListener)
        timerHandler.post(timerTick)
    }

    override fun onStop() {
        timerHandler.removeCallbacks(timerTick)
        CallStateHub.removeListener(stateListener)
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        number = intent.getStringExtra(EXTRA_NUMBER) ?: number
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: contactName
        isSavedContact = intent.getBooleanExtra(EXTRA_IS_SAVED_CONTACT, isSavedContact)
        refreshUi()
    }

    private fun refreshUi() {
        val call = CallStateHub.activeCall
        val state = call?.state ?: Call.STATE_DISCONNECTED
        val recording = CallStateHub.isRecordingActive()

        val isVideo = call != null && android.telecom.VideoProfile.isVideo(call.details.videoState)
        binding.videoCallContainer.visibility = if (isVideo) View.VISIBLE else View.GONE
        binding.btnVideo.isSelected = isVideo

        if (call != null) {
            val videoCall = call.videoCall
            if (videoCall != null && videoCall != lastBoundVideoCall) {
                lastBoundVideoCall?.unregisterCallback(videoCallCallback)
                lastBoundVideoCall = videoCall
                try {
                    videoCall.registerCallback(videoCallCallback)
                } catch (_: Exception) {}
                checkAndSetVideoSurfaces()
            } else if (videoCall == null && lastBoundVideoCall != null) {
                lastBoundVideoCall = null
            }
        }

        if (contactName != null) {
            binding.tvName.text = contactName
            binding.tvName.setTextColor(android.graphics.Color.WHITE)
        } else {
            val label = DialerHelper.getUnsavedContactLabel(number)
            binding.tvName.text = label
            if (label.contains("Spam") || label.contains("Scam") || label.contains("Telemarketer")) {
                binding.tvName.setTextColor(android.graphics.Color.parseColor("#FF453A"))
            } else {
                binding.tvName.setTextColor(android.graphics.Color.WHITE)
            }
        }
        val numberLine = displayNumberLine()
        binding.tvNumber.text = numberLine
        binding.tvNumber.visibility = if (numberLine.isEmpty()) View.GONE else View.VISIBLE
        
        binding.tvCountry.text = getCountryOrRegion(number)
        val queued = CallStateHub.pendingRecordOnConnect
        binding.tvStatus.text = if (queued && !recording) "Recording queued…" else statusText(state, recording)
        binding.recordingIndicator.visibility = if (recording) View.VISIBLE else View.GONE
        binding.btnRecord.isSelected = recording || queued

        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        val audioState = service?.callAudioState
        if (audioState != null) {
            binding.btnMute.isSelected = audioState.isMuted
            binding.btnMute.setImageResource(if (audioState.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.btnSpeaker.isSelected = (audioState.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        } else {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            binding.btnMute.isSelected = audio.isMicrophoneMute
            binding.btnMute.setImageResource(if (audio.isMicrophoneMute) R.drawable.ic_mic_off else R.drawable.ic_mic)
            binding.btnSpeaker.isSelected = audio.isSpeakerphoneOn
        }

        // Update Speaker Button state (icon and background selection) reflecting actual route
        val route = audioState?.route ?: -1
        updateSpeakerButton(route)

        // Update Recording Voice Source Indicator (only when recording is active)
        val recordingSourceIndicator = binding.recordingSourceIndicator
        val tvRecordingSourceText = binding.tvRecordingSourceText
        val iconView = binding.imgRecordingSourceIcon
        binding.recordingIndicator.visibility = android.view.View.GONE
        
        if (recording && service != null && service.recorder != null) {
            val recorderObj = service.recorder
            val activeSrc = recorderObj?.activeSource ?: -1
            
            val (sourceName, isTwoWay) = when (activeSrc) {
                android.media.MediaRecorder.AudioSource.VOICE_CALL -> Pair("Two-Way Recording", true)
                android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION -> Pair("Two-Way Recording", true)
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION -> {
                    if (recorderObj?.usedMicFallback == true) Pair("Phone Mic Recording (One-Way)", false)
                    else Pair("Bluetooth Mic Recording (One-Way)", false)
                }
                android.media.MediaRecorder.AudioSource.MIC -> {
                    if (recorderObj?.usedMicFallback == true) Pair("Phone Mic Recording (One-Way)", false)
                    else Pair("Microphone Recording (One-Way)", false)
                }
                else -> Pair("Call Recording", true)
            }
            tvRecordingSourceText.text = sourceName
            
            val iconRes = if (isTwoWay) R.drawable.ic_record else if (recorderObj?.usedMicFallback == true) R.drawable.ic_mic else R.drawable.ic_bluetooth
            iconView.setImageResource(iconRes)
            
            if (isTwoWay) {
                recordingSourceIndicator.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#1530D158"))
                    cornerRadius = 16f * resources.displayMetrics.density
                }
                tvRecordingSourceText.setTextColor(android.graphics.Color.parseColor("#30D158"))
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#30D158"))
            } else {
                recordingSourceIndicator.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#15FF9F0A"))
                    cornerRadius = 16f * resources.displayMetrics.density
                }
                tvRecordingSourceText.setTextColor(android.graphics.Color.parseColor("#FF9F0A"))
                iconView.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9F0A"))
            }
            recordingSourceIndicator.visibility = android.view.View.VISIBLE
        } else {
            recordingSourceIndicator.visibility = android.view.View.GONE
        }

        val ringing = state == Call.STATE_RINGING
        val incoming = ringing && call?.details?.callDirection == Call.Details.DIRECTION_INCOMING
        val active = !incoming && state != Call.STATE_DISCONNECTED

        binding.incomingActions.visibility = if (incoming) View.VISIBLE else View.GONE
        if (incoming) {
            startPulseAnimation()
        } else {
            stopPulseAnimation()
        }
        binding.activeActions.visibility = if (active) View.VISIBLE else View.GONE
        val recordVisible = active
        binding.containerRecord.visibility = if (recordVisible) View.VISIBLE else View.GONE
        binding.emptySpacerRecord.visibility = if (recordVisible) View.VISIBLE else View.GONE
        binding.containerHold.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnHold.isSelected = (state == Call.STATE_HOLDING)
        binding.tvHoldLabel.text = if (state == Call.STATE_HOLDING) "unhold" else "hold"
        binding.bottomActionsBar.visibility = if (active) View.VISIBLE else View.GONE

        if (state == Call.STATE_RINGING) {
            registerSensorListener()
        } else {
            unregisterSensorListener()
        }

        // Manage Proximity Wake Lock: Do not engage proximity sensor if speaker is active
        val isSpeaker = if (audioState != null) {
            audioState.route == android.telecom.CallAudioState.ROUTE_SPEAKER
        } else {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            audio.isSpeakerphoneOn
        }

        if (active && !isSpeaker) {
            acquireProximityWakeLock()
        } else {
            releaseProximityWakeLock()
        }

        // Manage Multi-Call / Conference UI
        val calls = com.secretdialer.app.service.DialerInCallService.getActiveCalls()
        val hasSecondaryCall = calls.size >= 2
        if (hasSecondaryCall) {
            val primary = calls.find { it == call } ?: call
            val secondary = calls.find { it != primary }
            if (secondary != null) {
                val secNum = secondary.details.handle?.schemeSpecificPart ?: ""
                val secContact = ContactResolver.lookupByNumber(this, secNum)
                binding.tvSecondaryName.text = secContact?.name ?: secNum
                val secStateText = when (secondary.state) {
                    Call.STATE_ACTIVE -> "Active"
                    Call.STATE_HOLDING -> "On hold"
                    Call.STATE_RINGING -> "Incoming"
                    Call.STATE_DIALING -> "Dialing"
                    else -> "Disconnected"
                }
                binding.tvSecondaryStatus.text = secStateText
                binding.secondaryCallContainer.visibility = View.VISIBLE
            } else {
                binding.secondaryCallContainer.visibility = View.GONE
            }
            binding.btnAddCall.setImageResource(R.drawable.ic_merge)
            binding.tvAddCallLabel.text = "Merge"
        } else {
            binding.secondaryCallContainer.visibility = View.GONE
            binding.btnAddCall.setImageResource(R.drawable.ic_add)
            binding.tvAddCallLabel.text = "Add call"
        }

        if (state == Call.STATE_DISCONNECTED) {
            releaseProximityWakeLock()
            unregisterPowerButtonReceiver()
            val typed = binding.tvDtmfDialed.text.toString()
            if (typed.isNotEmpty()) {
                val intent = android.content.Intent(this, MainActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = android.content.Intent.ACTION_DIAL
                    data = android.net.Uri.parse("tel:$typed")
                }
                startActivity(intent)
            }
            finish()
        }
    }

    private fun displayNumberLine(): String {
        val mask = Prefs.maskSavedContactNumbers(this) && isSavedContact
        return when {
            contactName != null && mask -> ""
            contactName != null -> number
            else -> formatNumber(number)
        }
    }

    private fun statusText(state: Int, recording: Boolean): String {
        if (recording && state == Call.STATE_ACTIVE) {
            return getString(R.string.recording) + " · " + elapsedLabel()
        }
        return when (state) {
            Call.STATE_RINGING -> getString(R.string.incoming_call)
            Call.STATE_DIALING -> getString(R.string.calling)
            Call.STATE_CONNECTING -> getString(R.string.connecting)
            Call.STATE_ACTIVE -> elapsedLabel()
            Call.STATE_HOLDING -> getString(R.string.on_hold)
            Call.STATE_DISCONNECTED -> getString(R.string.call_ended)
            else -> ""
        }
    }

    private fun getCountryOrRegion(num: String): String {
        val clean = num.replace(Regex("[^0-9+]"), "")
        return when {
            clean.startsWith("+91") -> "India"
            clean.startsWith("+1") -> "United States"
            clean.startsWith("+44") -> "United Kingdom"
            clean.startsWith("+86") -> "China"
            clean.startsWith("+81") -> "Japan"
            clean.startsWith("+49") -> "Germany"
            clean.startsWith("+33") -> "France"
            clean.startsWith("+61") -> "Australia"
            clean.length == 10 && !clean.contains("+") -> "India"
            else -> "India"
        }
    }

    private fun elapsedLabel(): String {
        val call = CallStateHub.activeCall ?: return getString(R.string.connected)
        val connect = call.details.connectTimeMillis
        if (connect <= 0) return getString(R.string.connected)
        val secs = ((System.currentTimeMillis() - connect) / 1000).toInt()
        val m = secs / 60
        val s = secs % 60
        return String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    private fun formatNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }
        if (digits.length == 10) {
            return "${digits.take(3)} ${digits.drop(3).take(3)} ${digits.takeLast(4)}"
        }
        return raw
    }

    private fun endCall() {
        CallStateHub.activeCall?.disconnect()
        finish()
    }

    private fun answerCall() {
        val incomingCall = CallStateHub.activeCall
        if (incomingCall != null) {
            val calls = com.secretdialer.app.service.DialerInCallService.getActiveCalls()
            calls.forEach { otherCall ->
                if (otherCall != incomingCall && (otherCall.state == Call.STATE_ACTIVE || otherCall.state == Call.STATE_DIALING)) {
                    otherCall.hold()
                }
            }
            incomingCall.answer(incomingCall.details.videoState)
        }
    }

    private fun rejectCall() {
        CallStateHub.activeCall?.reject(false, null)
        finish()
    }

    private fun toggleRecording() {
        val call = CallStateHub.activeCall
        if (call == null || call.state == Call.STATE_DISCONNECTED) return

        if (call.state != Call.STATE_ACTIVE) {
            CallStateHub.pendingRecordOnConnect = !CallStateHub.pendingRecordOnConnect
            if (CallStateHub.pendingRecordOnConnect) {
                Toast.makeText(this, "Recording scheduled on connect", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Recording schedule cancelled", Toast.LENGTH_SHORT).show()
            }
            refreshUi()
            return
        }

        val nowRecording = CallStateHub.toggleRecording()
        if (nowRecording) {
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
            val service = com.secretdialer.app.service.DialerInCallService.getInstance()
            val currentRoute = service?.callAudioState?.route ?: -1
            if (currentRoute == android.telecom.CallAudioState.ROUTE_BLUETOOTH || currentRoute == android.telecom.CallAudioState.ROUTE_WIRED_HEADSET) {
                Toast.makeText(
                    this,
                    "Headset/Bluetooth active: Google blocks two-way recording. Switch to Speaker for two-way audio.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        refreshUi()
    }

    private val timerHandler = Handler(Looper.getMainLooper())

    private fun toggleMute() {
        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        if (service != null) {
            val audioState = service.callAudioState
            val nextMute = !(audioState?.isMuted ?: false)
            service.setMuted(nextMute)
            binding.btnMute.isSelected = nextMute
        } else {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val next = !audio.isMicrophoneMute
            audio.isMicrophoneMute = next
            binding.btnMute.isSelected = next
        }
    }

    private fun toggleSpeaker() {
        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        if (service != null) {
            val audioState = service.callAudioState
            val currentRoute = audioState?.route ?: android.telecom.CallAudioState.ROUTE_EARPIECE
            val nextRoute = if (currentRoute == android.telecom.CallAudioState.ROUTE_SPEAKER) {
                android.telecom.CallAudioState.ROUTE_EARPIECE
            } else {
                android.telecom.CallAudioState.ROUTE_SPEAKER
            }
            service.setAudioRoute(nextRoute)
            binding.btnSpeaker.isSelected = (nextRoute == android.telecom.CallAudioState.ROUTE_SPEAKER)
        } else {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val next = !audio.isSpeakerphoneOn
            audio.isSpeakerphoneOn = next
            binding.btnSpeaker.isSelected = next
        }
    }

    private fun toggleKeypad() {
        val show = binding.dtmfPadContainer.visibility != View.VISIBLE
        binding.dtmfPadContainer.visibility = if (show) View.VISIBLE else View.GONE
        binding.btnKeypad.isSelected = show
        binding.callDetailsLayout.visibility = if (show) View.GONE else View.VISIBLE
    }

    private val dtmfBuilder = StringBuilder()

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupDtmfKey(view: View, digit: Char) {
        view.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    CallStateHub.activeCall?.playDtmfTone(digit)
                    appendDtmfDigit(digit)
                    DialerHelper.playHapticFeedback(view)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    CallStateHub.activeCall?.stopDtmfTone()
                }
            }
            false
        }
    }

    private fun appendDtmfDigit(digit: Char) {
        dtmfBuilder.append(digit)
        binding.tvDtmfDialed.text = dtmfBuilder.toString()
    }

    private var proximityWakeLock: android.os.PowerManager.WakeLock? = null

    private fun setupProximitySensor() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximityWakeLock = pm.newWakeLock(
                        android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "com.secretdialer.app:proximity_lock"
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InCallActivity", "Failed to setup proximity lock", e)
        }
    }

    private fun acquireProximityWakeLock() {
        proximityWakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (e: Exception) {}
            }
        }
    }

    private var powerButtonReceiver: android.content.BroadcastReceiver? = null
    private var powerClickCount = 0

    private fun registerPowerButtonReceiver() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        powerButtonReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                val call = CallStateHub.activeCall
                val state = call?.state ?: Call.STATE_DISCONNECTED
                
                when (intent.action) {
                    android.content.Intent.ACTION_SCREEN_OFF -> {
                        if (state == Call.STATE_RINGING) {
                            powerClickCount++
                            if (powerClickCount == 1) {
                                silenceRinger()
                            } else if (powerClickCount >= 2) {
                                rejectCall()
                            }
                        } else if (state == Call.STATE_ACTIVE) {
                            if (Prefs.isPowerButtonEndCallEnabled(context)) {
                                endCall()
                            }
                        }
                    }
                    android.content.Intent.ACTION_SCREEN_ON -> {
                        if (state == Call.STATE_RINGING) {
                            powerClickCount++
                            if (powerClickCount >= 2) {
                                rejectCall()
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(powerButtonReceiver, filter)
    }

    private fun unregisterPowerButtonReceiver() {
        powerButtonReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {}
        }
        powerButtonReceiver = null
    }

    private fun silenceRinger() {
        try {
            val tm = getSystemService(android.content.Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            tm.silenceRinger()
            Toast.makeText(this, "Ringtone Muted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try {
                val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                audio.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
            } catch (ex: Exception) {}
        }
    }

    override fun onDestroy() {
        releaseProximityWakeLock()
        unregisterPowerButtonReceiver()
        unregisterSensorListener()
        super.onDestroy()
    }

    // ── ODialer-style inline audio route panel ──────────────────────────────
    private var audioRoutePanelVisible = false

    private fun showAudioRouteSelectorDialog() {
        if (audioRoutePanelVisible) {
            hideAudioRoutePanel()
            return
        }

        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        val audioState = service?.callAudioState

        // Determine which routes are available
        val mask = audioState?.supportedRouteMask ?: (android.telecom.CallAudioState.ROUTE_EARPIECE or android.telecom.CallAudioState.ROUTE_SPEAKER)
        val currentRoute = audioState?.route ?: android.telecom.CallAudioState.ROUTE_EARPIECE

        val hasWired = (mask and android.telecom.CallAudioState.ROUTE_WIRED_HEADSET) != 0
        val hasBluetooth = (mask and android.telecom.CallAudioState.ROUTE_BLUETOOTH) != 0

        // Show/hide rows based on connected hardware
        binding.routeWiredHeadset.visibility = if (hasWired) View.VISIBLE else View.GONE
        binding.dividerWired.visibility = if (hasWired) View.VISIBLE else View.GONE
        binding.routeBluetooth.visibility = if (hasBluetooth) View.VISIBLE else View.GONE

        // Try to get Bluetooth device name if connected
        if (hasBluetooth && audioState != null) {
            try {
                val btName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    audioState.activeBluetoothDevice?.name ?: "Bluetooth Device"
                } else {
                    "Bluetooth Device"
                }
                binding.tvBluetoothName.text = btName
            } catch (_: Exception) {
                binding.tvBluetoothName.text = "Bluetooth Device"
            }
        }

        // Update checkmarks to show active route
        fun setCheck(check: View, active: Boolean) {
            check.visibility = if (active) View.VISIBLE else View.GONE
        }
        setCheck(binding.checkRouteSpeaker, currentRoute == android.telecom.CallAudioState.ROUTE_SPEAKER)
        setCheck(binding.checkRouteEarpiece, currentRoute == android.telecom.CallAudioState.ROUTE_EARPIECE)
        setCheck(binding.checkRouteWired, currentRoute == android.telecom.CallAudioState.ROUTE_WIRED_HEADSET)
        setCheck(binding.checkRouteBluetooth, currentRoute == android.telecom.CallAudioState.ROUTE_BLUETOOTH)

        // Highlight active row label blue
        fun rowColor(routeId: Int) = if (currentRoute == routeId) android.graphics.Color.parseColor("#0A84FF") else android.graphics.Color.WHITE
        binding.tvSpeakerName.setTextColor(rowColor(android.telecom.CallAudioState.ROUTE_SPEAKER))
        binding.tvEarpieceName.setTextColor(rowColor(android.telecom.CallAudioState.ROUTE_EARPIECE))
        binding.tvWiredName.setTextColor(rowColor(android.telecom.CallAudioState.ROUTE_WIRED_HEADSET))
        binding.tvBluetoothName.setTextColor(rowColor(android.telecom.CallAudioState.ROUTE_BLUETOOTH))

        // Wire click listeners
        binding.routeSpeaker.setOnClickListener {
            selectRoute(android.telecom.CallAudioState.ROUTE_SPEAKER); hideAudioRoutePanel()
        }
        binding.routeEarpiece.setOnClickListener {
            selectRoute(android.telecom.CallAudioState.ROUTE_EARPIECE); hideAudioRoutePanel()
        }
        binding.routeWiredHeadset.setOnClickListener {
            selectRoute(android.telecom.CallAudioState.ROUTE_WIRED_HEADSET); hideAudioRoutePanel()
        }
        binding.routeBluetooth.setOnClickListener {
            selectRoute(android.telecom.CallAudioState.ROUTE_BLUETOOTH); hideAudioRoutePanel()
        }

        // Animate panel in
        binding.audioRoutePanel.visibility = View.VISIBLE
        binding.audioRoutePanel.alpha = 0f
        binding.audioRoutePanel.translationY = 40f
        binding.audioRoutePanel.animate().alpha(1f).translationY(0f).setDuration(200).start()
        audioRoutePanelVisible = true

        // Highlight speaker button to show panel is open
        binding.btnSpeaker.isSelected = true
    }

    private fun hideAudioRoutePanel() {
        if (!audioRoutePanelVisible) return
        binding.audioRoutePanel.animate().alpha(0f).translationY(40f).setDuration(150)
            .withEndAction { binding.audioRoutePanel.visibility = View.GONE }
            .start()
        audioRoutePanelVisible = false
        // Restore speaker button highlight to reflect actual route
        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        val audioState = service?.callAudioState
        updateSpeakerButton(audioState?.route ?: -1)
    }

    private fun selectRoute(route: Int) {
        val service = com.secretdialer.app.service.DialerInCallService.getInstance()
        if (service != null) {
            service.setAudioRoute(route)
        } else {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            audio.isSpeakerphoneOn = (route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
        refreshUi()
    }

    private fun updateSpeakerButton(route: Int) {
        val label = binding.tvSpeakerLabel
        when (route) {
            android.telecom.CallAudioState.ROUTE_BLUETOOTH -> {
                binding.btnSpeaker.isSelected = true
                binding.btnSpeaker.setImageResource(R.drawable.ic_bluetooth)
                binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0A84FF"))
                label.text = "Bluetooth"
                label.setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            }
            android.telecom.CallAudioState.ROUTE_SPEAKER -> {
                binding.btnSpeaker.isSelected = true
                binding.btnSpeaker.setImageResource(R.drawable.ic_speaker)
                binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#30D158"))
                label.text = "Speaker"
                label.setTextColor(android.graphics.Color.parseColor("#30D158"))
            }
            android.telecom.CallAudioState.ROUTE_WIRED_HEADSET -> {
                binding.btnSpeaker.isSelected = true
                binding.btnSpeaker.setImageResource(R.drawable.ic_mic)
                binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD60A"))
                label.text = "Headset"
                label.setTextColor(android.graphics.Color.parseColor("#FFD60A"))
            }
            android.telecom.CallAudioState.ROUTE_EARPIECE -> {
                binding.btnSpeaker.isSelected = true
                binding.btnSpeaker.setImageResource(R.drawable.ic_phone)
                binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1C1C1E"))
                label.text = "Earpiece"
                label.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
            }
            else -> {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager
                if (audio.isBluetoothScoOn || audio.isBluetoothA2dpOn) {
                    binding.btnSpeaker.isSelected = true
                    binding.btnSpeaker.setImageResource(R.drawable.ic_bluetooth)
                    binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#0A84FF"))
                    label.text = "Bluetooth"
                    label.setTextColor(android.graphics.Color.parseColor("#0A84FF"))
                } else if (audio.isSpeakerphoneOn) {
                    binding.btnSpeaker.isSelected = true
                    binding.btnSpeaker.setImageResource(R.drawable.ic_speaker)
                    binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#30D158"))
                    label.text = "Speaker"
                    label.setTextColor(android.graphics.Color.parseColor("#30D158"))
                } else if (audio.isWiredHeadsetOn) {
                    binding.btnSpeaker.isSelected = true
                    binding.btnSpeaker.setImageResource(R.drawable.ic_mic)
                    binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFD60A"))
                    label.text = "Headset"
                    label.setTextColor(android.graphics.Color.parseColor("#FFD60A"))
                } else {
                    binding.btnSpeaker.isSelected = true
                    binding.btnSpeaker.setImageResource(R.drawable.ic_phone)
                    binding.btnSpeaker.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1C1C1E"))
                    label.text = "Earpiece"
                    label.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                }
            }
        }
    }

    private fun showQuickSmsDeclineDialog() {
        val templates = arrayOf(
            "Can't talk now. What's up?",
            "In a meeting. I'll call you back.",
            "I'm driving. Will call shortly.",
            "Sorry, busy right now."
        )

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).create()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_modern_dialog)
            setPadding(48, 48, 48, 48)
        }

        val tvTitle = android.widget.TextView(this).apply {
            text = "Decline with Message"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }
        container.addView(tvTitle)

        templates.forEachIndexed { index, msg ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(16, 24, 16, 24)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    sendSmsAndDecline(msg)
                    dialog.dismiss()
                }
            }
            val tvName = android.widget.TextView(this).apply {
                text = msg
                setTextColor(android.graphics.Color.WHITE)
                textSize = 15f
            }
            row.addView(tvName)
            container.addView(row)

            if (index < templates.size - 1) {
                val sep = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E"))
                }
                container.addView(sep)
            }
        }

        val btnCancel = android.widget.TextView(this).apply {
            text = "Cancel"
            setTextColor(android.graphics.Color.parseColor("#FF453A"))
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

    private fun sendSmsAndDecline(message: String) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                smsManager.sendTextMessage(number, null, message, null, null)
                Toast.makeText(this, "Decline message sent", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to send SMS", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "SMS Permission not granted", Toast.LENGTH_SHORT).show()
        }
        rejectCall()
    }

    private var sensorManager: android.hardware.SensorManager? = null
    private var accelerometer: android.hardware.Sensor? = null
    private var sensorListener: android.hardware.SensorEventListener? = null

    private fun registerSensorListener() {
        if (!Prefs.isFlipToSilenceEnabled(this)) return
        if (sensorListener != null) return
        
        sensorManager = getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        
        sensorListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                    val z = event.values[2]
                    if (z < -8.0) {
                        silenceRinger()
                        unregisterSensorListener()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor, accuracy: Int) {}
        }
        sensorManager?.registerListener(sensorListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterSensorListener() {
        sensorListener?.let {
            sensorManager?.unregisterListener(it)
        }
        sensorListener = null
        sensorManager = null
        accelerometer = null
    }

    private fun addCall() {
        val intent = android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = android.content.Intent.ACTION_DIAL
        }
        startActivity(intent)
    }

    private fun mergeCalls() {
        val calls = com.secretdialer.app.service.DialerInCallService.getActiveCalls()
        if (calls.size >= 2) {
            val active = calls.find { it.state == Call.STATE_ACTIVE } ?: calls.first()
            val held = calls.find { it.state == Call.STATE_HOLDING } ?: calls.last()
            if (active != held) {
                active.conference(held)
            }
        }
    }

    private fun toggleHold() {
        val call = CallStateHub.activeCall ?: return
        if (call.state == Call.STATE_HOLDING) {
            call.unhold()
        } else {
            call.hold()
        }
    }

    private fun switchCalls() {
        val calls = com.secretdialer.app.service.DialerInCallService.getActiveCalls()
        if (calls.size >= 2) {
            val primary = CallStateHub.activeCall ?: return
            val secondary = calls.find { it != primary } ?: return
            
            // Switch states: hold active one, unhold held one
            if (primary.state == Call.STATE_ACTIVE) {
                primary.hold()
            }
            if (secondary.state == Call.STATE_HOLDING) {
                secondary.unhold()
            }
            // Set CallStateHub activeCall to the switched call
            CallStateHub.activeCall = secondary
            refreshUi()
        }
    }

    private fun upgradeToVideo() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 1001)
            return
        }
        val targetNumber = number.ifBlank { return }
        try {
            // Try to upgrade active call via Telecom API first (works on VoLTE carriers)
            val c = CallStateHub.activeCall
            val videoCall = c?.videoCall
            if (videoCall != null) {
                val profile = android.telecom.VideoProfile(android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
                videoCall.sendSessionModifyRequest(profile)
                return
            }
            // Fallback: launch a fresh video call via system dialer intent
            val uri = android.net.Uri.parse("tel:$targetNumber")
            val intent = android.content.Intent(android.content.Intent.ACTION_CALL, uri).apply {
                putExtra("android.telecom.extra.START_CALL_WITH_VIDEO_STATE",
                    android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Video call not supported by your carrier.", Toast.LENGTH_LONG).show()
        }
    }
    private fun checkAndSetVideoSurfaces() {
        val c = CallStateHub.activeCall ?: return
        val videoCall = c.videoCall ?: return
        try {
            videoCall.setDisplaySurface(displaySurface)
            videoCall.setPreviewSurface(previewSurface)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPulseAnimation() {
        if (pulseAnimator != null) return
        val pulse = binding.swipePulse
        pulse.visibility = android.view.View.VISIBLE
        pulse.scaleX = 1.0f
        pulse.scaleY = 1.0f
        pulse.alpha = 1.0f

        val scaleX = android.animation.ObjectAnimator.ofFloat(pulse, "scaleX", 1.0f, 1.8f)
        scaleX.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleX.repeatMode = android.animation.ValueAnimator.RESTART

        val scaleY = android.animation.ObjectAnimator.ofFloat(pulse, "scaleY", 1.0f, 1.8f)
        scaleY.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY.repeatMode = android.animation.ValueAnimator.RESTART

        val alpha = android.animation.ObjectAnimator.ofFloat(pulse, "alpha", 1.0f, 0.0f)
        alpha.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha.repeatMode = android.animation.ValueAnimator.RESTART

        val set = android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1400
            interpolator = android.view.animation.DecelerateInterpolator()
        }
        pulseAnimator = set
        set.start()
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.swipePulse.visibility = android.view.View.GONE
    }

    private fun updateSwipeProgress(
        tx: Float,
        centerX: Float,
        padPx: Float,
        maxX: Float,
        trackW: Float
    ) {
        val handle = binding.swipeHandle
        val hint = binding.tvSwipeHint
        val answerFill = binding.swipeAnswerFill
        val declineFill = binding.swipeDeclineFill
        val tvDecline = binding.tvDeclineLabel
        val tvAnswer = binding.tvAnswerLabel
        val imgAnswer = binding.imgAnswerTarget
        val imgDecline = binding.imgDeclineTarget

        handle.translationX = tx

        // Fade out hint
        val distFromCenter = kotlin.math.abs(tx - centerX) / (trackW / 2f)
        hint.alpha = (1f - distFromCenter * 1.8f).coerceIn(0f, 1f)

        val handleW = handle.width

        if (tx >= centerX) {
            // Dragging towards Answer (right)
            val maxDist = maxX - centerX
            val ratio = if (maxDist > 0f) ((tx - centerX) / maxDist).coerceIn(0f, 1f) else 0f
            
            val bgColor = androidx.core.graphics.ColorUtils.blendARGB(0xFFFFFFFF.toInt(), 0xFF30D158.toInt(), ratio)
            handle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
            
            val imgColor = androidx.core.graphics.ColorUtils.blendARGB(0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), ratio)
            handle.imageTintList = android.content.res.ColorStateList.valueOf(imgColor)

            // Rotate handset icon straight up when sliding to answer
            handle.rotation = -45f * ratio

            // Flowing width from center to handle position
            val fillW = (tx - centerX).toInt().coerceAtLeast(0) + (handleW / 2)
            val params = answerFill.layoutParams
            if (params.width != fillW) {
                params.width = fillW
                answerFill.layoutParams = params
            }
            answerFill.translationX = centerX + (handleW / 2)
            answerFill.alpha = ratio * 0.95f
            declineFill.alpha = 0f

            tvDecline.alpha = ((1f - ratio) * 0.7f).coerceIn(0f, 0.7f)
            tvAnswer.alpha = 0.7f

            // Animate Targets
            imgAnswer.scaleX = 1.0f + 0.3f * ratio
            imgAnswer.scaleY = 1.0f + 0.3f * ratio
            imgAnswer.alpha = 0.6f + 0.4f * ratio

            imgDecline.scaleX = 1.0f - 0.6f * ratio
            imgDecline.scaleY = 1.0f - 0.6f * ratio
            imgDecline.alpha = 0.6f - 0.6f * ratio
        } else {
            // Dragging towards Decline (left)
            val maxDist = centerX - padPx
            val ratio = if (maxDist > 0f) ((centerX - tx) / maxDist).coerceIn(0f, 1f) else 0f
            
            val bgColor = androidx.core.graphics.ColorUtils.blendARGB(0xFFFFFFFF.toInt(), 0xFFFF453A.toInt(), ratio)
            handle.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
            
            val imgColor = androidx.core.graphics.ColorUtils.blendARGB(0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), ratio)
            handle.imageTintList = android.content.res.ColorStateList.valueOf(imgColor)

            // Rotate handset icon down to decline hang-up position when sliding to decline
            handle.rotation = 90f * ratio

            // Flowing width from handle position to center
            val fillW = (centerX - tx).toInt().coerceAtLeast(0) + (handleW / 2)
            val params = declineFill.layoutParams
            if (params.width != fillW) {
                params.width = fillW
                declineFill.layoutParams = params
            }
            declineFill.translationX = tx
            declineFill.alpha = ratio * 0.95f
            answerFill.alpha = 0f

            tvAnswer.alpha = ((1f - ratio) * 0.7f).coerceIn(0f, 0.7f)
            tvDecline.alpha = 0.7f

            // Animate Targets
            imgDecline.scaleX = 1.0f + 0.3f * ratio
            imgDecline.scaleY = 1.0f + 0.3f * ratio
            imgDecline.alpha = 0.6f + 0.4f * ratio

            imgAnswer.scaleX = 1.0f - 0.6f * ratio
            imgAnswer.scaleY = 1.0f - 0.6f * ratio
            imgAnswer.alpha = 0.6f - 0.6f * ratio
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeToActGesture() {
        val handle = binding.swipeHandle
        val track = binding.swipeTrack

        val padPx = (4 * resources.displayMetrics.density)
        var trackW = 0f
        var maxX = 0f
        var centerX = 0f
        var dimensionsReady = false

        track.addOnLayoutChangeListener(object : android.view.View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: android.view.View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                val width = right - left
                if (width > 0) {
                    trackW = width.toFloat()
                    val handleW = handle.width.toFloat()
                    if (handleW > 0) {
                        track.removeOnLayoutChangeListener(this)
                        maxX = trackW - handleW - padPx
                        centerX = (trackW - handleW) / 2f
                        handle.translationX = centerX
                        binding.swipePulse.translationX = centerX
                        dimensionsReady = true
                    }
                }
            }
        })

        var startRawX = 0f
        var startTranslation = 0f
        var thresholdFeedbackPlayed = false

        fun animateTo(targetX: Float, duration: Long, onEnd: (() -> Unit)? = null) {
            val startX = handle.translationX
            val animator = android.animation.ValueAnimator.ofFloat(startX, targetX)
            animator.duration = duration
            if (targetX == centerX) {
                animator.interpolator = android.view.animation.OvershootInterpolator(2f)
            } else {
                animator.interpolator = android.view.animation.DecelerateInterpolator()
            }
            animator.addUpdateListener { anim ->
                val currVal = anim.animatedValue as Float
                updateSwipeProgress(currVal, centerX, padPx, maxX, trackW)
            }
            if (onEnd != null) {
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd()
                    }
                })
            }
            animator.start()
        }

        track.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!dimensionsReady || trackW == 0f) {
                        trackW = track.width.toFloat()
                        val handleW = handle.width.toFloat()
                        maxX = trackW - handleW - padPx
                        centerX = (trackW - handleW) / 2f
                        dimensionsReady = true
                    }
                    startRawX = event.rawX
                    startTranslation = handle.translationX
                    thresholdFeedbackPlayed = false
                    
                    stopPulseAnimation()
                    
                    handle.animate().cancel()
                    handle.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150).start()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (trackW == 0f) return@setOnTouchListener false
                    val dx = event.rawX - startRawX
                    val newTx = (startTranslation + dx).coerceIn(padPx, maxX)

                    val answerThreshold = trackW * 0.72f
                    val declineThreshold = trackW * 0.28f

                    if (newTx >= answerThreshold || newTx <= declineThreshold) {
                        if (!thresholdFeedbackPlayed) {
                            DialerHelper.playHapticFeedback(handle)
                            thresholdFeedbackPlayed = true
                        }
                    } else {
                        thresholdFeedbackPlayed = false
                    }

                    updateSwipeProgress(newTx, centerX, padPx, maxX, trackW)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (trackW == 0f) return@setOnTouchListener false
                    
                    handle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()

                    val tx = handle.translationX
                    val answerThreshold = trackW * 0.72f
                    val declineThreshold = trackW * 0.28f

                    when {
                        tx >= answerThreshold -> {
                            animateTo(maxX, 120) {
                                DialerHelper.playHapticFeedback(handle)
                                answerCall()
                            }
                        }
                        tx <= declineThreshold -> {
                            animateTo(padPx, 120) {
                                DialerHelper.playHapticFeedback(handle)
                                rejectCall()
                            }
                        }
                        else -> {
                            animateTo(centerX, 280) {
                                binding.tvDeclineLabel.alpha = 0.7f
                                binding.tvAnswerLabel.alpha = 0.7f
                                startPulseAnimation()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        const val EXTRA_NUMBER = "extra_number"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_IS_SAVED_CONTACT = "extra_is_saved"
    }
}
