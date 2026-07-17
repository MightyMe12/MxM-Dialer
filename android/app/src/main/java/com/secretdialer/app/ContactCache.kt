package com.secretdialer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ContactCache {
    private const val TAG = "ContactCache"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var index: ContactIndex? = null
    private val loading = AtomicBoolean(false)
    private val listeners = mutableSetOf<(ContactIndex) -> Unit>()

    fun preload(context: Context, force: Boolean = false) {
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        val shouldReload = force || index == null || (index == ContactIndex.EMPTY && hasPermission)
        
        if (!shouldReload || !loading.compareAndSet(false, true)) {
            index?.let { built ->
                mainHandler.post {
                    val list = synchronized(listeners) { listeners.toList() }
                    list.forEach { it(built) }
                }
            }
            return
        }
        val app = context.applicationContext
        executor.execute {
            try {
                val contacts = ContactResolver.loadAllContacts(app)
                index = ContactIndex.build(contacts)
            } catch (e: SecurityException) {
                Log.w(TAG, "Contacts permission not granted yet", e)
                index = ContactIndex.EMPTY
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load contacts", e)
                index = ContactIndex.EMPTY
            } finally {
                loading.set(false)
                val built = index ?: ContactIndex.EMPTY
                mainHandler.post {
                    val list = synchronized(listeners) { listeners.toList() }
                    list.forEach { it(built) }
                }
            }
        }
    }

    fun addListener(context: Context, listener: (ContactIndex) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        val current = index
        if (current != null && !(current == ContactIndex.EMPTY && hasPermission)) {
            listener(current)
        } else {
            preload(context)
        }
    }

    fun removeListener(listener: (ContactIndex) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun current(): ContactIndex = index ?: ContactIndex.EMPTY

    fun invalidate() {
        index = null
    }
}
