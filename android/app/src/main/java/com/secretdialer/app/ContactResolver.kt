package com.secretdialer.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object ContactResolver {

    fun lookupByNumber(context: Context, rawNumber: String): ContactInfo? {
        val cached = ContactCache.current().nameForNumber(rawNumber)
        if (cached != null) {
            return ContactInfo(0, cached, rawNumber, null)
        }
        val digits = rawNumber.filter { it.isDigit() }
        if (digits.length < 4) return null
        val resolver = context.contentResolver
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(rawNumber)
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.NUMBER,
            ContactsContract.PhoneLookup.PHOTO_URI
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: return null
                val number = cursor.getString(2) ?: rawNumber
                val photo = cursor.getString(3)?.let { Uri.parse(it) }
                return ContactInfo(id, name, number, photo)
            }
        }
        return null
    }

    fun loadAllContacts(context: Context): List<ContactInfo> {
        val resolver = context.contentResolver
        val list = mutableListOf<ContactInfo>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numIdx) ?: continue
                val photo = if (photoIdx >= 0) cursor.getString(photoIdx)?.let { Uri.parse(it) } else null
                list.add(ContactInfo(id, name, number, photo))
            }
        }
        return list.distinctBy { it.number.filter { c -> c.isDigit() } }
    }

    fun queryCallFrequencies(context: Context): Map<String, Int> {
        val frequencies = mutableMapOf<String, Int>()
        val uri = android.provider.CallLog.Calls.CONTENT_URI
        val projection = arrayOf(android.provider.CallLog.Calls.NUMBER)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                if (numIdx != -1) {
                    while (cursor.moveToNext()) {
                        val num = cursor.getString(numIdx)
                        if (!num.isNullOrEmpty()) {
                            val clean = num.filter { it.isDigit() }
                            frequencies[clean] = (frequencies[clean] ?: 0) + 1
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ContactResolver", "Read Call Log permission not granted yet", e)
        } catch (e: Exception) {
            android.util.Log.e("ContactResolver", "Failed to query call frequencies", e)
        }
        return frequencies
    }

    fun isSavedContact(context: Context, number: String): Boolean =
        ContactCache.current().nameForNumber(number) != null ||
            lookupByNumber(context, number) != null
}
