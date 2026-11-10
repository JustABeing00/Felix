package com.felix.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * Felix main screen.
 * - One-tap voice command: Google speech → parse → match → ACTION_CALL while the app is visible.
 * - Hands-free mode: a foreground service listens for "Felix" offline, then takes one command.
 * Both paths share the safety rules: ambiguous matches are never guessed and calls are never retried.
 */
class MainActivity : AppCompatActivity() {

    companion object {
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
        contactInfoText.text = if (contacts.isEmpty()) {
            "No contacts with phone numbers available. Grant contacts access."
        } else {
            "${contacts.size} contact(s) ready."
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
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> "Recognition service failed (network). Try again."
                        else -> "Recognition failed. Try again."
                    }
                )
            }

            override fun onResults(results: Bundle?) {
                stopRecognizer()
                setListeningUi(false)
                val transcript = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (transcript.isBlank()) {
                    setStatus("I didn't catch that. Try again.")
                    return
                }
                handleCommand(transcript)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
        stopButton.visibility = if (on) View.VISIBLE else View.GONE
    }

    // ---------------- Command → contact → call ----------------

    private fun handleCommand(rawText: String) {
        heardText.text = "I heard: “${rawText.trim()}”"
        heardText.visibility = View.VISIBLE
        when (val parsed = FelixLogic.parseCommand(rawText)) {
            is ParseResult.Err -> setStatus(parsed.message)
            is ParseResult.Ok -> when (val m = FelixLogic.matchContact(
                parsed.name,
                contacts,
                settings.preferredNumbers(),
                settings.soundMatchingEnabled
            )) {
                is MatchResult.NotFound -> {
                    setStatus("I couldn't find that contact.")
                    showMessage(
                        "No match for “${parsed.name}”.",
                        "Check the name and try again."
                    )
                }
                is MatchResult.Ambiguous -> {
                    setStatus("Multiple contacts matched. Please choose one.")
                    showResultTitle("Which “${parsed.name}” did you mean?")
                    m.matches.forEach { c -> addChoiceButton(c.name) { resolveContact(c) } }
                    showResultNote("I never guess when several contacts match.")
                }
                is MatchResult.NoNumber -> {
                    setStatus("${m.contact.name} has no valid phone number.")
                    showMessage(
                        "${m.contact.name} has no usable number.",
                        "Fix the number in your contacts and try again."
                    )
                }
                is MatchResult.NeedChoice -> {
                    setStatus("Found ${m.contact.name}${FelixLogic.heardNote(parsed.name, m.contact.name)}. Which number?")
                    showResultTitle("Found ${m.contact.name}.")
                    showResultNote("Pick one. Felix remembers your choice for this contact.")
                    m.phones.forEach { p -> addChoiceButton(p) { rememberAndCall(m.contact, p) } }
                }
                is MatchResult.Found -> {
                    val note = FelixLogic.heardNote(parsed.name, m.contact.name)
                    setStatus("Found ${m.contact.name}$note.")
                    requestCall(m.contact, m.phone)
                }
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
                showResultTitle("Found ${contact.name}.")
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
            showMessage("Calling ${contact.name}.", phone)
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
        showResultTitle("Found ${contact.name}.")
        showResultNote(phone)
        val dial = Button(this).apply {
            text = "Call ${contact.name} (open dialer)"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(uri)))
                } catch (e: ActivityNotFoundException) {
                    setStatus("No calling app found on this device.")
                }
            }
        }
        resultBox.addView(dial)
        addAskAgainButton()
        resultBox.visibility = View.VISIBLE
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
        AlertDialog.Builder(this)
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
        handsFreeToggleButton.text =
            if (HandsFreeStatus.serviceRunning) "Stop Listening" else "Activate Hands-Free Mode"
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle("Hands-free troubleshooting")
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    // ---------------- Small UI helpers ----------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun clearResult() {
        resultBox.removeAllViews()
        resultBox.visibility = View.GONE
    }

    private fun showResultTitle(title: String) {
        clearResult()
        val t = TextView(this).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        resultBox.addView(t)
        resultBox.visibility = View.VISIBLE
    }

    private fun showResultNote(note: String) {
        val n = TextView(this).apply { text = note }
        resultBox.addView(n)
    }

    private fun addChoiceButton(label: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        resultBox.addView(b)
    }

    private fun addAskAgainButton() {
        val again = Button(this).apply {
            text = "Activate Felix again"
            setOnClickListener { startListening() }
        }
        resultBox.addView(again)
    }

    private fun showMessage(title: String, sub: String) {
        showResultTitle(title)
        showResultNote(sub)
        addAskAgainButton()
    }
}
