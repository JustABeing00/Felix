package com.felix.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import org.vosk.Model
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for hands-free mode.
 *
 * Cycle: offline wake-word listening ("felix", Vosk on the microphone) → short cue →
 * Google speech recognition for the command → policy-checked call → back to listening.
 * The microphone is never shared: the wake listener releases it before the speech
 * recognizer opens its own session.
 *
 * Threads: "felix-listen" reads audio and runs the wake-word model; "felix-decide" loads
 * contacts and applies the call policy; the main thread owns the speech recognizer and
 * all Android UI and notification calls.
 */
class FelixListeningService : Service() {

    companion object {
        const val ACTION_START = "com.felix.app.action.START_LISTENING"
        const val ACTION_STOP = "com.felix.app.action.STOP_LISTENING"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_LENGTH = 512
        private const val CUE_DELAY_MS = 450L
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val COOLDOWN_MS = 2_000L
        /** How many recognizer guesses to consider: the top guess plus lower-ranked alternatives. */
        private const val MAX_COMMAND_RESULTS = 5

        fun startIntent(context: Context): Intent =
            Intent(context, FelixListeningService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, FelixListeningService::class.java).setAction(ACTION_STOP)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listenExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "felix-listen") }
    private val decideExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "felix-decide") }
    private val stopRequested = AtomicBoolean(false)

    // Main-thread state.
    private var sessionStarted = false
    private var sessionId = 0L
    private var activeCommandId = -1L
    private var dispatcher: CommandDispatcher? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var commandTimeout: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Listen-thread state.
    private var model: Model? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(userRequested = true)
        } else {
            beginSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!stopRequested.get() && HandsFreeStatus.serviceRunning) {
            HandsFreeStatus.publish(
                HandsFreePhase.ERROR,
                "Android stopped hands-free listening. Tap Activate Hands-Free Mode to start again."
            )
        }
        shutdown(userRequested = false)
        super.onDestroy()
    }

    private fun beginSession() {
        if (sessionStarted) return
        FelixNotifications.ensureChannels(this)
        if (!startForegroundNow()) {
            stopSelf()
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            failWith(HandsFreePhase.MIC_UNAVAILABLE, "Microphone permission is missing. Allow it in Felix, then try again.")
            return
        }
        sessionStarted = true
        stopRequested.set(false)
        HandsFreeStatus.serviceRunning = true
        dispatcher = CommandDispatcher(TelecomCallExecutor(applicationContext))
        wakeLock = acquireWakeLock()
        report(HandsFreePhase.STARTING, "Preparing the offline wake-word model…")
        listenExecutor.execute { runListenStep { loadModelAndListen() } }
    }

    private fun startForegroundNow(): Boolean = try {
        val notification = FelixNotifications.listening(this, "Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FelixNotifications.LISTENING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(FelixNotifications.LISTENING_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        HandsFreeStatus.publish(
            HandsFreePhase.MIC_UNAVAILABLE,
            "Android wouldn't start the listening service. Open Felix and try again."
        )
        false
    }

    // ---------------- Listen thread: wake word ----------------

    private fun runListenStep(step: () -> Unit) {
        try {
            step()
        } catch (failure: HandsFreeFailure) {
            failWith(failure.phase, failure.message ?: "Felix couldn't start listening.")
        } catch (e: Throwable) {
            failWith(HandsFreePhase.ERROR, "Felix hit an unexpected problem (${e.javaClass.simpleName}).")
        }
    }

    private fun loadModelAndListen() {
        val loaded = VoskModelStore.open(applicationContext)
        model = loaded
        if (stopRequested.get()) return
        main.post {
            if (!stopRequested.get()) {
                report(HandsFreePhase.LISTENING_FOR_WAKE, "Listening for “Felix”. Say it, then pause briefly.")
            }
        }
        listenCycle()
    }

    /** Runs the microphone and wake-word model until "felix" is heard or listening stops. */
    private fun listenCycle() {
        val vosk = model ?: return
        if (stopRequested.get()) return
        val resources = ResourceGroup()
        var wakeHeard = false
        try {
            val detector = try {
                VoskWakeWordDetector(vosk)
            } catch (e: Exception) {
                throw HandsFreeFailure(
                    HandsFreePhase.SPEECH_UNAVAILABLE,
                    "The offline wake-word recognizer couldn't start."
                )
            }
            resources.add(detector)

            val record = openMicrophone()
            resources.add(object : Releasable {
                override fun release() = releaseRecord(record)
            })
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw HandsFreeFailure(
                    HandsFreePhase.MIC_UNAVAILABLE,
                    "The microphone didn't start. Another app may be using it."
                )
            }

            val frame = ShortArray(FRAME_LENGTH)
            while (readFrame(record, frame)) {
                if (detector.feed(frame)) {
                    wakeHeard = true
                    break
                }
            }
        } finally {
            resources.releaseAll()
        }
        if (wakeHeard) main.post { onWakeWord() }
    }

    private fun openMicrophone(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            throw HandsFreeFailure(HandsFreePhase.MIC_UNAVAILABLE, "This phone reported no usable microphone format.")
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_LENGTH * 2 * 4)
            )
        } catch (e: SecurityException) {
            throw HandsFreeFailure(HandsFreePhase.MIC_UNAVAILABLE, "Microphone permission is missing.")
        } catch (e: IllegalArgumentException) {
            throw HandsFreeFailure(HandsFreePhase.MIC_UNAVAILABLE, "The microphone couldn't be configured on this phone.")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw HandsFreeFailure(
                HandsFreePhase.MIC_UNAVAILABLE,
                "The microphone couldn't be opened. Another app may be using it."
            )
        }
        return record
    }

    private fun readFrame(record: AudioRecord, frame: ShortArray): Boolean {
        var filled = 0
        while (filled < FRAME_LENGTH) {
            if (stopRequested.get()) return false
            val read = record.read(frame, filled, FRAME_LENGTH - filled)
            if (read < 0) {
                if (stopRequested.get()) return false
                throw HandsFreeFailure(
                    HandsFreePhase.MIC_UNAVAILABLE,
                    "The microphone stopped delivering audio (code $read). Another app may be using it."
                )
            }
            filled += read
        }
        return true
    }

    private fun releaseRecord(record: AudioRecord) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        record.release()
    }

    private fun closeModel() {
        model?.close()
        model = null
    }

    // ---------------- Main thread: command ----------------

    private fun onWakeWord() {
        if (stopRequested.get()) return
        sessionId++
        val id = sessionId
        report(HandsFreePhase.WAKE_DETECTED, "Wake word heard. Say your command after the beep…")
        playCue()
        main.postDelayed({ startCommandRecognition(id) }, CUE_DELAY_MS)
    }

    private fun startCommandRecognition(id: Long) {
        if (stopRequested.get() || id != sessionId) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            failWith(
                HandsFreePhase.SPEECH_UNAVAILABLE,
                "Speech recognition isn't available on this phone. Install or enable the Google app."
            )
            return
        }
        activeCommandId = id
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                report(HandsFreePhase.RECOGNIZING_COMMAND, "Listening for your command…")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onCommandError(id, error)

            override fun onResults(results: Bundle?) {
                val transcripts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_COMMAND_RESULTS)
                onCommandResult(id, transcripts)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_COMMAND_RESULTS)
        }
        val timeout = Runnable { onCommandTimeout(id) }
        commandTimeout = timeout
        main.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        try {
            recognizer.startListening(intent)
        } catch (e: SecurityException) {
            onCommandError(id, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        }
    }

    private fun onCommandResult(id: Long, transcripts: List<String>) {
        if (!claimCommand(id)) return
        if (transcripts.isEmpty()) {
            finishCycle("I didn't catch that.")
            return
        }
        report(HandsFreePhase.RESOLVING_CONTACT, "Heard “${transcripts.first()}”. Resolving contact…")
        decideExecutor.execute {
            val result = try {
                decide(id, transcripts)
            } catch (e: Exception) {
                DispatchResult.NotACommand("Felix couldn't read your contacts right now. Nothing was called.")
            }
            main.post { presentResult(result) }
        }
    }

    private fun onCommandError(id: Long, error: Int) {
        if (!claimCommand(id)) return
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                failWith(
                    HandsFreePhase.MIC_UNAVAILABLE,
                    "Speech recognition has no microphone permission. Allow Microphone for Felix."
                )
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                finishCycle("I didn't hear a command.")
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                finishCycle("The speech service is unreachable. Check internet or the Google app.")
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                finishCycle("The speech recognizer is busy.")
            SpeechRecognizer.ERROR_AUDIO ->
                finishCycle("The microphone isn't available for speech right now.", HandsFreePhase.MIC_UNAVAILABLE)
            else ->
                finishCycle("Speech recognition failed (code $error).")
        }
    }

    private fun onCommandTimeout(id: Long) {
        if (!claimCommand(id)) return
        finishCycle("I didn't hear a command.")
    }

    /** Only the first callback for the active command proceeds, so a late callback can't repeat a decision. */
    private fun claimCommand(id: Long): Boolean {
        if (activeCommandId != id || stopRequested.get()) return false
        activeCommandId = -1L
        clearCommandSession()
        return true
    }

    private fun clearCommandSession() {
        commandTimeout?.let { main.removeCallbacks(it) }
        commandTimeout = null
        speechRecognizer?.let { recognizer ->
            try {
                recognizer.cancel()
            } catch (e: RuntimeException) {
                // Nothing to cancel.
            }
            recognizer.destroy()
        }
        speechRecognizer = null
    }

    /** Runs on the decide thread: contacts from the phone plus the saved preferences. */
    private fun decide(wakeId: Long, transcripts: List<String>): DispatchResult {
        val settings = FelixSettings(applicationContext)
        val contactsGranted = ContactsRepository.hasPermission(applicationContext)
        val contacts = if (contactsGranted) ContactsRepository.load(applicationContext) else emptyList()
        val request = DispatchRequest(
            commandText = transcripts.first(),
            alternatives = transcripts.drop(1),
            wakeEventId = wakeId,
            contacts = contacts,
            contactsPermissionGranted = contactsGranted,
            preferredNumbers = settings.preferredNumbers(),
            autoCallEnabled = settings.autoCallEnabled && settings.autoCallAcknowledged,
            callPermissionGranted = hasPermission(Manifest.permission.CALL_PHONE),
            soundMatching = settings.soundMatchingEnabled
        )
        return dispatcher?.dispatch(request)
            ?: DispatchResult.NotACommand("Felix isn't ready. Nothing was called.")
    }

    private fun presentResult(result: DispatchResult) {
        if (stopRequested.get()) return
        when (result) {
            is DispatchResult.NotACommand -> finishCycle(result.message)
            is DispatchResult.Blocked -> presentBlocked(result)
            is DispatchResult.CallRequested -> {
                val note = FelixLogic.heardNote(result.spokenName, result.contact.name)
                report(
                    HandsFreePhase.CALL_REQUEST_SENT,
                    "Call request sent for ${result.contact.name}$note. This doesn't mean it has connected."
                )
                resumeListening()
            }
            is DispatchResult.CallFailed -> {
                val reason = when (val outcome = result.outcome) {
                    CallOutcome.PermissionDenied -> "Phone permission is off"
                    is CallOutcome.Failed -> "the phone couldn't start the call (${outcome.reason})"
                    CallOutcome.Sent -> "the call wasn't started"
                }
                FelixNotifications.showResult(
                    this,
                    "Call ${result.contact.name}?",
                    "Tap to open the dialer.",
                    FelixLogic.telUri(result.phone)
                )
                finishCycle(
                    "$reason, so nothing was dialed. Use the notification to dial " +
                        "${result.contact.name}${FelixLogic.heardNote(result.spokenName, result.contact.name)}.",
                    HandsFreePhase.CALL_NOT_PLACED
                )
            }
        }
    }

    private fun presentBlocked(result: DispatchResult.Blocked) {
        val spoken = result.spokenName
        val name = result.contact?.name ?: spoken
        when (result.reason) {
            BlockReason.NOT_FOUND ->
                finishCycle("I couldn't find “$spoken”. Nothing was called.", HandsFreePhase.CONTACT_NOT_FOUND)
            BlockReason.AMBIGUOUS ->
                finishCycle(
                    "More than one contact matches “$spoken”. Nothing was called. Open Felix to choose.",
                    HandsFreePhase.AMBIGUOUS_CONTACT
                )
            BlockReason.NEEDS_PREFERRED_NUMBER ->
                finishCycle(
                    "$name has several numbers. Open Felix and pick the one to remember. Nothing was called.",
                    HandsFreePhase.AMBIGUOUS_CONTACT
                )
            BlockReason.NO_NUMBER, BlockReason.INVALID_NUMBER ->
                finishCycle("$name has no usable number. Nothing was called.", HandsFreePhase.CALL_NOT_PLACED)
            BlockReason.BLOCKED_NUMBER ->
                finishCycle("Felix never auto-dials emergency or short numbers.", HandsFreePhase.CALL_NOT_PLACED)
            BlockReason.AUTO_CALL_DISABLED -> {
                FelixNotifications.showResult(
                    this,
                    "Call $name?",
                    "Tap to open the dialer.",
                    result.phone?.let { FelixLogic.telUri(it) }
                )
                finishCycle(
                    "Found $name${FelixLogic.heardNote(spoken, name)}. " +
                        "Automatic calling is off, so use the notification to dial.",
                    HandsFreePhase.CALL_NOT_PLACED
                )
            }
            BlockReason.PERMISSION_MISSING -> {
                FelixNotifications.showResult(
                    this,
                    "Call $name?",
                    "Tap to open the dialer.",
                    result.phone?.let { FelixLogic.telUri(it) }
                )
                finishCycle(
                    "Phone permission is missing${FelixLogic.heardNote(spoken, name)}. " +
                        "Allow it for Felix. Nothing was called.",
                    HandsFreePhase.CALL_NOT_PLACED
                )
            }
            BlockReason.CONTACTS_PERMISSION_MISSING ->
                finishCycle("Contacts permission is missing, so I can't look up “$spoken”.", HandsFreePhase.CONTACT_NOT_FOUND)
            BlockReason.NO_WAKE_EVENT, BlockReason.INCOMPLETE_COMMAND, BlockReason.ALREADY_ATTEMPTED ->
                finishCycle("Nothing was called.")
        }
    }

    private fun finishCycle(
        message: String,
        phase: HandsFreePhase = HandsFreePhase.COMMAND_NOT_UNDERSTOOD
    ) {
        report(phase, "$message Still listening for “Felix”.")
        resumeListening()
    }

    private fun resumeListening() {
        if (stopRequested.get()) return
        main.postDelayed({ scheduleListen() }, COOLDOWN_MS)
    }

    private fun scheduleListen() {
        if (stopRequested.get()) return
        listenExecutor.execute { runListenStep { listenCycle() } }
    }

    // ---------------- Shared helpers ----------------

    private fun report(phase: HandsFreePhase, message: String) {
        HandsFreeStatus.publish(phase, message)
        if (!stopRequested.get()) FelixNotifications.updateListening(this, message)
    }

    /** Ends the session and shows the failure. The message stays visible in the app. */
    private fun failWith(phase: HandsFreePhase, message: String) {
        main.post {
            if (stopRequested.get()) return@post
            report(phase, message)
            shutdown(userRequested = false)
        }
    }

    private fun shutdown(userRequested: Boolean) {
        if (stopRequested.getAndSet(true)) return
        clearCommandSession()
        activeCommandId = -1L
        releaseWakeLock()
        HandsFreeStatus.serviceRunning = false
        sessionStarted = false
        if (userRequested) HandsFreeStatus.publish(HandsFreePhase.STOPPED, "Listening stopped.")
        // Queued behind any in-flight wake-word cycle, which exits quickly once stopRequested is set.
        if (!listenExecutor.isShutdown) {
            listenExecutor.execute { closeModel() }
            listenExecutor.shutdown()
        }
        decideExecutor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun playCue() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            main.postDelayed({ tone.release() }, 400L)
        } catch (e: RuntimeException) {
            // No tone on this device. The status text still prompts the user.
        }
    }

    /**
     * Held only while listening and released on every stop path. Deliberately no timeout: a timed lock
     * would silently stop listening mid-session. The OS also frees it if the process dies.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "felix:hands-free")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    } catch (e: RuntimeException) {
        null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
