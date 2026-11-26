package com.opsflow.integration.ssh;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOutputSinkTest {

    private final Object lock = new Object();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final List<String> lines = new ArrayList<>();

    private CommandOutputSink sink(BoundedOutputBuffer buffer, Consumer<String> listener, int maxLineBytes) {
        return new CommandOutputSink(lock, buffer, listener, maxLineBytes, failure);
    }

    private static void write(CommandOutputSink sink, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sink.write(bytes, 0, bytes.length);
    }

    @Test
    void splitsLinesOnLfAndDropsCr() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        CommandOutputSink sink = sink(buffer, lines::add, 1024);
        write(sink, "one\ntwo\r\n\nthree");
        assertEquals(Arrays.asList("one", "two", ""), lines);
        sink.finish();
        assertEquals(Arrays.asList("one", "two", "", "three"), lines);
        assertEquals("one\ntwo\r\n\nthree", buffer.toString());
    }

    @Test
    void lineSpanningSeveralWritesIsEmittedOnce() {
        CommandOutputSink sink = sink(new BoundedOutputBuffer(), lines::add, 1024);
        write(sink, "hel");
        write(sink, "lo wor");
        assertTrue(lines.isEmpty());
        write(sink, "ld\nnext");
        assertEquals(Collections.singletonList("hello world"), lines);
    }

    @Test
    void multiByteCharacterSplitAcrossWritesIsNotGarbled() {
        CommandOutputSink sink = sink(new BoundedOutputBuffer(), lines::add, 1024);
        byte[] bytes = "构建成功\n".getBytes(StandardCharsets.UTF_8);
        // 每次只写一个字节，模拟数据包在任意位置切开
        for (byte b : bytes) {
            sink.write(b);
        }
        assertEquals(Collections.singletonList("构建成功"), lines);
    }

    @Test
    void honoursOffsetAndLength() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        CommandOutputSink sink = sink(buffer, lines::add, 1024);
        byte[] bytes = "xxab\ncdyy".getBytes(StandardCharsets.UTF_8);
        sink.write(bytes, 2, 5);
        sink.finish();
        assertEquals(Arrays.asList("ab", "cd"), lines);
        assertEquals("ab\ncd", buffer.toString());
    }

    @Test
    void overlongLineIsForceSplit() {
        CommandOutputSink sink = sink(new BoundedOutputBuffer(), lines::add, 4);
        write(sink, "abcdefghij\n");
        assertEquals(Arrays.asList("abcd", "efgh", "ij"), lines);
    }

    @Test
    void withoutListenerOnlyBuffers() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        CommandOutputSink sink = sink(buffer, null, 1024);
        write(sink, "a\nb");
        sink.finish();
        assertEquals("a\nb", buffer.toString());
        assertNull(failure.get());
    }

    @Test
    void listenerFailureIsRecordedAndStopsCallbacksButKeepsBuffering() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        RuntimeException boom = new IllegalStateException("boom");
        CommandOutputSink sink = sink(buffer, line -> {
            lines.add(line);
            if ("bad".equals(line)) {
                throw boom;
            }
        }, 1024);
        write(sink, "ok\nbad\nafter\n");
        write(sink, "more\n");
        sink.finish();
        assertSame(boom, failure.get());
        assertEquals(Arrays.asList("ok", "bad"), lines);
        assertEquals("ok\nbad\nafter\nmore\n", buffer.toString());
    }

    @Test
    void failureOnOneStreamStopsCallbacksOnTheOther() {
        CommandOutputSink out = sink(new BoundedOutputBuffer(), line -> {
            throw new IllegalStateException("boom");
        }, 1024);
        CommandOutputSink err = sink(new BoundedOutputBuffer(), lines::add, 1024);
        write(out, "x\n");
        write(err, "y\n");
        assertTrue(lines.isEmpty());
    }

    @Test
    void dataArrivingAfterFinishIsIgnored() {
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        CommandOutputSink sink = sink(buffer, lines::add, 1024);
        write(sink, "a\n");
        sink.finish();
        write(sink, "late\n");
        sink.finish();
        assertEquals(Collections.singletonList("a"), lines);
        assertEquals("a\n", buffer.toString());
    }

    @Test
    void discardDropsPendingPartialLine() {
        CommandOutputSink sink = sink(new BoundedOutputBuffer(), lines::add, 1024);
        write(sink, "done\npartial");
        sink.discard();
        write(sink, "late\n");
        sink.finish();
        assertEquals(Collections.singletonList("done"), lines);
    }

    @Test
    void closeDoesNotStopLaterWrites() {
        // SSH 收到 EOF 时会 close 输出流，之后不应影响已有内容的读取
        BoundedOutputBuffer buffer = new BoundedOutputBuffer();
        CommandOutputSink sink = sink(buffer, lines::add, 1024);
        write(sink, "tail");
        sink.close();
        sink.finish();
        assertEquals(Collections.singletonList("tail"), lines);
        assertEquals("tail", buffer.toString());
    }
}
