package com.felix.app

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Felix main screen.
 * - One-tap voice command: Google speech → parse → match → ACTION_CALL while the app is visible.
 * - Hands-free mode: a foreground service listens for "Felix" offline, then takes one command.
 * Both paths share the safety rules: ambiguous matches are never guessed and calls are never retried.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** How many recognizer guesses to consider: the top guess plus lower-ranked alternatives. */
        private const val MAX_VOICE_RESULTS = 5
        private const val AUTO_CALL_ACKNOWLEDGEMENT =
            "When hands-free mode is on, Felix can start a phone call by itself after you say “Felix” " +
                "and then a call command for a contact that matches exactly one saved number.\n\n" +
                "Felix never guesses between people, never calls emergency numbers and never retries a call. " +
                "Your phone may still show its own call screen, and Felix can't tell whether the other person answered.\n\n" +
                "You can switch this off at any time."
    }

    // One-tap voice views.
    private lateinit var statusText: TextView
    private lateinit var heardText: TextView
    private lateinit var contactInfoText: TextView
    private lateinit var activateButton: Button
    private lateinit var stopButton: Button
    private lateinit var resultBox: LinearLayout
    private lateinit var typeCommandText: EditText
    private lateinit var typeGoButton: Button

    // Hands-free views.
    private lateinit var handsFreeStatusText: TextView
    private lateinit var handsFreeToggleButton: Button
    private lateinit var autoCallSwitch: SwitchCompat
    private lateinit var autoCallHint: TextView
    private lateinit var soundMatchSwitch: SwitchCompat
    private lateinit var soundMatchHint: TextView
    private lateinit var handsFreeSetupText: TextView
    private lateinit var troubleshootButton: Button

    // Premium visual-only views (decorative, no logic change).
    private lateinit var voiceStateDot: View
    private lateinit var voiceStateLabel: TextView
    private lateinit var voiceHintText: TextView
    private lateinit var voiceHaloOuter: View
    private lateinit var voiceHaloInner: View
    private lateinit var voiceOrbCore: View
    private lateinit var voiceOrbIcon: ImageView
    private lateinit var handsFreeDot: View
    private lateinit var contactDot: View

    private var pulseSet: AnimatorSet? = null

    private lateinit var settings: FelixSettings
    private var contacts: List<FelixContact> = emptyList()
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pendingCall: Pair<FelixContact, String>? = null
    private var suppressSwitchEvents = false
    private var suppressSoundEvents = false

    private val handsFreeStatusListener: (HandsFreeUiState) -> Unit = { state -> renderHandsFree(state) }

    /** Microphone and contacts are needed before anything works, so ask up front. */
    private val setupPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadContacts() }

    /** CALL_PHONE is sensitive: the one-tap path asks only when a call is about to happen. */
    private val callPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val call = pendingCall
        pendingCall = null
        if (call == null) return@registerForActivityResult
        if (granted) {
            placeCallDirect(call.first, call.second)
        } else {
            showDialFallback(call.first, call.second)
            setStatus("Call permission denied. Use the dial button below instead.")
        }
    }

    private val handsFreePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startHandsFreeService()
        } else {
            HandsFreeStatus.publish(
                HandsFreePhase.MIC_UNAVAILABLE,
                "Microphone permission is needed for hands-free mode."
            )
        }
    }

    private val autoCallPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        settings.autoCallEnabled = granted
        setSwitchQuietly(granted)
        refreshAutoCallHint()
        if (!granted) setStatus("Phone permission was refused, so automatic calling stays off.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = FelixSettings(this)
        bindViews()
        wireListeners()
        FelixNotifications.ensureChannels(this)
        setupPermissions.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
        )
    }

    override fun onStart() {
        super.onStart()
        HandsFreeStatus.addListener(handsFreeStatusListener)
        renderHandsFree(HandsFreeStatus.current)
        refreshSetupText()
        setSwitchQuietly(settings.autoCallEnabled)
        refreshAutoCallHint()
        setSoundSwitchQuietly(settings.soundMatchingEnabled)
        refreshSoundHint()
    }

    override fun onStop() {
        HandsFreeStatus.removeListener(handsFreeStatusListener)
        super.onStop()
    }

    override fun onDestroy() {
        stopPulse()
        stopRecognizer()
        super.onDestroy()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        heardText = findViewById(R.id.heardText)
        contactInfoText = findViewById(R.id.contactInfoText)
        activateButton = findViewById(R.id.activateButton)
        stopButton = findViewById(R.id.stopButton)
        resultBox = findViewById(R.id.resultBox)
        typeCommandText = findViewById(R.id.typeCommandText)
        typeGoButton = findViewById(R.id.typeGoButton)
        handsFreeStatusText = findViewById(R.id.handsFreeStatusText)
        handsFreeToggleButton = findViewById(R.id.handsFreeToggleButton)
        autoCallSwitch = findViewById(R.id.autoCallSwitch)
        autoCallHint = findViewById(R.id.autoCallHint)
        soundMatchSwitch = findViewById(R.id.soundMatchSwitch)
        soundMatchHint = findViewById(R.id.soundMatchHint)
        handsFreeSetupText = findViewById(R.id.handsFreeSetupText)
        troubleshootButton = findViewById(R.id.troubleshootButton)
        voiceStateDot = findViewById(R.id.voiceStateDot)
        voiceStateLabel = findViewById(R.id.voiceStateLabel)
        voiceHintText = findViewById(R.id.voiceHintText)
        voiceHaloOuter = findViewById(R.id.voiceHaloOuter)
        voiceHaloInner = findViewById(R.id.voiceHaloInner)
        voiceOrbCore = findViewById(R.id.voiceOrbCore)
        voiceOrbIcon = findViewById(R.id.voiceOrbIcon)
        handsFreeDot = findViewById(R.id.handsFreeDot)
        contactDot = findViewById(R.id.contactDot)
    }

    private fun wireListeners() {
        activateButton.setOnClickListener { startListening() }
        stopButton.setOnClickListener { stopListening(userInitiated = true) }
        typeGoButton.setOnClickListener {
            val typed = typeCommandText.text.toString()
            if (typed.isBlank()) {
                setStatus("Type a command first, e.g. “Call Yash”.")
            } else {
                handleCommand(typed)
            }
        }
        typeCommandText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                typeGoButton.performClick()
                true
            } else {
                false
            }
        }
        handsFreeToggleButton.setOnClickListener { onHandsFreeToggle() }
        troubleshootButton.setOnClickListener { showTroubleshooting() }
        autoCallSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSwitchEvents) onAutoCallSwitched(checked)
        }
        soundMatchSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSoundEvents) {
                settings.soundMatchingEnabled = checked
                refreshSoundHint()
            }
        }
    }

    // ---------------- Contacts ----------------

    private fun loadContacts() {
        contacts = ContactsRepository.load(this)
        if (contacts.isEmpty()) {
            contactInfoText.text = "No contacts"
            contactDot.setBackgroundResource(R.drawable.dot_warning)
        } else {
            contactInfoText.text = if (contacts.size == 1) "1 ready" else "${contacts.size} ready"
            contactDot.setBackgroundResource(R.drawable.dot_success)
        }
        setStatus(
            if (contacts.isEmpty()) "No contacts available. Grant access, then try again."
            else "Ready when you are."
        )
    }

    // ---------------- One-tap voice (MVP) ----------------

    private fun startListening() {
        clearResult()
        heardText.visibility = View.GONE
        if (HandsFreeStatus.serviceRunning) {
            setStatus("Hands-free mode is listening. Stop it first to use one-tap voice.")
            return
        }
        if (contacts.isEmpty()) {
            setStatus("No contacts available. Grant contacts access first.")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus("Speech recognition isn't available on this device. Type your command below instead.")
            return
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            setStatus("Microphone access was denied.")
            return
        }
        stopRecognizer()
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setListeningUi(true)
                setStatus("Listening…")
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val wasListening = listening
                stopRecognizer()
                setListeningUi(false)
                if (!wasListening) return
                setStatus(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Try again."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access was denied."
                        SpeechRecognizer.ERROR_AUDIO -> "No microphone found."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Stop hands-free mode first, then try again."
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> "Recognition service failed (network). Try again."
                        12, 13 -> "Speech language (en-IN) isn't available on this device. Check the Google app's voice settings."
                        else -> "Recognition failed (code $error). Try again."
                    }
                )
            }

            override fun onResults(results: Bundle?) {
                stopRecognizer()
                setListeningUi(false)
                val transcripts = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .take(MAX_VOICE_RESULTS)
                if (transcripts.isEmpty()) {
                    setStatus("I didn't catch that. Try again.")
                    return
                }
                handleVoiceCommand(transcripts)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_VOICE_RESULTS)
        }
        try {
            sr.startListening(intent)
            setListeningUi(true)
            setStatus("Listening…")
        } catch (e: SecurityException) {
            stopRecognizer()
            setListeningUi(false)
            setStatus("Microphone access was denied.")
        }
    }

    private fun stopListening(userInitiated: Boolean) {
        stopRecognizer()
        setListeningUi(false)
        if (userInitiated) setStatus("Stopped. Tap Activate Felix to try again.")
    }

    private fun stopRecognizer() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun setListeningUi(on: Boolean) {
        listening = on
        activateButton.isEnabled = !on
        activateButton.text = if (on) "Listening…" else "Activate Felix"
        (activateButton as? MaterialButton)?.apply {
            if (on) {
                setIconResource(R.drawable.ic_mic_dark)
            } else {
                setIconResource(R.drawable.ic_mic_dark)
            }
            alpha = if (on) 0.65f else 1f
        }
        stopButton.visibility = if (on) View.VISIBLE else View.GONE
        // Voice orb + state chip — visual only.
        if (on) {
            voiceStateLabel.text = "Listening"
            voiceStateDot.setBackgroundResource(R.drawable.dot_accent)
            voiceHintText.text = "Speak now — “Call …”"
            voiceOrbIcon.setImageResource(R.drawable.ic_mic)
            voiceOrbCore.setBackgroundResource(R.drawable.bg_orb_button)
            voiceOrbIcon.setColorFilter(ContextCompat.getColor(this, R.color.felix_on_accent))
            startPulse()
            stopButton.announceForAccessibility("Listening. Tap Stop listening to cancel.")
        } else {
            voiceStateLabel.text = "Ready"
            voiceStateDot.setBackgroundResource(R.drawable.dot_success)
            voiceHintText.text = "Tap Activate Felix, then say “Call …”"
            voiceOrbIcon.setImageResource(R.drawable.ic_mic)
            voiceOrbCore.setBackgroundResource(R.drawable.bg_orb_core)
            voiceOrbIcon.setColorFilter(ContextCompat.getColor(this, R.color.felix_text_primary))
            stopPulse()
        }
    }

    // ---------------- Command → contact → call ----------------

    private fun handleCommand(rawText: String) {
        val trimmed = rawText.trim()
        when (val parsed = FelixLogic.parseCommand(trimmed)) {
            is ParseResult.Err -> {
                heardText.text = "I heard: “$trimmed”"
                heardText.visibility = View.VISIBLE
                setStatus(parsed.message)
                showMessage(
                    "Couldn't understand that.",
                    parsed.message,
                    ResultKind.ERROR
                )
            }
            is ParseResult.Ok -> presentMatch(
                trimmed,
                parsed.name,
                FelixLogic.matchContact(
                    parsed.name,
                    contacts,
                    settings.preferredNumbers(),
                    settings.soundMatchingEnabled
                )
            )
        }
    }

    /** Voice path: tries the recognizer's ranked guesses in order, acting on the first that matches. */
    private fun handleVoiceCommand(transcripts: List<String>) {
        val selected = FelixLogic.selectCommand(
            transcripts,
            contacts,
            settings.preferredNumbers(),
            settings.soundMatchingEnabled
        )
        if (selected == null) {
            handleCommand(transcripts.first())
            return
        }
        presentMatch(selected.transcript, selected.name, selected.match)
    }

    private fun presentMatch(spokenTranscript: String, parsedName: String, m: MatchResult) {
        heardText.text = "I heard: “$spokenTranscript”"
        heardText.visibility = View.VISIBLE
        when (m) {
            is MatchResult.NotFound -> {
                setStatus("I couldn't find that contact.")
                showMessage(
                    "No match for “${parsedName}”.",
                    "Check the name and try again.",
                    ResultKind.ERROR
                )
            }
            is MatchResult.Ambiguous -> {
                setStatus("Multiple contacts matched. Please choose one.")
                showResultTitle("Which “${parsedName}” did you mean?", ResultKind.CHOICE)
                m.matches.forEach { c -> addChoiceButton(c.name) { resolveContact(c) } }
                showResultNote("I never guess when several contacts match.")
            }
            is MatchResult.NoNumber -> {
                setStatus("${m.contact.name} has no valid phone number.")
                showMessage(
                    "${m.contact.name} has no usable number.",
                    "Fix the number in your contacts and try again.",
                    ResultKind.ERROR
                )
            }
            is MatchResult.NeedChoice -> {
                setStatus("Found ${m.contact.name}${FelixLogic.heardNote(parsedName, m.contact.name)}. Which number?")
                showResultTitle("Found ${m.contact.name}.", ResultKind.CHOICE)
                showResultNote("Pick one. Felix remembers your choice for this contact.")
                m.phones.forEach { p -> addChoiceButton(p) { rememberAndCall(m.contact, p) } }
            }
            is MatchResult.Found -> {
                val note = FelixLogic.heardNote(parsedName, m.contact.name)
                setStatus("Found ${m.contact.name}$note.")
                requestCall(m.contact, m.phone)
            }
        }
    }

    private fun resolveContact(contact: FelixContact) {
        val phones = contact.phones.filter { FelixLogic.telUri(it) != null }
        when {
            phones.isEmpty() -> setStatus("${contact.name} has no valid phone number.")
            phones.size == 1 -> requestCall(contact, phones[0])
            else -> {
                setStatus("Found ${contact.name}. Which number?")
                showResultTitle("Found ${contact.name}.", ResultKind.CHOICE)
                showResultNote("Pick one. Felix remembers your choice for this contact.")
                phones.forEach { p -> addChoiceButton(p) { rememberAndCall(contact, p) } }
            }
        }
    }

    private fun rememberAndCall(contact: FelixContact, phone: String) {
        settings.setPreferredNumber(contact.id, phone)
        requestCall(contact, phone)
    }

    /** Zero-tap path when allowed; permission prompt (one tap) only if needed. */
    private fun requestCall(contact: FelixContact, phone: String) {
        if (FelixLogic.telUri(phone) == null) {
            setStatus("That number isn't valid, so I can't call it.")
            return
        }
        if (hasPermission(Manifest.permission.CALL_PHONE)) {
            placeCallDirect(contact, phone)
        } else {
            pendingCall = contact to phone
            setStatus("Found ${contact.name}. Need phone permission to dial…")
            callPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun placeCallDirect(contact: FelixContact, phone: String) {
        val uri = FelixLogic.telUri(phone) ?: run {
            setStatus("That number isn't valid, so I can't call it.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse(uri)))
            setStatus("Calling ${contact.name}…")
            showMessage("Calling ${contact.name}.", phone, ResultKind.SUCCESS)
        } catch (e: SecurityException) {
            showDialFallback(contact, phone)
            setStatus("Couldn't place the call directly. Use the dial button below.")
        } catch (e: ActivityNotFoundException) {
            setStatus("No calling app found on this device.")
        }
    }

    /** Fallback that needs no CALL_PHONE permission: opens the dialer pre-filled. */
    private fun showDialFallback(contact: FelixContact, phone: String) {
        val uri = FelixLogic.telUri(phone) ?: return
        showResultTitle("Found ${contact.name}.", ResultKind.NEUTRAL)
        showResultNote(phone)
        val dial = styledButton(
            text = "Call ${contact.name} (open dialer)",
            kind = ResultKind.NEUTRAL,
            iconRes = R.drawable.ic_call
        ) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri)))
            } catch (e: ActivityNotFoundException) {
                setStatus("No calling app found on this device.")
            }
        }
        resultBox.addView(dial)
        addAskAgainButton()
        resultBox.visibility = View.VISIBLE
        animateResultIn()
    }

    // ---------------- Hands-free mode ----------------

    private fun onHandsFreeToggle() {
        if (HandsFreeStatus.serviceRunning) {
            startService(FelixListeningService.stopIntent(this))
            return
        }
        val check = HandsFreeSetup.check(this)
        if (!check.ready) {
            refreshSetupText()
            showSetupProblems(check.problems())
            return
        }
        stopRecognizer()
        setListeningUi(false)
        if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
            startHandsFreeService()
        } else {
            handsFreePermissions.launch(handsFreePermissionList())
        }
    }

    private fun handsFreePermissionList(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }

    private fun startHandsFreeService() {
        ContextCompat.startForegroundService(this, FelixListeningService.startIntent(this))
    }

    private fun onAutoCallSwitched(checked: Boolean) {
        if (!checked) {
            settings.autoCallEnabled = false
            refreshAutoCallHint()
            return
        }
        if (settings.autoCallAcknowledged) {
            requestCallPermissionForAutoCall()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow automatic calling?")
            .setMessage(AUTO_CALL_ACKNOWLEDGEMENT)
            .setPositiveButton("I understand") { _, _ ->
                settings.autoCallAcknowledged = true
                requestCallPermissionForAutoCall()
            }
            .setNegativeButton("Cancel") { _, _ -> setSwitchQuietly(false) }
            .setOnCancelListener { setSwitchQuietly(false) }
            .show()
    }

    private fun requestCallPermissionForAutoCall() {
        if (hasPermission(Manifest.permission.CALL_PHONE)) {
            settings.autoCallEnabled = true
            refreshAutoCallHint()
        } else {
            autoCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun setSwitchQuietly(checked: Boolean) {
        suppressSwitchEvents = true
        autoCallSwitch.isChecked = checked
        suppressSwitchEvents = false
    }

    private fun setSoundSwitchQuietly(checked: Boolean) {
        suppressSoundEvents = true
        soundMatchSwitch.isChecked = checked
        suppressSoundEvents = false
    }

    private fun refreshSoundHint() {
        soundMatchHint.text = if (settings.soundMatchingEnabled) {
            "On: names that sound alike count as the same contact, e.g. Satwinder finds Satvinder. " +
                "Two saved contacts that sound alike still ask you to choose."
        } else {
            "Off: only exact name matches count."
        }
    }

    private fun refreshAutoCallHint() {
        autoCallHint.text = if (settings.autoCallEnabled) {
            "On: after “Felix” and a call command for one matching contact with one number, Felix places the call itself."
        } else {
            "Off: Felix shows the contact and a notification with a dial button. It never calls by itself."
        }
    }

    private fun renderHandsFree(state: HandsFreeUiState) {
        handsFreeStatusText.text = state.message
        val running = HandsFreeStatus.serviceRunning
        handsFreeToggleButton.text =
            if (running) "Stop Listening" else "Activate Hands-Free Mode"
        (handsFreeToggleButton as? MaterialButton)?.apply {
            if (running) {
                setIconResource(R.drawable.ic_stop)
                iconTint = ContextCompat.getColorStateList(context, R.color.felix_error)
            } else {
                setIconResource(R.drawable.ic_handsfree)
                iconTint = ContextCompat.getColorStateList(context, R.color.felix_accent)
            }
        }
        handsFreeDot.setBackgroundResource(
            if (running) R.drawable.dot_success else R.drawable.dot_muted
        )
        if (running) {
            handsFreeToggleButton.announceForAccessibility("Hands-free listening is on")
        }
    }

    private fun refreshSetupText() {
        val report = HandsFreeSetup.check(this)
        handsFreeSetupText.text = if (report.ready) {
            "Setup OK: the offline wake-word model is bundled and speech recognition is available."
        } else {
            report.problems().joinToString("\n\n")
        }
    }

    private fun showSetupProblems(problems: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hands-free isn't ready yet")
            .setMessage(problems.joinToString("\n\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showTroubleshooting() {
        val check = HandsFreeSetup.check(this)
        val lines = mutableListOf(
            "Hands-free state: ${HandsFreeStatus.current.phase.name}",
            "Last message: ${HandsFreeStatus.current.message}",
            "Service running: ${yesNo(HandsFreeStatus.serviceRunning)}",
            "Microphone permission: ${yesNo(hasPermission(Manifest.permission.RECORD_AUDIO))}",
            "Contacts permission: ${yesNo(hasPermission(Manifest.permission.READ_CONTACTS))}",
            "Phone permission (automatic calls): ${yesNo(hasPermission(Manifest.permission.CALL_PHONE))}",
            "Automatic calling: ${if (settings.autoCallEnabled) "on" else "off"}",
            "Offline speech model bundled: ${yesNo(check.speechModelPresent)}",
            "Speech recognition available: ${yesNo(check.commandRecognizerAvailable)}"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lines.add("Notification permission: ${yesNo(hasPermission(Manifest.permission.POST_NOTIFICATIONS))}")
        }
        lines.add("")
        lines.add(
            "If listening stops by itself: set Felix to unrestricted battery use " +
                "(Settings > Apps > Felix > Battery), and allow auto-start or background activity in your " +
                "phone's battery or app-management settings. On ColorOS (Oppo A54) these menus have different names."
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Hands-free troubleshooting")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    // ---------------- Small UI helpers (styled, same behaviour) ----------------

    private enum class ResultKind { SUCCESS, CHOICE, ERROR, NEUTRAL }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun clearResult() {
        resultBox.removeAllViews()
        resultBox.visibility = View.GONE
        resultBox.background = null
        resultBox.setPadding(0, 0, 0, 0)
    }

    private fun styleResultBox(kind: ResultKind) {
        val bg = when (kind) {
            ResultKind.SUCCESS -> R.drawable.bg_result_success
            ResultKind.CHOICE -> R.drawable.bg_result_choice
            ResultKind.ERROR -> R.drawable.bg_result_error
            ResultKind.NEUTRAL -> R.drawable.bg_result_neutral
        }
        resultBox.setBackgroundResource(bg)
        val p = dp(14)
        resultBox.setPadding(p, p, p, p)
        val lp = resultBox.layoutParams as? LinearLayout.LayoutParams
        if (lp != null && lp.topMargin != dp(12)) {
            lp.topMargin = dp(12)
            resultBox.layoutParams = lp
        }
        resultBox.visibility = View.VISIBLE
        animateResultIn()
    }

    private fun showResultTitle(title: String, kind: ResultKind = ResultKind.NEUTRAL) {
        clearResult()
        styleResultBox(kind)
        val t = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.felix_text_primary))
            setLineSpacing(0f, 1.3f)
        }
        resultBox.addView(t)
        resultBox.visibility = View.VISIBLE
    }

    private fun showResultNote(note: String) {
        if (resultBox.visibility != View.VISIBLE) styleResultBox(ResultKind.NEUTRAL)
        val n = TextView(this).apply {
            text = note
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(context, R.color.felix_text_secondary))
            setLineSpacing(0f, 1.4f)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        resultBox.addView(n)
    }

    private fun addChoiceButton(label: String, onClick: () -> Unit) {
        if (resultBox.visibility != View.VISIBLE) styleResultBox(ResultKind.CHOICE)
        val isPhone = label.any { it.isDigit() }
        val b = styledButton(
            text = label,
            kind = ResultKind.CHOICE,
            iconRes = if (isPhone) R.drawable.ic_call else R.drawable.ic_person,
            onClick = onClick
        )
        resultBox.addView(b)
    }

    private fun addAskAgainButton() {
        val again = styledButton(
            text = "Activate Felix again",
            kind = ResultKind.NEUTRAL,
            iconRes = R.drawable.ic_mic,
            secondary = true,
            onClick = { startListening() }
        )
        resultBox.addView(again)
    }

    private fun styledButton(
        text: String,
        kind: ResultKind,
        iconRes: Int? = null,
        secondary: Boolean = false,
        onClick: () -> Unit
    ): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            minimumHeight = dp(52)
            minHeight = dp(52)
            cornerRadius = dp(14)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            if (iconRes != null) {
                setIconResource(iconRes)
                iconSize = dp(20)
            } else {
                icon = null
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
            if (secondary) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.felix_surface_elevated)
                strokeColor = ContextCompat.getColorStateList(context, R.color.felix_border)
                setTextColor(ContextCompat.getColor(context, R.color.felix_text_primary))
                iconTint = ContextCompat.getColorStateList(context, R.color.felix_accent)
                rippleColor = ContextCompat.getColorStateList(context, R.color.felix_ripple)
            } else if (kind == ResultKind.SUCCESS) {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.felix_accent)
                strokeWidth = 0
                setTextColor(ContextCompat.getColor(context, R.color.felix_on_accent))
                iconTint = ContextCompat.getColorStateList(context, R.color.felix_on_accent)
            } else {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.felix_surface_elevated)
                strokeColor = ContextCompat.getColorStateList(context, R.color.felix_border)
                strokeWidth = dp(1)
                setTextColor(ContextCompat.getColor(context, R.color.felix_text_primary))
                iconTint = ContextCompat.getColorStateList(context, R.color.felix_accent)
                rippleColor = ContextCompat.getColorStateList(context, R.color.felix_ripple)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showMessage(title: String, sub: String, kind: ResultKind = ResultKind.NEUTRAL) {
        showResultTitle(title, kind)
        showResultNote(sub)
        addAskAgainButton()
    }

    // ---------------- Visual polish: motion (lightweight, skips when reduced) ----------------

    private fun animatorsEnabled(): Boolean {
        return try {
            ValueAnimator.areAnimatorsEnabled()
        } catch (_: Throwable) {
            true
        }
    }

    private fun startPulse() {
        stopPulse()
        if (!animatorsEnabled()) return
        voiceHaloOuter.visibility = View.VISIBLE
        voiceHaloInner.visibility = View.VISIBLE
        voiceHaloOuter.alpha = 0.9f
        voiceHaloInner.alpha = 0.6f
        voiceHaloOuter.scaleX = 1f
        voiceHaloOuter.scaleY = 1f
        voiceHaloInner.scaleX = 1f
        voiceHaloInner.scaleY = 1f

        val outerScaleX = ObjectAnimator.ofFloat(voiceHaloOuter, View.SCALE_X, 1f, 1.18f)
        val outerScaleY = ObjectAnimator.ofFloat(voiceHaloOuter, View.SCALE_Y, 1f, 1.18f)
        val outerFade = ObjectAnimator.ofFloat(voiceHaloOuter, View.ALPHA, 0.9f, 0.15f)
        outerScaleX.duration = 1800; outerScaleY.duration = 1800; outerFade.duration = 1800
        outerScaleX.repeatCount = ValueAnimator.INFINITE
        outerScaleY.repeatCount = ValueAnimator.INFINITE
        outerFade.repeatCount = ValueAnimator.INFINITE

        val innerScaleX = ObjectAnimator.ofFloat(voiceHaloInner, View.SCALE_X, 1f, 1.14f)
        val innerScaleY = ObjectAnimator.ofFloat(voiceHaloInner, View.SCALE_Y, 1f, 1.14f)
        val innerFade = ObjectAnimator.ofFloat(voiceHaloInner, View.ALPHA, 0.6f, 0.1f)
        innerScaleX.duration = 1800; innerScaleY.duration = 1800; innerFade.duration = 1800
        innerScaleX.repeatCount = ValueAnimator.INFINITE
        innerScaleY.repeatCount = ValueAnimator.INFINITE
        innerFade.repeatCount = ValueAnimator.INFINITE
        innerScaleX.startDelay = 450; innerScaleY.startDelay = 450; innerFade.startDelay = 450

        val coreBreath = ObjectAnimator.ofFloat(voiceOrbCore, View.SCALE_X, 1f, 1.05f)
        val coreBreathY = ObjectAnimator.ofFloat(voiceOrbCore, View.SCALE_Y, 1f, 1.05f)
        coreBreath.duration = 1200; coreBreathY.duration = 1200
        coreBreath.repeatCount = ValueAnimator.INFINITE
        coreBreath.repeatMode = ValueAnimator.REVERSE
        coreBreathY.repeatCount = ValueAnimator.INFINITE
        coreBreathY.repeatMode = ValueAnimator.REVERSE

        pulseSet = AnimatorSet().apply {
            playTogether(
                outerScaleX, outerScaleY, outerFade,
                innerScaleX, innerScaleY, innerFade,
                coreBreath, coreBreathY
            )
            start()
        }
    }

    private fun stopPulse() {
        pulseSet?.cancel()
        pulseSet = null
        if (::voiceHaloOuter.isInitialized) {
            voiceHaloOuter.scaleX = 1f
            voiceHaloOuter.scaleY = 1f
            voiceHaloOuter.alpha = 1f
            voiceHaloInner.scaleX = 1f
            voiceHaloInner.scaleY = 1f
            voiceHaloInner.alpha = 0.6f
            voiceOrbCore.scaleX = 1f
            voiceOrbCore.scaleY = 1f
        }
    }

    private fun animateResultIn() {
        if (!animatorsEnabled()) return
        resultBox.alpha = 0f
        resultBox.translationY = dp(10).toFloat()
        resultBox.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
