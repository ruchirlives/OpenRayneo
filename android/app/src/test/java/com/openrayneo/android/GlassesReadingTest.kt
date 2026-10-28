package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class GlassesReadingTest {
    private fun answer(persistent: Boolean) = AnswerContent(AnswerRef(AnswerSource.COMPUTER_CODEX, 1),
        "A long question that should not consume the reply's reading area", "First paragraph.\n\nSecond paragraph.", persistent)
    private fun initial() = GlassesState(link = LinkState(LinkPhase.FULL), intent = DisplayIntent.Navigation,
        navigation = NavigationState(enabled = true, frame = "navigation"))
    @Test fun readerPreservesTheReplyAndUsesAShortHeading() {
        val content = answer(true)
        val result = GlassesCoordinator.reduce(initial(), GlassesEvent.RevealAnswer(content))
        val page = result.effects.filterIsInstance<GlassesEffect.StartPage>().single().session
        assertEquals("Codex", page.content.text)
        assertEquals(content.text, page.content.answer)
        assertEquals(PageMode.PROMPT, page.mode)
    }
    @Test fun persistentReplyDoesNotExpireDuringReading() {
        val opened = GlassesCoordinator.reduce(initial(), GlassesEvent.RevealAnswer(answer(true))).state
        val session = opened.session!!
        val written = GlassesCoordinator.reduce(opened, GlassesEvent.SessionSignal(session.id, PageSignal.Written(session.content)))
        assertFalse(written.effects.any { it is GlassesEffect.ScheduleRestore })
    }
    @Test fun nonPersistentAnswersKeepNavigationRestoreBehavior() {
        val opened = GlassesCoordinator.reduce(initial(), GlassesEvent.RevealAnswer(answer(false))).state
        val session = opened.session!!
        val written = GlassesCoordinator.reduce(opened, GlassesEvent.SessionSignal(session.id, PageSignal.Written(session.content)))
        assertTrue(written.effects.any { it is GlassesEffect.ScheduleRestore })
    }
}
