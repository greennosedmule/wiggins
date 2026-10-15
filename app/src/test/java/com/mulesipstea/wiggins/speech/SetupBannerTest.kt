package com.mulesipstea.wiggins.speech

import com.mulesipstea.wiggins.settings.SpeechMode
import com.mulesipstea.wiggins.settings.SpeechSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setup banner shows what's missing, and once dismissed, only what's newly missing (SPEC "Prerequisites"). */
class SetupBannerTest {
    private val hubSpeech = SpeechSettings(stt = SpeechMode.HUB, tts = SpeechMode.HUB)
    private val deviceSpeech = SpeechSettings(stt = SpeechMode.DEVICE, tts = SpeechMode.DEVICE)
    private val nothing = Prerequisites(recognizer = null, ttsEngines = emptyList(), isAssistant = false)

    @Test fun hubSpeechNeedsOnlyTheAssistantRole() {
        assertEquals(setOf(SetupItem.DEFAULT_ASSISTANT), nothing.missing(hubSpeech))
        assertEquals(emptySet<SetupItem>(), nothing.copy(isAssistant = true).missing(hubSpeech))
    }

    @Test fun deviceSpeechNeedsItsApps() {
        assertEquals(SetupItem.entries.toSet(), nothing.missing(deviceSpeech))
        val ready = Prerequisites(recognizer = "FUTO Voice Input", ttsEngines = listOf("RHVoice"), isAssistant = true)
        assertEquals(emptySet<SetupItem>(), ready.missing(deviceSpeech))
    }

    @Test fun missingMatchesSpeechReady() {
        for (p in listOf(nothing, nothing.copy(recognizer = "r"), nothing.copy(ttsEngines = listOf("t")), nothing.copy(isAssistant = true))) {
            for (s in listOf(hubSpeech, deviceSpeech, SpeechSettings(stt = SpeechMode.HUB), SpeechSettings(tts = SpeechMode.HUB))) {
                assertEquals("$p $s", !p.speechReady(s) || !p.isAssistant, p.missing(s).isNotEmpty())
            }
        }
    }

    @Test fun showsUntilDismissed() {
        val missing = setOf(SetupItem.DEFAULT_ASSISTANT)
        assertTrue(SetupItem.bannerShows(missing, emptySet()))
        assertFalse(SetupItem.bannerShows(missing, setOf("default_assistant")))
        assertFalse(SetupItem.bannerShows(emptySet(), emptySet()))
    }

    @Test fun showsAgainWhenSomethingNewIsMissing() {
        val dismissed = setOf("default_assistant")
        assertTrue(SetupItem.bannerShows(setOf(SetupItem.DEFAULT_ASSISTANT, SetupItem.TTS_ENGINE), dismissed))
        // Fixing a dismissed item doesn't bring the banner back.
        assertFalse(SetupItem.bannerShows(emptySet(), dismissed))
    }

    @Test fun nothingShowsBeforeTheDismissalIsLoaded() {
        assertFalse(SetupItem.bannerShows(setOf(SetupItem.DEFAULT_ASSISTANT), null))
    }
}
