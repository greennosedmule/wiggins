package com.mulesipstea.wiggins.speech

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale

/** What Wiggins needs from the rest of the phone (SPEC "Prerequisites"), checked on each resume. */
data class Prerequisites(
    /** Label of the app that will handle RecognizerIntent, or null if none is installed. */
    val recognizer: String?,
    /** Labels of installed TTS engines. */
    val ttsEngines: List<String>,
    val isAssistant: Boolean,
) {
    val speechReady get() = recognizer != null && ttsEngines.isNotEmpty()

    companion object {
        fun check(context: Context): Prerequisites {
            val pm = context.packageManager
            val recognizer = pm.resolveActivity(recognizeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
                ?.takeIf { it.activityInfo.packageName != "android" } // the chooser, when several qualify
                ?.loadLabel(pm)?.toString()
                ?: pm.queryIntentActivities(recognizeIntent(), 0).firstOrNull()?.loadLabel(pm)?.toString()
            val engines = pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
                .map { it.loadLabel(pm).toString() }
            val roles = context.getSystemService(RoleManager::class.java)
            return Prerequisites(recognizer, engines, roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
        }

        /** The request Wiggins hands to the user's recognizer app; it never records audio itself. */
        fun recognizeIntent(prompt: String? = null): Intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                prompt?.let { putExtra(RecognizerIntent.EXTRA_PROMPT, it) }
            }
    }
}
