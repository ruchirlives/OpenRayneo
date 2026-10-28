package com.openrayneo.android

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CodexHistoryTest {
    @Test fun newestFirstTurnsBecomeChronologicalConversation() {
        val records = CodexHistory.records(JSONArray("""[
            {"items":[{"type":"userMessage","content":[{"type":"text","text":"Second"}]},{"type":"agentMessage","text":"Answer two"}]},
            {"items":[{"type":"userMessage","content":[{"type":"text","text":"First"}]},{"type":"agentMessage","text":"Answer one"}]}
        ]"""))
        assertEquals(listOf("First", "Second"), records.map { it.question })
        assertEquals(listOf("Answer one", "Answer two"), records.map { it.answer })
    }
    @Test fun toolOutputDoesNotLeakIntoChatAndTextFragmentsAreJoined() {
        val records = CodexHistory.records(JSONArray("""[{"items":[
            {"type":"userMessage","content":[{"type":"text","text":"Question"},{"type":"image","url":"ignored"}]},
            {"type":"commandExecution","text":"hidden command log"},
            {"type":"agentMessage","text":"Part one"},{"type":"agentMessage","text":"Part two"}
        ]}]"""))
        assertEquals("Question", records.single().question)
        assertEquals("Part one\n\nPart two", records.single().answer)
    }
    @Test fun assistantOnlyAndUnansweredTurnsRemainVisible() {
        assertEquals("Answer", CodexHistory.records(JSONArray("""[{"items":[{"type":"agentMessage","text":"Answer"}]}]""")).single().answer)
        assertEquals("Question", CodexHistory.records(JSONArray("""[{"items":[{"type":"userMessage","content":[{"type":"text","text":"Question"}]}]}]""")).single().question)
    }
    @Test fun emptyAndToolOnlyTurnsAreSkipped() {
        assertTrue(CodexHistory.records(JSONArray("""[{"items":[]},{"items":[{"type":"commandExecution"}]}]""")).isEmpty())
    }
}
