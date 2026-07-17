package com.secretdialer.app.recorder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.secretdialer.app.Prefs
import com.secretdialer.app.service.DialerInCallService
import android.telecom.TelecomManager
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Cross-device call recorder.
 *
 * Modern Android often blocks telephony downlink mix for third-party dialers.
 * Portable approach: open MIC after InCallService routes to SPEAKER so the room
 * mic can hear both sides. Do not fight Telecom by changing AudioManager.mode /
 * isSpeakerphoneOn — those are ignored during an InCallService call.
 */
class CallRecorder(private val context: Context) {

    @Volatile
    private var isRecordingActive = false
    @Volatile
    private var isMediaRecorderActive = false
    private var mediaRecorderRef: MediaRecorder? = null
    private var currentFile: File? = null
    var usedMicFallback = false
        private set
    var activeSource: Int = -1
        private set

    @Volatile
    private var stopRequested = false
    private var recordingThread: Thread? = null
    private var tempWavFile: File? = null
    @Volatile
    private var audioRecordRef: AudioRecord? = null

    val isRecording: Boolean
        get() = isRecordingActive || isMediaRecorderActive

    fun start(number: String, contactName: String?): File? {
        if (isRecordingActive || isMediaRecorderActive) return currentFile

        logDiagnostics()

        val dir = File(context.filesDir, RECORDINGS_DIR).apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = (contactName ?: number).replace(Regex("[^a-zA-Z0-9._-]"), "_").take(24)

        val wavFile = File(dir, "${timestamp}_${safeName}.wav")
        val mp3File = File(dir, "${timestamp}_${safeName}.mp3")
        val m4aFile = File(dir, "${timestamp}_${safeName}.m4a")

        tempWavFile = wavFile
        currentFile = mp3File
        usedMicFallback = false
        activeSource = -1
        stopRequested = false
        isMediaRecorderActive = false

        isRecordingActive = true
        startAudioRecordThread(wavFile, m4aFile)
        return mp3File
    }

    fun stop(): File? {
        if (isMediaRecorderActive && mediaRecorderRef != null) {
            isMediaRecorderActive = false
            isRecordingActive = false
            try {
                mediaRecorderRef?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaRecorder", e)
            }
            try {
                mediaRecorderRef?.release()
            } catch (_: Exception) {}
            mediaRecorderRef = null

            val file = currentFile
            if (file != null && file.exists() && file.length() > 0) {
                autoSaveToPublicFolder(file, "audio/mp4")
            }
            currentFile = null
            tempWavFile = null
            return file
        }

        if (!isRecordingActive && recordingThread == null && tempWavFile == null) return null
        stopRequested = true
        isRecordingActive = false

        try {
            audioRecordRef?.stop()
        } catch (_: Exception) {}

        try {
            recordingThread?.join(8000)
        } catch (_: Exception) {}
        recordingThread = null
        audioRecordRef = null

        val wavFile = tempWavFile
        val mp3File = currentFile

        if (wavFile != null && mp3File != null && wavFile.exists()) {
            val pcmBytes = (wavFile.length() - 44).coerceAtLeast(0)
            if (pcmBytes < 1600) {
                Log.e(TAG, "WAV too short to encode ($pcmBytes pcm bytes). Call ended before capture started.")
                try {
                    wavFile.delete()
                } catch (_: Exception) {}
            } else {
                try {
                    updateWavSizes(wavFile)
                    val encodedOk = encodeWavToMp3(wavFile, mp3File)
                    if (encodedOk) {
                        if (wavFile.exists()) wavFile.delete()
                        autoSaveToPublicFolder(mp3File)
                    } else {
                        Log.e(TAG, "MP3 encoding failed or produced empty output: ${mp3File.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed during WAV to MP3 conversion", e)
                }
            }
        }

        tempWavFile = null
        currentFile = null
        return mp3File
    }

    private fun logDiagnostics() {
        try {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && roleManager != null) {
                roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)
            } else {
                context.packageName == telecomManager.defaultDialerPackage
            }
            val micPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            var isAccEnabled = false
            try {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                val list = am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                isAccEnabled = list?.any {
                    it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name.contains("CallRecordingAccessibilityService")
                } ?: false
            } catch (_: Exception) {}

            Log.i(TAG, "DIAGNOSTICS: isDefaultDialer=$isDefault, RECORD_AUDIO_granted=$micPermission, accessibilityHelperEnabled=$isAccEnabled")
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostics logger failed", e)
        }
    }

    private fun encodeWavToMp3(wavFile: File, mp3File: File): Boolean {
        val wavPath = wavFile.absolutePath
        val mp3Path = mp3File.absolutePath

        try {
            Log.i(TAG, "Starting MP3 encoding (try1): ${wavFile.name} -> ${mp3File.name}")
            de.sciss.jump3r.Main().run(arrayOf("-b", "128", wavPath, mp3Path))
        } catch (e: Exception) {
            Log.e(TAG, "MP3 encoding try1 threw exception", e)
        }
        if (mp3File.exists() && mp3File.length() > 0) return true

        try {
            Log.i(TAG, "Starting MP3 encoding (try2): ${wavFile.name} -> ${mp3File.name}")
            de.sciss.jump3r.Main().run(arrayOf("-b", "128", "--resample", "16", wavPath, mp3Path))
        } catch (e: Exception) {
            Log.e(TAG, "MP3 encoding try2 threw exception", e)
        }

        return mp3File.exists() && mp3File.length() > 0
    }

    private data class OpenedRecord(
        val record: AudioRecord,
        val sampleRate: Int,
        val bufferSize: Int,
        val source: Int
    )

    private fun getBuiltInMicDevice(): android.media.AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val devices = audioManager?.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS) ?: return null
        for (device in devices) {
            if (device.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                return device
            }
        }
        return null
    }

    private fun isBluetoothHeadsetActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        if (audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            if (commDevice != null) {
                val t = commDevice.type
                if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return true
                }
            }
        }
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS or android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            val t = d.type
            if (t == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return true
            }
        }
        return false
    }

    private fun isWiredHeadsetActive(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        if (audioManager.isWiredHeadsetOn) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            if (commDevice != null) {
                val t = commDevice.type
                if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                    return true
                }
            }
        }
        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS or android.media.AudioManager.GET_DEVICES_OUTPUTS)
        for (d in devices) {
            val t = d.type
            if (t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                t == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                t == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                return true
            }
        }
        return false
    }

    private fun tryOpenRecord(source: Int, sampleRate: Int, preferBuiltInMic: Boolean = false): OpenedRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBuffer <= 0) return null
        val bufferSize = minBuffer * 2
        return try {
            val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioEncoding)
                    .setChannelMask(channelConfig)
                    .build()
                val builder = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && source != MediaRecorder.AudioSource.VOICE_CALL) {
                    try {
                        builder.setPrivacySensitive(false)
                    } catch (_: Exception) {}
                }
                builder.build()
            } else {
                AudioRecord(source, sampleRate, channelConfig, audioEncoding, bufferSize)
            }
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                if (preferBuiltInMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val builtinMic = getBuiltInMicDevice()
                    if (builtinMic != null) {
                        try {
                            candidate.setPreferredDevice(builtinMic)
                            Log.i(TAG, "Forced built-in microphone for source=$source")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed setPreferredDevice builtinMic for source=$source", e)
                        }
                    }
                }
                OpenedRecord(candidate, sampleRate, bufferSize, source)
            } else {
                candidate.release()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "tryOpenRecord failed source=$source rate=$sampleRate", e)
            null
        }
    }

    private fun peakOf(data: ShortArray, count: Int): Int {
        var peak = 0
        for (i in 0 until count) {
            val v = abs(data[i].toInt())
            if (v > peak) peak = v
        }
        return peak
    }

    private data class TrialConfig(
        val source: Int,
        val sampleRate: Int,
        val preferBuiltInMic: Boolean
    )

    private fun startAudioRecordThread(file: File, m4aFile: File) {
        recordingThread = Thread({
            var audioRecord: AudioRecord? = null
            var outStream: BufferedOutputStream? = null
            try {
                val isBluetooth = isBluetoothHeadsetActive()
                val isWired = isWiredHeadsetActive()
                Log.i(TAG, "startAudioRecordThread: isBluetooth=$isBluetooth, isWired=$isWired")

                val configs = mutableListOf<TrialConfig>()
                val rates = intArrayOf(16000, 44100, 8000)

                val prioritizedSources = intArrayOf(
                    MediaRecorder.AudioSource.VOICE_CALL,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                )

                if (isBluetooth) {
                    for (src in prioritizedSources) {
                        for (rate in rates) {
                            configs.add(TrialConfig(src, rate, false))
                        }
                    }
                    for (src in prioritizedSources) {
                        for (rate in rates) {
                            configs.add(TrialConfig(src, rate, true))
                        }
                    }
                } else if (isWired) {
                    for (src in prioritizedSources) {
                        for (rate in rates) {
                            configs.add(TrialConfig(src, rate, true))
                        }
                    }
                    for (src in prioritizedSources) {
                        for (rate in rates) {
                            configs.add(TrialConfig(src, rate, false))
                        }
                    }
                } else {
                    val nonHeadsetSources = intArrayOf(
                        MediaRecorder.AudioSource.VOICE_CALL,
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.CAMCORDER
                    )
                    for (src in nonHeadsetSources) {
                        for (rate in rates) {
                            configs.add(TrialConfig(src, rate, false))
                        }
                    }
                }

                var opened: OpenedRecord? = null
                var firstInitializedRecord: OpenedRecord? = null

                for (cfg in configs) {
                    if (stopRequested) break
                    val candidate = tryOpenRecord(cfg.source, cfg.sampleRate, preferBuiltInMic = cfg.preferBuiltInMic) ?: continue
                    
                    if (firstInitializedRecord == null) {
                        firstInitializedRecord = candidate
                    }

                    try {

                        candidate.record.startRecording()

                        val isStandardMic = (
                            cfg.source == MediaRecorder.AudioSource.MIC ||
                            cfg.source == MediaRecorder.AudioSource.VOICE_RECOGNITION ||
                            cfg.source == MediaRecorder.AudioSource.CAMCORDER
                        )
                        val isExempt = isStandardMic || (!isBluetooth && !isWired)

                        var hasNonZero = false
                        if (!isExempt) {
                            val probeBuffer = ShortArray(256)
                            val probeStartTime = System.currentTimeMillis()
                            while (System.currentTimeMillis() - probeStartTime < 250) {
                                if (stopRequested) break
                                val read = candidate.record.read(probeBuffer, 0, probeBuffer.size)
                                if (read > 0) {
                                    for (i in 0 until read) {
                                        if (probeBuffer[i] != 0.toShort()) {
                                            hasNonZero = true
                                            break
                                        }
                                    }
                                }
                                if (hasNonZero) break
                                Thread.sleep(30)
                            }
                        }

                        if ((hasNonZero || isExempt) && !stopRequested) {
                            opened = candidate
                            usedMicFallback = cfg.preferBuiltInMic
                            Log.i(TAG, "Selected source=${cfg.source} sampleRate=${cfg.sampleRate} preferBuiltInMic=${cfg.preferBuiltInMic} (passed probe or mic fallback)")
                            break
                        } else {
                            Log.w(TAG, "Source=${cfg.source} sampleRate=${cfg.sampleRate} preferBuiltInMic=${cfg.preferBuiltInMic} failed probe (all zeros/silenced), trying next...")
                            if (candidate != firstInitializedRecord) {
                                try {
                                    candidate.record.stop()
                                    candidate.record.release()
                                } catch (_: Exception) {}
                            } else {
                                try {
                                    candidate.record.stop()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed startRecording source=${cfg.source} rate=${cfg.sampleRate}", e)
                        if (candidate != firstInitializedRecord) {
                            try {
                                candidate.record.release()
                            } catch (_: Exception) {}
                        }
                    }
                }

                if (opened == null && firstInitializedRecord != null && !stopRequested) {
                    opened = firstInitializedRecord
                    try {
                        opened.record.startRecording()
                        Log.w(TAG, "Fallback to first initialized record: source=${opened.source} sampleRate=${opened.sampleRate}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start fallback recording", e)
                        opened = null
                    }
                }

                if (firstInitializedRecord != null && firstInitializedRecord != opened) {
                    try {
                        firstInitializedRecord.record.stop()
                        firstInitializedRecord.record.release()
                    } catch (_: Exception) {}
                }

                if (opened == null) {
                    Log.w(TAG, "AudioRecord could not be initialized. Trying MediaRecorder fallback...")
                    val fallbackOk = startMediaRecorderFallback(m4aFile)
                    if (fallbackOk) {
                        isMediaRecorderActive = true
                        isRecordingActive = false
                        currentFile = m4aFile
                        try {
                            file.delete()
                        } catch (_: Exception) {}
                        return@Thread
                    } else {
                        Log.e(TAG, "MediaRecorder fallback failed as well.")
                        isRecordingActive = false
                        return@Thread
                    }
                }

                audioRecord = opened.record
                audioRecordRef = audioRecord
                val selectedSampleRate = opened.sampleRate
                val selectedBufferSize = opened.bufferSize
                activeSource = opened.source

                Log.i(
                    TAG,
                    "AudioRecord recording: source=${opened.source} sampleRate=$selectedSampleRate buffer=$selectedBufferSize micFallback=$usedMicFallback"
                )

                outStream = BufferedOutputStream(FileOutputStream(file))
                writeWavHeader(outStream, 1, selectedSampleRate, 16)

                val data = ShortArray(selectedBufferSize / 2)
                val byteBuffer = ByteArray(selectedBufferSize)
                var totalSamples = 0L
                var peakSeen = 0
                var nonZeroSamples = 0L
                var lastPeakLogMs = 0L

                while (!stopRequested) {
                    val shortsRead = audioRecord.read(data, 0, data.size)
                    if (shortsRead > 0) {
                        totalSamples += shortsRead
                        for (i in 0 until shortsRead) {
                            val value = data[i]
                            val absV = abs(value.toInt())
                            if (absV > peakSeen) peakSeen = absV
                            if (value != 0.toShort()) {
                                nonZeroSamples++
                            }
                            byteBuffer[i * 2] = (value.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
                        }
                        outStream.write(byteBuffer, 0, shortsRead * 2)

                        val now = System.currentTimeMillis()
                        if (now - lastPeakLogMs > 1000) {
                            val ratio = if (totalSamples > 0) (nonZeroSamples.toDouble() / totalSamples * 100) else 0.0
                            Log.i(
                                    TAG,
                                    "Capture progress: samples=$totalSamples, peak=$peakSeen, nonZeroRatio=${String.format(Locale.US, "%.2f", ratio)}%"
                            )
                            lastPeakLogMs = now
                        }
                    } else if (shortsRead < 0) {
                        Log.e(TAG, "AudioRecord.read error code: $shortsRead")
                        break
                    }
                }

                val finalRatio = if (totalSamples > 0) (nonZeroSamples.toDouble() / totalSamples * 100) else 0.0
                Log.i(TAG, "Recording finished: samples=$totalSamples, peak=$peakSeen, nonZeroRatio=${String.format(Locale.US, "%.2f", finalRatio)}%")
                if (peakSeen < 50) {
                    Log.e(
                        TAG,
                        "Recording looks silent (peak=$peakSeen, nonZeroRatio=${String.format(Locale.US, "%.2f", finalRatio)}%). " +
                            "Confirm speaker is on during record and RECORD_AUDIO is granted. Telephony mix is often blocked on modern OEMs."
                    )
                }

                try {
                    audioRecord.stop()
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "Recording thread error", e)
            } finally {
                try {
                    outStream?.close()
                } catch (_: Exception) {}
                try {
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecordRef = null
                isRecordingActive = false
            }
        }, "CallRecordingThread")
        recordingThread?.start()
    }

    private fun writeWavHeader(out: java.io.OutputStream, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16.toByte()
        header[20] = 1.toByte()
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        val byteRate = sampleRate * channels * bitsPerSample / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        val blockAlign = channels * bitsPerSample / 8
        header[32] = blockAlign.toByte()
        header[34] = bitsPerSample.toByte()
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        out.write(header)
    }

    private fun updateWavSizes(file: File) {
        if (!file.exists()) return
        val totalAudioLen = file.length() - 8
        val totalDataLen = file.length() - 44
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(file, "rw")
            raf.seek(4)
            raf.write((totalAudioLen and 0xff).toInt())
            raf.write(((totalAudioLen shr 8) and 0xff).toInt())
            raf.write(((totalAudioLen shr 16) and 0xff).toInt())
            raf.write(((totalAudioLen shr 24) and 0xff).toInt())
            raf.seek(40)
            raf.write((totalDataLen and 0xff).toInt())
            raf.write(((totalDataLen shr 8) and 0xff).toInt())
            raf.write(((totalDataLen shr 16) and 0xff).toInt())
            raf.write(((totalDataLen shr 24) and 0xff).toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating WAV header sizes", e)
        } finally {
            try {
                raf?.close()
            } catch (_: Exception) {}
        }
    }

    private fun autoSaveToPublicFolder(sourceFile: File, mimeType: String = "audio/mpeg") {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.SIZE, sourceFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/MxMRecordings")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    values.put(MediaStore.Audio.Media.SIZE, sourceFile.length())
                    resolver.update(uri, values, null, null)
                }
                Log.i(TAG, "Saved true recording to public Music/MxMRecordings: ${sourceFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy true recording to public storage", e)
            }
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
            android.media.AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
            else -> "OTHER_$type"
        }
    }

    private fun logDeepAudioDiagnostics() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager == null) {
                Log.i(TAG, "DEEP_LOG: AudioManager is null")
                return
            }
            
            Log.i(TAG, "DEEP_LOG: --- AUDIO DIAGNOSTICS START ---")
            Log.i(TAG, "DEEP_LOG: isWiredHeadsetOn=${audioManager.isWiredHeadsetOn}")
            Log.i(TAG, "DEEP_LOG: isBluetoothScoOn=${audioManager.isBluetoothScoOn}")
            Log.i(TAG, "DEEP_LOG: isBluetoothA2dpOn=${audioManager.isBluetoothA2dpOn}")
            Log.i(TAG, "DEEP_LOG: isSpeakerphoneOn=${audioManager.isSpeakerphoneOn}")
            Log.i(TAG, "DEEP_LOG: mode=${audioManager.mode}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val inputs = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                Log.i(TAG, "DEEP_LOG: Available Input Devices count=${inputs?.size ?: 0}")
                inputs?.forEach { dev ->
                    Log.i(TAG, "DEEP_LOG:   Device ID=${dev.id} Name=${dev.productName} Type=${getDeviceTypeName(dev.type)} isSource=${dev.isSource}")
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val configs = audioManager.activeRecordingConfigurations
                Log.i(TAG, "DEEP_LOG: Active Recording Configurations count=${configs.size}")
                configs.forEachIndexed { index, config ->
                    val sourceStr = config.clientAudioSource
                    val session = config.clientAudioSessionId
                    val isSilenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) config.isClientSilenced else "unknown"
                    val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) config.audioDevice?.productName else "unknown"
                    Log.i(TAG, "DEEP_LOG:   Config #$index: Session=$session Source=$sourceStr Device=$deviceName isSilenced=$isSilenced")
                }
            }
            Log.i(TAG, "DEEP_LOG: --- AUDIO DIAGNOSTICS END ---")
        } catch (e: Exception) {
            Log.e(TAG, "DEEP_LOG: Failed to run deep diagnostics", e)
        }
    }

    private fun startMediaRecorderFallback(m4aFile: File): Boolean {
        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            var source = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            val isBluetooth = isBluetoothHeadsetActive()
            val isWired = isWiredHeadsetActive()

            if (isBluetooth || isWired) {
                source = MediaRecorder.AudioSource.MIC
            }

            mr.setAudioSource(source)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96000)
            mr.setAudioSamplingRate(16000)
            mr.setOutputFile(m4aFile.absolutePath)

            if ((isBluetooth || isWired) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val builtinMic = getBuiltInMicDevice()
                if (builtinMic != null) {
                    try {
                        mr.setPreferredDevice(builtinMic)
                        Log.i(TAG, "MediaRecorder forced built-in mic device: ${builtinMic.id}")
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaRecorder failed to setPreferredDevice", e)
                    }
                }
            }

            mr.prepare()
            mr.start()
            mediaRecorderRef = mr
            activeSource = source
            usedMicFallback = (isBluetooth || isWired)
            Log.i(TAG, "Successfully started MediaRecorder fallback with source=$source")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MediaRecorder fallback", e)
            return false
        }
    }

    fun usingMicFallback(): Boolean = usedMicFallback

    companion object {
        private const val TAG = "CallRecorder"
        const val RECORDINGS_DIR = "recordings"

        fun listRecordings(context: Context): List<File> {
            val dir = File(context.filesDir, RECORDINGS_DIR)
            if (!dir.exists()) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && (it.extension == "mp3" || it.extension == "m4a" || it.extension == "3gp") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        fun deleteRecording(file: File): Boolean = file.delete()

        fun performAutoDeleteIfNeeded(context: Context) {
            val days = Prefs.getAutoDeleteDays(context)
            if (days <= 0) return

            val dir = File(context.filesDir, RECORDINGS_DIR)
            if (!dir.exists()) return

            val now = System.currentTimeMillis()
            val limit = days * 24L * 60 * 60 * 1000

            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isFile && (file.extension == "mp3" || file.extension == "m4a" || file.extension == "3gp")) {
                    val age = now - file.lastModified()
                    if (age > limit) {
                        file.delete()
                        Log.i(TAG, "Auto-deleted stale recording: ${file.name}")
                    }
                }
            }
        }
    }
}
