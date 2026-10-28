package com.openrayneo.android

internal data class RecentProject(val threadId: String, val cwd: String, val title: String, val updatedAt: Long) {
    val name: String get() = cwd.trimEnd('/', '\\').substringAfterLast('\\').substringAfterLast('/').ifBlank { cwd }
}

internal object RecentProjects {
    private fun key(path: String): String {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return if (Regex("^[A-Za-z]:").containsMatchIn(normalized) || path.startsWith("\\\\")) normalized.lowercase(java.util.Locale.ROOT)
            else normalized
    }
    fun latest(threads: List<RecentProject>, limit: Int = 5): List<RecentProject> =
        threads.filter { it.threadId.isNotBlank() && it.cwd.isNotBlank() }
            .sortedByDescending { it.updatedAt }.distinctBy { key(it.cwd) }.take(limit)
}
