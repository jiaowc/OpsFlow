package com.opsflow.integration.ssh;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedOutputBufferTest {

    private static void write(BoundedOutputBuffer buffer, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes, 0, bytes.length);
    }

    @Test
    void outputWithinLimitsIsKeptIntact() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(4, 8);
        write(buffer, "abcd");
        write(buffer, "efgh");
        write(buffer, "ijkl");
        assertEquals("abcdefghijkl", buffer.toString());
        assertEquals(0, buffer.droppedBytes());
        assertEquals(12, buffer.size());
    }

    @Test
    void middleIsDroppedAndHeadAndTailAreKept() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(4, 6);
        write(buffer, "HEAD");
        for (int i = 0; i < 10; i++) {
            write(buffer, "line" + i + "\n");
        }
        String text = buffer.toString();
        assertTrue(text.startsWith("HEAD\n...... [输出过长，已省略中间约 54 字节] ......\n"), text);
        assertTrue(text.endsWith("line9\n"), text);
        assertEquals(54, buffer.droppedBytes());
        assertEquals(10, buffer.size());
    }

    @Test
    void singleWriteLargerThanTailKeepsOnlyItsEnd() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(2, 4);
        write(buffer, "ab");
        write(buffer, "xy");
        write(buffer, "0123456789");
        String text = buffer.toString();
        assertTrue(text.startsWith("ab\n"), text);
        assertTrue(text.endsWith("6789"), text);
        assertEquals(8, buffer.droppedBytes());
    }

    @Test
    void tailWrapsAroundManyTimesAndStaysInOrder() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(0, 7);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String piece = Integer.toString(i % 10) + (i % 3 == 0 ? "ab" : "");
            all.append(piece);
            write(buffer, piece);
        }
        String text = buffer.toString();
        assertTrue(text.endsWith(all.substring(all.length() - 7)), text);
        assertEquals(all.length() - 7, buffer.droppedBytes());
    }

    @Test
    void multiByteCharactersAreNotBrokenAtTheCuts() {
        // 每个汉字 3 字节：head 上限 4 会切在第二个字中间，tail 上限 5 会从某个字中间开始
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(4, 5);
        write(buffer, "构建日志输出很长很长很长结束");
        String text = buffer.toString();
        assertFalse(text.contains("�"), text);
        assertTrue(text.startsWith("构\n"), text);
        assertTrue(text.endsWith("束"), text);
    }

    @Test
    void multiByteCharacterSplitAcrossWritesIsDecodedWhenNothingDropped() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer(4, 16);
        byte[] bytes = "构建完成".getBytes(StandardCharsets.UTF_8);
        buffer.write(bytes, 0, 5);
        buffer.write(bytes, 5, bytes.length - 5);
        assertEquals("构建完成", buffer.toString());
    }

    @Test
    void appendLineAddsNewline() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        buffer.appendLine("a");
        buffer.appendLine(null);
        buffer.appendLine("b");
        assertEquals("a\n\nb\n", buffer.toString());
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputBuffer(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedOutputBuffer(-1, 1));
    }
}
