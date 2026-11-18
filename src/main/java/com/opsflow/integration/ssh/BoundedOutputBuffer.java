package com.opsflow.integration.ssh;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 有上限的命令输出缓冲：保留开头和结尾，中间超出的部分丢弃，避免失控的大输出撑爆内存。
 * <p>
 * 按字节保存、取用时整体按 UTF-8 解码；发生丢弃时会修正两处切口，不会把多字节字符截成乱码。
 * 默认上限合计 20MB，与阶段日志文件的大小上限一致，正常构建日志不会被截短。
 * </p>
 */
public class BoundedOutputBuffer {

    public static final int DEFAULT_HEAD_BYTES = 2 * 1024 * 1024;
    public static final int DEFAULT_TAIL_BYTES = 18 * 1024 * 1024;

    private final int headLimit;
    private final int tailLimit;
    private final ByteArrayOutputStream head = new ByteArrayOutputStream();

    /** 结尾部分的环形缓冲；容量按需增长到 tailLimit，增长阶段 tailStart 恒为 0 */
    private byte[] tail = new byte[0];
    private int tailStart;
    private int tailLength;
    private long droppedBytes;

    public BoundedOutputBuffer() {
        this(DEFAULT_HEAD_BYTES, DEFAULT_TAIL_BYTES);
    }

    public BoundedOutputBuffer(int headLimit, int tailLimit) {
        if (headLimit < 0 || tailLimit <= 0) {
            throw new IllegalArgumentException("headLimit 不能为负，tailLimit 必须大于 0");
        }
        this.headLimit = headLimit;
        this.tailLimit = tailLimit;
    }

    public synchronized void write(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return;
        }
        int headRoom = headLimit - head.size();
        if (headRoom > 0) {
            int take = Math.min(headRoom, length);
            head.write(data, offset, take);
            offset += take;
            length -= take;
            if (length == 0) {
                return;
            }
        }
        writeTail(data, offset, length);
    }

    /** 追加一行文本（自动补换行） */
    public void appendLine(String line) {
        byte[] bytes = ((line == null ? "" : line) + "\n").getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    private void writeTail(byte[] data, int offset, int length) {
        if (length >= tailLimit) {
            // 这一次写入就超过了整个结尾窗口：只留它的最后 tailLimit 字节
            droppedBytes += (long) tailLength + (length - tailLimit);
            if (tail.length < tailLimit) {
                tail = new byte[tailLimit];
            }
            System.arraycopy(data, offset + length - tailLimit, tail, 0, tailLimit);
            tailStart = 0;
            tailLength = tailLimit;
            return;
        }
        long needed = (long) tailLength + length;
        if (needed > tail.length && tail.length < tailLimit) {
            int newCapacity = (int) Math.min(tailLimit, Math.max(needed, tail.length * 2L));
            byte[] grown = new byte[newCapacity];
            System.arraycopy(tail, 0, grown, 0, tailLength);
            tail = grown;
        }
        int overflow = (int) (needed - tail.length);
        if (overflow > 0) {
            tailStart = (tailStart + overflow) % tail.length;
            tailLength -= overflow;
            droppedBytes += overflow;
        }
        int writeAt = (tailStart + tailLength) % tail.length;
        int firstPart = Math.min(length, tail.length - writeAt);
        System.arraycopy(data, offset, tail, writeAt, firstPart);
        if (firstPart < length) {
            System.arraycopy(data, offset + firstPart, tail, 0, length - firstPart);
        }
        tailLength += length;
    }

    /** 被丢弃的字节数；0 表示输出完整 */
    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    /** 当前保留的字节数 */
    public synchronized int size() {
        return head.size() + tailLength;
    }

    @Override
    public synchronized String toString() {
        byte[] headBytes = head.toByteArray();
        byte[] tailBytes = tailBytes();
        if (droppedBytes == 0) {
            byte[] all = new byte[headBytes.length + tailBytes.length];
            System.arraycopy(headBytes, 0, all, 0, headBytes.length);
            System.arraycopy(tailBytes, 0, all, headBytes.length, tailBytes.length);
            return new String(all, StandardCharsets.UTF_8);
        }
        int headEnd = completeUtf8Length(headBytes);
        int tailBegin = 0;
        while (tailBegin < tailBytes.length && isContinuation(tailBytes[tailBegin])) {
            tailBegin++;
        }
        return new String(headBytes, 0, headEnd, StandardCharsets.UTF_8)
            + "\n...... [输出过长，已省略中间约 " + droppedBytes + " 字节] ......\n"
            + new String(tailBytes, tailBegin, tailBytes.length - tailBegin, StandardCharsets.UTF_8);
    }

    private byte[] tailBytes() {
        byte[] out = new byte[tailLength];
        int firstPart = Math.min(tailLength, tail.length - tailStart);
        System.arraycopy(tail, tailStart, out, 0, firstPart);
        if (firstPart < tailLength) {
            System.arraycopy(tail, 0, out, firstPart, tailLength - firstPart);
        }
        return out;
    }

    /** 去掉末尾不完整的 UTF-8 字符后的长度 */
    private static int completeUtf8Length(byte[] bytes) {
        int i = bytes.length - 1;
        int back = 0;
        while (i >= 0 && back < 3 && isContinuation(bytes[i])) {
            i--;
            back++;
        }
        if (i < 0) {
            return bytes.length;
        }
        int lead = bytes[i] & 0xFF;
        int expected;
        if (lead < 0x80) {
            expected = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            return bytes.length;
        }
        return i + expected > bytes.length ? i : bytes.length;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}
