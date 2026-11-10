package com.felix.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/**
 * Places a real cellular call through Android's Telecom service.
 * Works from a background service, which is why it is used instead of an ACTION_CALL activity
 * (Android blocks activity launches from the background). Requires CALL_PHONE.
 */
class TelecomCallExecutor(private val context: Context) : CallExecutor {

    override fun placeCall(phone: String): CallOutcome {
        val uri = FelixLogic.telUri(phone) ?: return CallOutcome.Failed("the number is invalid")
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return CallOutcome.Failed("this device has no telephony")
        }
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return CallOutcome.Failed("the telephony service is unavailable")
        return try {
            telecom.placeCall(Uri.parse(uri), Bundle())
            CallOutcome.Sent
        } catch (e: SecurityException) {
            CallOutcome.PermissionDenied
        } catch (e: RuntimeException) {
            CallOutcome.Failed(e.javaClass.simpleName)
        }
    }
}
