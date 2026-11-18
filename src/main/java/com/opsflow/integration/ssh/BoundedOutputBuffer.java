package com.opsflow.integration.ssh;

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
    /** 开头部分；容量按需增长到 headLimit */
    private byte[] head = new byte[0];
    private int headLength;

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
        int headRoom = headLimit - headLength;
        if (headRoom > 0) {
            int take = Math.min(headRoom, length);
            if (headLength + take > head.length) {
                int newCapacity = (int) Math.min(headLimit, Math.max((long) headLength + take, head.length * 2L));
                head = java.util.Arrays.copyOf(head, newCapacity);
            }
            System.arraycopy(data, offset, head, headLength, take);
            headLength += take;
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
        return headLength + tailLength;
    }

    /**
     * 尽量直接从内部缓冲解码，不为开头、结尾各建一份副本（20MB 输出时能少分配约 20MB）。
     */
    @Override
    public synchronized String toString() {
        if (droppedBytes == 0) {
            // 没有丢弃时结尾部分从未回绕（tailStart 为 0）；开头和结尾可能共用一个多字节字符，要拼起来一起解码
            byte[] all = new byte[headLength + tailLength];
            System.arraycopy(head, 0, all, 0, headLength);
            System.arraycopy(tail, tailStart, all, headLength, tailLength);
            return new String(all, StandardCharsets.UTF_8);
        }
        linearizeTail();
        int headEnd = completeUtf8Length(head, headLength);
        int tailBegin = 0;
        while (tailBegin < tailLength && isContinuation(tail[tailBegin])) {
            tailBegin++;
        }
        return new String(head, 0, headEnd, StandardCharsets.UTF_8)
            + "\n...... [输出过长，已省略中间约 " + droppedBytes + " 字节] ......\n"
            + new String(tail, tailBegin, tailLength - tailBegin, StandardCharsets.UTF_8);
    }

    /** 把环形缓冲原地转成从下标 0 开始的顺序排列（三次反转，不额外分配），之后可直接按区间解码 */
    private void linearizeTail() {
        if (tailStart == 0) {
            return;
        }
        reverse(tail, 0, tailStart);
        reverse(tail, tailStart, tail.length);
        reverse(tail, 0, tail.length);
        tailStart = 0;
    }

    private static void reverse(byte[] bytes, int from, int to) {
        for (int i = from, j = to - 1; i < j; i++, j--) {
            byte tmp = bytes[i];
            bytes[i] = bytes[j];
            bytes[j] = tmp;
        }
    }

    /** 去掉末尾不完整的 UTF-8 字符后的长度 */
    private static int completeUtf8Length(byte[] bytes, int length) {
        int i = length - 1;
        int back = 0;
        while (i >= 0 && back < 3 && isContinuation(bytes[i])) {
            i--;
            back++;
        }
        if (i < 0) {
            return length;
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
            return length;
        }
        return i + expected > length ? i : length;
    }

    private static boolean isContinuation(byte b) {
        return (b & 0xC0) == 0x80;
    }
}
