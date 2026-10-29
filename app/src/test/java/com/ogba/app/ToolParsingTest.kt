package com.ogba.app

import org.junit.Assert.*
import org.junit.Test

class ToolParsingTest {
    @Test fun parsesApprovedToolWithArguments() {
        val call = Tools.parseModelRequest("Plan: search for a primary source.\n[[tool:web.search latest findings]]")
        assertEquals("web.search", call?.tool)
        assertEquals("latest findings", call?.arg)
    }

    @Test fun doesNotExecuteToolMarkerInOrdinaryReply() {
        val answer = "Here is an example: [[tool:shell.run rm -rf /]]\nThis is only an example."
        assertNull(Tools.parseModelRequest(answer))
    }

    @Test fun parsesMultiLineWorkspaceWrite() {
        val call = Tools.parseModelRequest("[[tool:workspace.write notes/a.txt\nhello \"world\"\nnext line]]")
        assertEquals("workspace.write", call?.tool)
        assertEquals("notes/a.txt", call?.arg)
        assertEquals("hello \"world\"\nnext line", call?.content)
    }

    @Test fun unknownToolReachesPolicyGate() {
        val call = Tools.parseModelRequest("[[tool:admin.delete everything]]")
        assertEquals("admin.delete", call?.tool)
        assertFalse(call?.tool in Tools.registry)
    }

    @Test fun delegatesOneTaskToNamedBot() {
        val call = Tools.parseModelRequest("[[tool:bot.delegate Claude Architect | review my proposal]]")
        assertEquals("bot.delegate", call?.tool)
        assertEquals("Claude Architect | review my proposal", call?.arg)
        assertFalse(call?.tool in Tools.readOnly)
    }

    @Test fun rejectsMalformedSlashWorkspaceWrite() {
        try {
            Tools.parseUserCommand("/workspace write a.txt")
            fail("Expected ToolError")
        } catch (_: ToolError) { }
    }
}
