package com.github.x0x0b.codexlauncher.diff

/**
 * Enumerates the types of lines encountered in a Codex diff preview.
 */
enum class CodexLineType {
    /** The line is part of the surrounding context (unchanged). */
    CONTEXT,

    /** The line is being added. */
    ADD,

    /** The line is being deleted. */
    DELETE
}

/**
 * Represents a single edit operation parsed from a Codex diff preview line.
 *
 * @property lineNumber The 1-based line number in the *original* file (before edits).
 * @property type The type of operation (ADD, DELETE, or CONTEXT).
 * @property text The content of the line.
 */
data class CodexEdit(
    val lineNumber: Int,
    val type: CodexLineType,
    val text: String
)

/**
 * Holds the parsed result of a Codex diff preview block.
 *
 * @property filePath The path of the file being modified.
 * @property originalSnippet The reconstructed original text snippet (from CONTEXT and DELETE lines).
 * @property suggestedSnippet The reconstructed suggested text snippet (from CONTEXT and ADD lines).
 * @property edits A list of individual line edits used for full-file patching.
 */
data class CodexPreview(
    val filePath: String,
    val originalSnippet: String,
    val suggestedSnippet: String,
    val edits: List<CodexEdit>
)

/**
 * Pure utility for parsing Codex CLI output into structured diff data.
 */
object CodexOutputParser {

    /**
     * Parses the terminal buffer text to extract a Codex preview block.
     *
     * Expected format example:
     *
     *   Would you like to make the following edits?
     *
     *     path/to/File.kt (+A -D)
     *
     *       18      }
     *       19 +    new line
     *       20 -    removed line
     *       21      context
     *
     * @param fullText The full text content of the terminal buffer.
     * @return A [CodexPreview] object if a valid preview block is found, null otherwise.
     */
    fun parsePreview(fullText: String): CodexPreview? {
        val marker = "Would you like to make the following edits?"
        val markerIndex = fullText.lastIndexOf(marker)
        if (markerIndex == -1) return null

        val tail = fullText.substring(markerIndex)
        val lines = tail.lines()

        // Find path line: "  path (+A -D)"
        // Matches lines like: "src/Main.kt (+2 -1)" or "  src/Main.kt (+2 -1)"
        val pathRegex = Regex("""^\s*(.+?)\s+\(\+(\d+)\s+-(\d+)\)\s*$""")
        var path: String? = null
        var idx = 0
        while (idx < lines.size) {
            val m = pathRegex.matchEntire(lines[idx])
            if (m != null) {
                path = m.groupValues[1].trim()
                idx++
                break
            }
            idx++
        }
        if (path == null) return null

        // Collect preview lines until we hit the options (1. Yes, proceed ...)
        val edits = mutableListOf<CodexEdit>()
        while (idx < lines.size) {
            val raw = lines[idx]
            val trimmed = raw.trim()
            
            // Stop parsing when we hit the interactive menu options
            if (trimmed.startsWith("1. Yes") || trimmed.contains("Yes, proceed")) {
                break
            }
            // skip obvious non-code markers
            if (trimmed.isEmpty() || trimmed == "⋮") {
                idx++
                continue
            }
            
            // Expect lines like "    19 +    text" or "    20      context"
            // 1. Extract line number
            val noIndent = raw.trimStart()
            val numberPart = noIndent.takeWhile { it.isDigit() }
            if (numberPart.isEmpty()) {
                idx++
                continue
            }
            val lineNumber = numberPart.toIntOrNull()
            if (lineNumber == null) {
                idx++
                continue
            }

            var rest = noIndent.drop(numberPart.length)
            if (rest.isEmpty()) {
                idx++
                continue
            }

            // 2. Find the diff marker (+, -, or space)
            // It should be the first non-whitespace character after the number.
            val firstNonWsIndex = rest.indexOfFirst { !it.isWhitespace() }
            if (firstNonWsIndex == -1) {
                idx++
                continue
            }

            val markerChar = rest[firstNonWsIndex]
            val type: CodexLineType
            
            // 3. Determine type and extract content
            when (markerChar) {
                '+' -> {
                    type = CodexLineType.ADD
                    // Remove just the '+' marker. 
                    // We remove from firstNonWsIndex to firstNonWsIndex + 1
                    rest = rest.removeRange(firstNonWsIndex, firstNonWsIndex + 1)
                }
                '-' -> {
                    type = CodexLineType.DELETE
                    rest = rest.removeRange(firstNonWsIndex, firstNonWsIndex + 1)
                }
                else -> {
                    type = CodexLineType.CONTEXT
                    // No marker to remove, rest contains the content (potentially with indentation)
                }
            }

            edits += CodexEdit(lineNumber, type, rest)
            idx++
        }

        if (edits.isEmpty()) return null

        val previewLines = edits.map { it.type to it.text }

        val original = mutableListOf<String>()
        val suggested = mutableListOf<String>()

        for ((type, text) in previewLines) {
            when (type) {
                CodexLineType.CONTEXT -> {
                    original += text
                    suggested += text
                }
                CodexLineType.ADD -> {
                    suggested += text
                }
                CodexLineType.DELETE -> {
                    original += text
                }
            }
        }

        val originalSnippet = original.joinToString("\n")
        val suggestedSnippet = suggested.joinToString("\n")

        return CodexPreview(path, originalSnippet, suggestedSnippet, edits.toList())
    }
}
