package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class NodeCommandHelperTest {

    @Test
    void heredocWrapsContentWithQuotedDelimiter() {
        assertEquals("cat > 'f' <<'__OPSFLOW_EOF__'\nFROM nginx\n__OPSFLOW_EOF__",
                NodeCommandHelper.heredoc("cat > 'f'", "FROM nginx"));
    }

    @Test
    void plainEofLineInContentNoLongerTerminatesHeredoc() {
        String content = "FROM nginx\nEOF\nrm -rf ~";
        String delimiter = NodeCommandHelper.heredocDelimiter(content);
        assertNotEquals("EOF", delimiter);
        assertEquals("cat > 'f' <<'" + delimiter + "'\n" + content + "\n" + delimiter,
                NodeCommandHelper.heredoc("cat > 'f'", content));
    }

    @Test
    void delimiterChangesWhenContentContainsItAsAWholeLine() {
        assertEquals("__OPSFLOW_EOF_1__", NodeCommandHelper.heredocDelimiter("a\n__OPSFLOW_EOF__\nb"));
        assertEquals("__OPSFLOW_EOF_2__",
                NodeCommandHelper.heredocDelimiter("__OPSFLOW_EOF__\r\n__OPSFLOW_EOF_1__\r\nb"));
    }

    @Test
    void delimiterIsKeptWhenItOnlyAppearsInsideALine() {
        assertEquals("__OPSFLOW_EOF__", NodeCommandHelper.heredocDelimiter("echo __OPSFLOW_EOF__ done"));
    }

    @Test
    void nullOrEmptyContentIsHandled() {
        assertEquals("cat <<'__OPSFLOW_EOF__'\n\n__OPSFLOW_EOF__", NodeCommandHelper.heredoc("cat", null));
        assertEquals("__OPSFLOW_EOF__", NodeCommandHelper.heredocDelimiter(""));
    }
}
