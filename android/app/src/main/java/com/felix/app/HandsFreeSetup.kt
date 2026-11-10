package com.felix.app

import android.content.Context
import android.speech.SpeechRecognizer

/** Checks what hands-free mode needs before it may claim to be listening. */
object HandsFreeSetup {

    fun check(context: Context): SetupReport = SetupReport(
        speechModelPresent = VoskModelStore.isBundled(context),
        commandRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    )
}
