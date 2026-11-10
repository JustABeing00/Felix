package com.felix.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat

/**
 * Reads phone contacts on the device. Entries are grouped by contact ID, so two different
 * people who share a name stay separate and produce an ambiguous match instead of a guess.
 */
object ContactsRepository {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun load(context: Context): List<FelixContact> {
        if (!hasPermission(context)) return emptyList()
        val names = LinkedHashMap<String, String>()
        val numbers = LinkedHashMap<String, MutableList<String>>()
        try {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NORMALIZED_NUMBER, Phone.NUMBER),
                null,
                null,
                "${Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Phone.CONTACT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
                val normalizedIndex = cursor.getColumnIndexOrThrow(Phone.NORMALIZED_NUMBER)
                val numberIndex = cursor.getColumnIndexOrThrow(Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = FelixLogic.normalizeWhitespace(cursor.getString(nameIndex).orEmpty())
                    if (name.isEmpty()) continue
                    val number = FelixLogic.normalizePhone(cursor.getString(normalizedIndex))
                        ?: FelixLogic.normalizePhone(cursor.getString(numberIndex))
                        ?: continue
                    names.putIfAbsent(id, name)
                    numbers.getOrPut(id) { mutableListOf() }.add(number)
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return numbers.map { (id, phones) ->
            FelixContact(id = id, name = names.getValue(id), phones = phones.distinct())
        }
    }
}
