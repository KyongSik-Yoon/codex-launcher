package com.github.x0x0b.codexlauncher.diff

import org.junit.Test
import org.junit.Assert.*

class CodexOutputParserTest {

    @Test
    fun testParseBasicPreview() {
        val output = """
            Some random output...
            
            Would you like to make the following edits?

              src/main/kotlin/Main.kt (+1 -1)

                  18      fun main() {
                  19 -        println("Hello")
                  19 +        println("World")
                  20      }

            1. Yes, proceed
            2. No, cancel
        """.trimIndent()

        val preview = CodexOutputParser.parsePreview(output)
        assertNotNull("Preview should be parsed", preview)
        assertEquals("src/main/kotlin/Main.kt", preview!!.filePath)
        
        val edits = preview.edits
        assertEquals(4, edits.size)
        
        assertEquals(18, edits[0].lineNumber)
        assertEquals(CodexLineType.CONTEXT, edits[0].type)
        assertEquals("      fun main() {", edits[0].text)
        
        assertEquals(19, edits[1].lineNumber)
        assertEquals(CodexLineType.DELETE, edits[1].type)
        assertEquals("         println(\"Hello\")", edits[1].text)
        
        assertEquals(19, edits[2].lineNumber)
        assertEquals(CodexLineType.ADD, edits[2].type)
        assertEquals("         println(\"World\")", edits[2].text)
        
        assertEquals(20, edits[3].lineNumber)
        assertEquals(CodexLineType.CONTEXT, edits[3].type)
        assertEquals("      }", edits[3].text)
    }

    @Test
    fun testParsePreviewWithIndentation() {
        // Ensure indentation is preserved in the text content
        val output = """
            Would you like to make the following edits?

              Complex.java (+2 -0)

                  50      if (condition) {
                  51 +        // nested comment
                  51 +        doSomething();
                  52      }
            
            1. Yes
        """.trimIndent()

        val preview = CodexOutputParser.parsePreview(output)
        assertNotNull(preview)
        assertEquals("Complex.java", preview!!.filePath)
        
        assertEquals("         // nested comment", preview.edits[1].text)
        assertEquals("         doSomething();", preview.edits[2].text)
    }

    @Test
    fun testParseNoPreviewFound() {
        val output = """
            Thinking...
            Done.
        """.trimIndent()

        val preview = CodexOutputParser.parsePreview(output)
        assertNull(preview)
    }

    @Test
    fun testParsePartialOutput() {
        // If the output is cut off before the options, we might still want to parse what we have?
        // The current implementation stops at "1. Yes" or end of lines.
        val output = """
            Would you like to make the following edits?

              Test.kt (+1 -0)

                  10 +    val x = 1
        """.trimIndent()

        val preview = CodexOutputParser.parsePreview(output)
        assertNotNull(preview)
        assertEquals(1, preview!!.edits.size)
    }
    
    @Test
    fun testParseWithNoise() {
        val output = """
            Would you like to make the following edits?

              foo/bar.txt (+1 -1)

                  1      line 1
                  ⋮
                  5      line 5
                  6 -    old
                  6 +    new
            
            1. Yes
        """.trimIndent()
        
        val preview = CodexOutputParser.parsePreview(output)
        assertNotNull(preview)
        // Should skip the '⋮' line
        assertEquals(4, preview!!.edits.size) 
        assertEquals("      line 1", preview.edits[0].text)
        assertEquals("      line 5", preview.edits[1].text)
    }
}
