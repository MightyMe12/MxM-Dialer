package com.secretdialer.app

import android.os.Handler
import android.os.Looper

class Debouncer(
    private val delayMs: Long,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    private var pending: Runnable? = null

    fun submit(action: () -> Unit) {
        pending?.let { handler.removeCallbacks(it) }
        val task = Runnable { action() }
        pending = task
        handler.postDelayed(task, delayMs)
    }

    fun cancel() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }
}
