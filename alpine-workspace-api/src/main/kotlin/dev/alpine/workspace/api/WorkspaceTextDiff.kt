package dev.alpine.workspace.api

/** A bounded, display-safe line diff for the app-private text editor. */
enum class WorkspaceDiffLineKind {
    CONTEXT,
    REMOVED,
    ADDED,
    TRUNCATED,
}

data class WorkspaceDiffLine(
    val kind: WorkspaceDiffLineKind,
    val text: String,
)

data class WorkspaceTextDiff(
    val changed: Boolean,
    val removedLineCount: Int,
    val addedLineCount: Int,
    val lines: List<WorkspaceDiffLine>,
)

/**
 * Produces a bounded line-oriented comparison without trying to retain an unbounded editor
 * history. The changed middle is intentionally presented as remove/add blocks; it is robust for
 * large files and is enough for a user to review unsaved changes before an explicit Save.
 */
fun workspaceTextDiff(
    savedText: String,
    editedText: String,
    maxLines: Int = DEFAULT_MAX_DIFF_LINES,
): WorkspaceTextDiff {
    require(maxLines >= MINIMUM_DIFF_LINES) { "maxLines must be at least $MINIMUM_DIFF_LINES" }
    if (savedText == editedText) {
        return WorkspaceTextDiff(changed = false, removedLineCount = 0, addedLineCount = 0, lines = emptyList())
    }
    val saved = savedText.normalizedLines()
    val edited = editedText.normalizedLines()
    var prefix = 0
    while (prefix < saved.size && prefix < edited.size && saved[prefix] == edited[prefix]) prefix += 1

    var savedEnd = saved.lastIndex
    var editedEnd = edited.lastIndex
    while (savedEnd >= prefix && editedEnd >= prefix && saved[savedEnd] == edited[editedEnd]) {
        savedEnd -= 1
        editedEnd -= 1
    }

    val removed = if (savedEnd < prefix) emptyList() else saved.subList(prefix, savedEnd + 1)
    val added = if (editedEnd < prefix) emptyList() else edited.subList(prefix, editedEnd + 1)
    val contextBefore = saved.subList((prefix - CONTEXT_LINES).coerceAtLeast(0), prefix)
    val contextAfterStart = savedEnd + 1
    val contextAfter = if (contextAfterStart > saved.lastIndex) {
        emptyList()
    } else {
        saved.subList(contextAfterStart, (contextAfterStart + CONTEXT_LINES).coerceAtMost(saved.size))
    }

    val lines = ArrayList<WorkspaceDiffLine>(maxLines)
    contextBefore.forEach { lines += WorkspaceDiffLine(WorkspaceDiffLineKind.CONTEXT, it) }
    appendChangedBlock(lines, WorkspaceDiffLineKind.REMOVED, removed, maxLines)
    appendChangedBlock(lines, WorkspaceDiffLineKind.ADDED, added, maxLines)
    if (lines.size < maxLines) {
        contextAfter.take(maxLines - lines.size).forEach {
            lines += WorkspaceDiffLine(WorkspaceDiffLineKind.CONTEXT, it)
        }
    }
    if (lines.size >= maxLines && (removed.size + added.size + contextBefore.size + contextAfter.size) > maxLines) {
        lines[lines.lastIndex] = WorkspaceDiffLine(
            WorkspaceDiffLineKind.TRUNCATED,
            "… 변경 내용 일부만 표시합니다.",
        )
    }
    return WorkspaceTextDiff(
        changed = true,
        removedLineCount = removed.size,
        addedLineCount = added.size,
        lines = lines,
    )
}

private fun appendChangedBlock(
    target: MutableList<WorkspaceDiffLine>,
    kind: WorkspaceDiffLineKind,
    source: List<String>,
    maxLines: Int,
) {
    source.take((maxLines - target.size).coerceAtLeast(0)).forEach { line ->
        target += WorkspaceDiffLine(kind, line.displaySafe())
    }
}

private fun String.normalizedLines(): List<String> =
    replace("\r\n", "\n").replace('\r', '\n').split('\n')

private fun String.displaySafe(): String =
    if (length <= MAX_LINE_DISPLAY_CHARACTERS) this else take(MAX_LINE_DISPLAY_CHARACTERS) + "…"

private const val CONTEXT_LINES = 3
private const val MINIMUM_DIFF_LINES = 8
private const val DEFAULT_MAX_DIFF_LINES = 160
private const val MAX_LINE_DISPLAY_CHARACTERS = 2_048
