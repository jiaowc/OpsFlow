package com.opsflow.integration.ssh;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 远程命令一路输出（stdout 或 stderr）的接收端：SSH 连接收到数据后直接写进来，
 * 这里把数据放进有上限的缓冲，并按行回调。
 * <p>
 * 写入发生在 SSH 连接的读线程上，等待命令结束的线程在最后读取结果，两边用同一把锁互斥；
 * stdout 和 stderr 共用这把锁和同一个失败记录，回调不会并发执行。
 * <p>
 * 按字节切行，整行再按 UTF-8 解码，避免多字节字符被数据包边界截断成乱码。
 */
final class CommandOutputSink extends OutputStream {

    private final Object lock;
    private final BoundedOutputBuffer buffer;
    private final Consumer<String> lineListener;
    private final int maxLineBytes;
    private final AtomicReference<RuntimeException> listenerFailure;
    private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
    private boolean finished;

    /**
     * @param lock            stdout、stderr 共用的锁
     * @param lineListener    按行回调，可为 null
     * @param maxLineBytes    单行超过该长度时强制切分
     * @param listenerFailure 回调抛出的第一个异常；记录后不再回调，由等待方决定如何处理
     */
    CommandOutputSink(Object lock, BoundedOutputBuffer buffer, Consumer<String> lineListener,
                      int maxLineBytes, AtomicReference<RuntimeException> listenerFailure) {
        this.lock = lock;
        this.buffer = buffer;
        this.lineListener = lineListener;
        this.maxLineBytes = maxLineBytes;
        this.listenerFailure = listenerFailure;
    }

    @Override
    public void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] data, int offset, int length) {
        synchronized (lock) {
            if (finished || length <= 0) {
                return;
            }
            buffer.write(data, offset, length);
            if (lineListener == null || listenerFailure.get() != null) {
                return;
            }
            try {
                for (int i = offset; i < offset + length; i++) {
                    byte b = data[i];
                    if (b == '\n') {
                        emitLine();
                    } else if (b != '\r') {
                        lineBuf.write(b);
                        if (lineBuf.size() >= maxLineBytes) {
                            emitLine();
                        }
                    }
                }
            } catch (RuntimeException e) {
                // 不能把异常抛给 SSH 连接的读线程（会直接断开连接），记下来交给等待方
                listenerFailure.compareAndSet(null, e);
            }
        }
    }

    /**
     * 命令结束（或超时）后调用：把最后一段没有换行的内容作为一行回调，此后到达的数据一律丢弃。
     */
    void finish() {
        synchronized (lock) {
            if (finished) {
                return;
            }
            finished = true;
            if (lineListener != null && listenerFailure.get() == null && lineBuf.size() > 0) {
                emitLine();
            }
        }
    }

    /** 不再接收数据，也不回调残留的半行；用于异常或被中断的退出路径 */
    void discard() {
        synchronized (lock) {
            finished = true;
        }
    }

    /** 连接关闭时会被调用；缓冲由使用方读取，这里不需要释放任何资源 */
    @Override
    public void close() {
    }

    private void emitLine() {
        String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
        lineBuf.reset();
        lineListener.accept(line);
    }
}
