package com.felix.app

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Opens the offline speech model that ships in app/src/main/assets. */
object VoskModelStore {

    const val ASSET_DIR = "vosk-model-small-en-in-0.4"
    private const val COMPLETE_MARKER = ".felix-complete"

    fun isBundled(context: Context): Boolean = try {
        context.assets.list(ASSET_DIR)?.isNotEmpty() == true
    } catch (e: IOException) {
        false
    }

    /** Copies the model out of the APK once, then opens it. Throws HandsFreeFailure if that fails. */
    fun open(context: Context): Model {
        val target = File(context.filesDir, ASSET_DIR)
        try {
            val marker = File(target, COMPLETE_MARKER)
            if (!marker.exists()) {
                target.deleteRecursively()
                copyAssetTree(context.assets, ASSET_DIR, target)
                marker.writeText("ok")
            }
            return Model(target.absolutePath)
        } catch (e: IOException) {
            throw HandsFreeFailure(
                HandsFreePhase.SPEECH_UNAVAILABLE,
                "The offline speech model is missing or damaged. Reinstall Felix from a build that contains the model."
            )
        } catch (e: RuntimeException) {
            throw HandsFreeFailure(
                HandsFreePhase.SPEECH_UNAVAILABLE,
                "The offline speech model couldn't load on this phone."
            )
        }
    }

    private fun copyAssetTree(assets: AssetManager, assetPath: String, destination: File) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        } else {
            destination.mkdirs()
            for (child in children) {
                copyAssetTree(assets, "$assetPath/$child", File(destination, child))
            }
        }
    }
}

/**
 * Offline wake-word spotter. A grammar-restricted Vosk recognizer that can only emit
 * "felix" or [unk], so ambient speech is never transcribed into text.
 * Feed 16 kHz mono 16-bit frames. feed() returns true when a finished utterance contains the wake word.
 * Partial text is deliberately ignored: while "please" is still being spoken it can briefly read as "felix".
 */
class VoskWakeWordDetector(model: Model) : Releasable {

    private val recognizer: Recognizer = Recognizer(model, SAMPLE_RATE, WakeWord.GRAMMAR)

    fun feed(frame: ShortArray): Boolean {
        if (!recognizer.acceptWaveForm(frame, frame.size)) return false
        return WakeWord.containsWakeWord(JSONObject(recognizer.result).optString("text"))
    }

    override fun release() {
        recognizer.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}
