package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void runLocalCapturesOutputAndStreamsLines() {
        List<String> lines = new ArrayList<>();
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("printf 'one\\ntwo\\n'; echo three >&2", null, 10000, lines::add);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals("one\ntwo\nthree\n", result.getOutput());
        assertEquals(Arrays.asList("one", "two", "three"), lines);
    }

    @Test
    void runLocalReportsExitCodeAndKeepsOutput() {
        NodeCommandHelper.CommandResult result = new NodeCommandHelper()
                .runLocal("echo before-fail; exit 3", null, 10000, null);

        assertFalse(result.isSuccess());
        assertEquals(3, result.getExitCode());
        assertEquals("before-fail\n", result.getOutput());
    }

    @Test
    void nullOrEmptyContentIsHandled() {
        assertEquals("cat <<'__OPSFLOW_EOF__'\n\n__OPSFLOW_EOF__", NodeCommandHelper.heredoc("cat", null));
        assertEquals("__OPSFLOW_EOF__", NodeCommandHelper.heredocDelimiter(""));
    }
}
