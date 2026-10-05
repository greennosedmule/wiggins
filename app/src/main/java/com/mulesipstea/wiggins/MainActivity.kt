package com.mulesipstea.wiggins

import android.content.ActivityNotFoundException
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mulesipstea.wiggins.speech.Prerequisites
import com.mulesipstea.wiggins.ui.WigginsRoot
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    /** Hands speech-to-text to the user's recognizer app; Wiggins never records audio. */
    private val recognize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        viewModel.assistant.onRecognized(text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The volume keys adjust Wiggins' voice, even between sentences.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent { WigginsRoot(viewModel) }

        lifecycleScope.launch {
            // RESUMED, not STARTED: whichever Wiggins screen is in front starts the recognizer.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.assistant.listenRequests.collect { startRecognizer() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.assistant.onForeground()
    }

    override fun onStop() {
        viewModel.assistant.onBackground()
        super.onStop()
    }

    private fun startRecognizer() {
        try {
            recognize.launch(Prerequisites.recognizeIntent())
            viewModel.assistant.onRecognizerStarted()
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no speech recognizer", e)
            viewModel.assistant.refreshPrerequisites()
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
