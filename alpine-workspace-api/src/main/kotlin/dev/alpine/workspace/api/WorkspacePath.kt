package dev.alpine.workspace.api

/** Slash-separated path relative to one workspace root. Empty string represents the root. */
data class WorkspacePath(val value: String) : Comparable<WorkspacePath> {
    init {
        require(value.length <= MAX_PATH_LENGTH) { "workspace path is too long" }
        require(!value.startsWith('/') && !value.endsWith('/')) { "workspace path must be relative" }
        require('\\' !in value && '\u0000' !in value) { "workspace path contains an invalid character" }
        require(value.split('/').all { segment ->
            value.isEmpty() || segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.length <= MAX_SEGMENT_LENGTH
        }) { "workspace path contains an invalid segment" }
    }

    val isRoot: Boolean get() = value.isEmpty()
    val name: String get() = if (isRoot) "" else value.substringAfterLast('/')
    val parent: WorkspacePath?
        get() = if (isRoot) null else WorkspacePath(value.substringBeforeLast('/', ""))

    fun resolve(childName: String): WorkspacePath {
        require('/' !in childName) { "childName must be one segment" }
        return WorkspacePath(if (isRoot) childName else "$value/$childName")
    }

    override fun compareTo(other: WorkspacePath): Int = value.compareTo(other.value)

    companion object {
        @JvmField
        val ROOT = WorkspacePath("")
        private const val MAX_PATH_LENGTH = 4096
        private const val MAX_SEGMENT_LENGTH = 255
    }
}
