package com.morselink.app.core.transfer

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import com.morselink.app.di.AppServices

/** Small sound effects wired to real events (spec Section 10.10). */
object SoundFx {

    const val CONNECTED = 1
    const val COMPLETE = 2
    const val FAILED = 3

    private val handler = Handler(Looper.getMainLooper())

    fun play(kind: Int) {
        if (!AppServices.prefs.soundEffects) return
        handler.post {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
                when (kind) {
                    CONNECTED -> tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
                    COMPLETE -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
                    FAILED -> tone.startTone(ToneGenerator.TONE_PROP_NACK, 300)
                }
                handler.postDelayed({
                    try {
                        tone.release()
                    } catch (_: Exception) {
                    }
                }, 600)
            } catch (_: Exception) {
            }
        }
    }
}
