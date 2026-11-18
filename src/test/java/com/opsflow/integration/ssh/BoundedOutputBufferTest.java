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

    /** 按最直白的方式算出期望结果：保留开头 headLimit 字节和结尾 tailLimit 字节，切口处补齐字符边界 */
    private static String expected(byte[] all, int headLimit, int tailLimit) {
        if (all.length <= headLimit + tailLimit) {
            return new String(all, StandardCharsets.UTF_8);
        }
        int headEnd = headLimit;
        while (headEnd > 0 && (all[headEnd] & 0xC0) == 0x80) {
            headEnd--;
        }
        int tailBegin = all.length - tailLimit;
        while (tailBegin < all.length && (all[tailBegin] & 0xC0) == 0x80) {
            tailBegin++;
        }
        long dropped = all.length - headLimit - tailLimit;
        return new String(all, 0, headEnd, StandardCharsets.UTF_8)
            + "\n...... [输出过长，已省略中间约 " + dropped + " 字节] ......\n"
            + new String(all, tailBegin, all.length - tailBegin, StandardCharsets.UTF_8);
    }

    @Test
    void randomChunkedWritesMatchStraightforwardResult() {
        java.util.Random random = new java.util.Random(20261118L);
        String[] pieces = {"a", "build ok\n", "中", "构建成功\n", "\uD83D\uDE80", "é", "\r\n"};
        for (int round = 0; round < 300; round++) {
            StringBuilder text = new StringBuilder();
            int count = random.nextInt(60);
            for (int i = 0; i < count; i++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            byte[] all = text.toString().getBytes(StandardCharsets.UTF_8);
            int headLimit = random.nextInt(20);
            int tailLimit = 1 + random.nextInt(30);
            BoundedOutputBuffer buffer = new BoundedOutputBuffer(headLimit, tailLimit);
            int offset = 0;
            while (offset < all.length) {
                int n = Math.min(all.length - offset, 1 + random.nextInt(40));
                buffer.write(all, offset, n);
                offset += n;
            }
            String message = "round=" + round + " head=" + headLimit + " tail=" + tailLimit + " bytes=" + all.length;
            assertEquals(expected(all, headLimit, tailLimit), buffer.toString(), message);
            assertEquals(Math.min(all.length, headLimit + tailLimit), buffer.size(), message);
            assertEquals(Math.max(0, all.length - headLimit - tailLimit), buffer.droppedBytes(), message);
        }
    }
}
