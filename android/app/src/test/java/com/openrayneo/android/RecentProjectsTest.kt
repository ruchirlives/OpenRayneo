package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class RecentProjectsTest {
    private fun thread(id: String, cwd: String, time: Long) = RecentProject(id, cwd, "Conversation $id", time)
    @Test fun returnsFiveDistinctProjectsInsteadOfFiveChatsInOneFolder() {
        val input = (1..20).map { thread("same$it", "C:\\Work\\One", it.toLong()) } +
            (1..6).map { thread("other$it", "C:\\Work\\Project$it", it.toLong()) }
        val result = RecentProjects.latest(input)
        assertEquals(5, result.size)
        assertEquals("same20", result.first().threadId)
        assertEquals(5, result.map { it.cwd }.toSet().size)
    }
    @Test fun windowsSlashAndCaseVariantsResumeTheLatestChat() {
        val result = RecentProjects.latest(listOf(thread("old", "C:\\Work\\APP", 1), thread("new", "c:/work/app/", 4)))
        assertEquals(listOf("new"), result.map { it.threadId })
        assertEquals("c:/work/app/", result.single().cwd)
    }
    @Test fun unixProjectNamesRemainCaseSensitive() {
        assertEquals(2, RecentProjects.latest(listOf(thread("a", "/work/App", 1), thread("b", "/work/app", 2))).size)
    }
    @Test fun excludesInvalidRowsAndSortsByActivity() {
        val result = RecentProjects.latest(listOf(thread("", "C:/bad", 30), thread("bad", "", 40),
            thread("old", "C:/old", 1), thread("new", "C:/new", 5)))
        assertEquals(listOf("new", "old"), result.map { it.threadId })
    }
    @Test fun projectLabelSupportsWindowsAndUnixPaths() {
        assertEquals("App", thread("1", "C:\\Work\\App\\", 1).name)
        assertEquals("App", thread("2", "/work/App/", 1).name)
    }
}
