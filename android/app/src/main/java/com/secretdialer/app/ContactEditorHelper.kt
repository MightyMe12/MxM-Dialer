package com.secretdialer.app

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

object ContactEditorHelper {
    private const val TAG = "ContactEditorHelper"

    fun getContactBirthday(context: Context, contactId: Long): String? {
        val resolver = context.contentResolver
        val uri = ContactsContract.Data.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Event.START_DATE)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND " +
                "${ContactsContract.Data.MIMETYPE} = ? AND " +
                "${ContactsContract.CommonDataKinds.Event.TYPE} = ?"
        val selectionArgs = arrayOf(
            contactId.toString(),
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
        )
        try {
            resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query contact birthday", e)
        }
        return null
    }

    fun saveContact(
        context: Context,
        contactId: Long?,
        name: String,
        phone: String,
        birthday: String?
    ): Boolean {
        val resolver = context.contentResolver
        val ops = ArrayList<ContentProviderOperation>()

        try {
            if (contactId == null) {
                // ── CREATE NEW CONTACT ──
                val rawContactInsertIndex = ops.size
                
                ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build())

                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build())

                ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build())

                if (!birthday.isNullOrBlank()) {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                        .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, birthday)
                        .build())
                }
            } else {
                // ── EDIT EXISTING CONTACT ──
                // Find Raw Contact ID matching the given Contact ID
                var rawContactId: Long? = null
                val rawContactUri = ContactsContract.RawContacts.CONTENT_URI
                resolver.query(
                    rawContactUri,
                    arrayOf(ContactsContract.RawContacts._ID),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                    arrayOf(contactId.toString()),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        rawContactId = c.getLong(0)
                    }
                }

                val finalRawId = rawContactId ?: return false

                // 1. Update Name
                ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                        arrayOf(finalRawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build())

                // 2. Update Phone (we update mobile phone or create if doesn't exist)
                var phoneId: Long? = null
                resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(finalRawId.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        phoneId = c.getLong(0)
                    }
                }

                if (phoneId != null) {
                    ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(phoneId.toString()))
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .build())
                } else {
                    ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, finalRawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build())
                }

                // 3. Update Birthday
                var birthdayId: Long? = null
                resolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data._ID),
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                    arrayOf(
                        finalRawId.toString(),
                        ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()
                    ),
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        birthdayId = c.getLong(0)
                    }
                }

                if (!birthday.isNullOrBlank()) {
                    if (birthdayId != null) {
                        ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(birthdayId.toString()))
                            .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, birthday)
                            .build())
                    } else {
                        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, finalRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Event.TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
                            .withValue(ContactsContract.CommonDataKinds.Event.START_DATE, birthday)
                            .build())
                    }
                } else if (birthdayId != null) {
                    // If user cleared birthday, remove it
                    ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(birthdayId.toString()))
                        .build())
                }
            }

            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            
            // Force refresh of the in-memory contact cache
            ContactCache.preload(context, force = true)

            // Automatically link to Google Calendars
            if (!birthday.isNullOrBlank()) {
                syncBirthdayToCalendar(context, name, birthday)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save contact", e)
            return false
        }
    }

    private fun syncBirthdayToCalendar(context: Context, name: String, birthdayStr: String) {
        val parts = birthdayStr.split("-")
        val year = if (parts.size == 3) parts[0].toInt() else Calendar.getInstance().get(Calendar.YEAR)
        val month = if (parts.size == 3) parts[1].toInt() - 1 else if (parts.size == 2) parts[0].toInt() - 1 else return
        val day = if (parts.size == 3) parts[2].toInt() else if (parts.size == 2) parts[1].toInt() else return

        val resolver = context.contentResolver
        var calendarId: Long = -1

        try {
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE
            )
            resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val accType = cursor.getString(2)
                    if (accType?.contains("google", ignoreCase = true) == true) {
                        calendarId = id
                        break
                    }
                    if (calendarId == -1L) {
                        calendarId = id
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar read permission denied", e)
            return
        }

        if (calendarId == -1L) {
            Log.w(TAG, "No writeable calendar found")
            return
        }

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }

        val eventTitle = "$name's Birthday"
        var eventExists = false
        try {
            val proj = arrayOf(CalendarContract.Events._ID)
            val sel = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} = ?"
            val args = arrayOf(calendarId.toString(), eventTitle)
            resolver.query(
                CalendarContract.Events.CONTENT_URI,
                proj,
                sel,
                args,
                null
            )?.use { c ->
                if (c.count > 0) eventExists = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed checking event duplicates", e)
        }

        if (eventExists) return

        try {
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, eventTitle)
                put(CalendarContract.Events.DESCRIPTION, "Birthday automatically linked by MxM Dialer")
                put(CalendarContract.Events.DTSTART, cal.timeInMillis)
                put(CalendarContract.Events.DTEND, cal.timeInMillis + 60 * 60 * 1000)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.RRULE, "FREQ=YEARLY")
            }
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Log.i(TAG, "Birthday calendar event linked successfully for $name")
        } catch (e: SecurityException) {
            Log.w(TAG, "Calendar write permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed inserting calendar event", e)
        }
    }
}
