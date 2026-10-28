package com.openrayneo.android

import org.json.JSONArray

internal object CodexHistory {
    /** API pages arrive newest first. Display only user/assistant text, in chronological order. */
    fun records(turns: JSONArray): List<ChatRecord> = (0 until turns.length()).reversed().mapNotNull { index ->
        val items = turns.getJSONObject(index).optJSONArray("items") ?: JSONArray()
        val questions = mutableListOf<String>()
        val answers = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            when (item.optString("type")) {
                "userMessage" -> {
                    val content = item.optJSONArray("content") ?: JSONArray()
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") questions += part.optString("text")
                    }
                }
                "agentMessage" -> answers += item.optString("text")
            }
        }
        val question = questions.joinToString("\n").takeLast(24000)
        val answer = answers.joinToString("\n\n").takeLast(24000)
        if (question.isBlank() && answer.isBlank()) null else ChatRecord(question, answer)
    }
}
