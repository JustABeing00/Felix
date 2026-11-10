package com.felix.app

import android.content.Context

/**
 * User choices stored on the phone only: automatic calling, its acknowledgement,
 * and a remembered number for contacts that have several.
 */
class FelixSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Automatic calling is only honored once the acknowledgement has been given too. */
    var autoCallEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CALL, value).apply()
        }

    var autoCallAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_ACKNOWLEDGED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ACKNOWLEDGED, value).apply()
        }

    /** Sound-alike matching ("satwinder" finds Satvinder). On by default; switch it off for exact matches only. */
    var soundMatchingEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_MATCHING, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SOUND_MATCHING, value).apply()
        }

    fun preferredNumbers(): Map<String, String> = prefs.all
        .mapNotNull { (key, value) ->
            if (key.startsWith(PREFERRED_PREFIX) && value is String) {
                key.removePrefix(PREFERRED_PREFIX) to value
            } else {
                null
            }
        }
        .toMap()

    fun setPreferredNumber(contactId: String, phone: String) {
        prefs.edit().putString(PREFERRED_PREFIX + contactId, phone).apply()
    }

    private companion object {
        const val PREFS_NAME = "felix_settings"
        const val KEY_AUTO_CALL = "auto_call_enabled"
        const val KEY_ACKNOWLEDGED = "auto_call_acknowledged"
        const val KEY_SOUND_MATCHING = "sound_matching_enabled"
        const val PREFERRED_PREFIX = "preferred_number::"
    }
}
