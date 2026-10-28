package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesVoiceCommandTest {
    @Test fun ordinaryConversationDoesNotExecute() {
        for (text in listOf("Turn off the lights", "I asked Codex to do it", "", "Codec, delete this"))
            assertNull(GlassesVoiceCommand.request(text))
    }
    @Test fun emptyAndPartialWakeAddressesDoNotExecute() {
        for (text in listOf("Codex", "Hey Codex!", "Code", "Codex,   "))
            assertNull(GlassesVoiceCommand.request(text))
    }
    @Test fun explicitAddressPreservesTheRequest() {
        assertEquals("Tell me the time.", GlassesVoiceCommand.request("  Hey CODEX, Tell me the time.  "))
        assertEquals("explain this", GlassesVoiceCommand.request("Code X: explain this"))
        assertEquals("are you there?", GlassesVoiceCommand.request("codecs are you there?"))
    }
    @Test fun wakeWordMustHaveAWordBoundary() {
        assertNull(GlassesVoiceCommand.request("Codexification is a word"))
    }
    @Test fun stopPhraseReachesTheLocalControlHandler() {
        assertEquals("stop listening.", GlassesVoiceCommand.request("Codex, stop listening."))
    }
}
