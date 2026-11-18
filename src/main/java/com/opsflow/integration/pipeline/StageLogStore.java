package com.opsflow.integration.pipeline;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流水线阶段日志文件存储（方案 B：完整日志落盘，DB 仅保留路径与预览）。
 */
@Slf4j
@Component
public class StageLogStore {

    public static final int DEFAULT_PREVIEW_CHARS = 4096;
    public static final int DEFAULT_READ_CHUNK_BYTES = 128 * 1024;
    private static final int WRITE_CHUNK_CHARS = 8192;

    @Value("${opsflow.pipeline.log-dir:./data/pipeline-logs}")
    private String logDir;

    private Path baseDir;

    /**
     * 正在追加写入的日志文件（相对路径 -> 已打开的文件）。
     * 步骤运行期间每行输出都会追加一次，保持文件打开可以省掉每行一次的打开、关闭和查大小。
     * 写入不经过用户态缓冲，每次追加后读取方立即可见。
     */
    private final Map<String, OpenLog> openLogs = new ConcurrentHashMap<>();

    private static final class OpenLog {
        private final OutputStream out;
        private volatile long size;

        private OpenLog(OutputStream out, long size) {
            this.out = out;
            this.size = size;
        }
    }

    @PostConstruct
    public void init() throws IOException {
        baseDir = Paths.get(logDir).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("流水线阶段日志目录: {}", baseDir);
    }

    public Path getBaseDir() {
        return baseDir;
    }

    /**
     * 相对路径，写入 DB 的 log_path
     */
    public String relativePath(Long jobId, Long stageId) {
        return jobId + "/" + stageId + ".log";
    }

    public Path absolutePath(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return null;
        }
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法日志路径: " + relativePath);
        }
        return resolved;
    }

    public void ensureParent(String relativePath) throws IOException {
        Path path = absolutePath(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
    }

    /**
     * 保持日志文件打开，供后续 {@link #append} 连续追加；用完必须 {@link #closeAppend}（或 {@link #closeJobLogs}）。
     * 重复调用无影响；打开失败时不报错，append 会退回到每次单独打开文件的方式。
     */
    public synchronized void openForAppend(String relativePath) {
        if (relativePath == null || relativePath.trim().isEmpty() || openLogs.containsKey(relativePath)) {
            return;
        }
        try {
            ensureParent(relativePath);
            Path path = absolutePath(relativePath);
            OutputStream out = Files.newOutputStream(path,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            openLogs.put(relativePath, new OpenLog(out, Files.size(path)));
        } catch (Exception e) {
            log.warn("打开阶段日志失败: {} - {}", relativePath, e.getMessage());
        }
    }

    /** 关闭 {@link #openForAppend} 打开的文件；没有打开时无影响 */
    public synchronized void closeAppend(String relativePath) {
        OpenLog open = relativePath == null ? null : openLogs.remove(relativePath);
        if (open == null) {
            return;
        }
        try {
            open.out.close();
        } catch (IOException e) {
            log.warn("关闭阶段日志失败: {} - {}", relativePath, e.getMessage());
        }
    }

    /** 关闭某个任务下所有还开着的日志文件（任务结束时兜底，防止步骤异常中断后文件一直开着） */
    public synchronized void closeJobLogs(Long jobId) {
        if (jobId == null) {
            return;
        }
        String prefix = jobId + "/";
        for (String relativePath : new ArrayList<>(openLogs.keySet())) {
            if (relativePath.startsWith(prefix)) {
                closeAppend(relativePath);
            }
        }
    }

    @PreDestroy
    public synchronized void closeAll() {
        for (String relativePath : new ArrayList<>(openLogs.keySet())) {
            closeAppend(relativePath);
        }
    }

    /** 当前有多少个日志文件保持打开 */
    public int openLogCount() {
        return openLogs.size();
    }

    public synchronized void writeFull(String relativePath, String content) {
        // 整段覆盖前先关掉追加用的文件，覆盖后需要继续追加的由调用方重新打开
        closeAppend(relativePath);
        try {
            ensureParent(relativePath);
            Path path = absolutePath(relativePath);
            // 分块编码写入：不把整段日志再转成一份字节数组或字符数组（完整日志可达 20MB）
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(path,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
                    StandardCharsets.UTF_8)) {
                int length = content == null ? 0 : content.length();
                char[] chunk = new char[Math.min(length, WRITE_CHUNK_CHARS)];
                for (int offset = 0; offset < length; offset += chunk.length) {
                    int end = Math.min(length, offset + chunk.length);
                    content.getChars(offset, end, chunk, 0);
                    writer.write(chunk, 0, end - offset);
                }
            }
        } catch (Exception e) {
            log.warn("写入阶段日志失败: {} - {}", relativePath, e.getMessage());
        }
    }

    public synchronized void append(String relativePath, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
        OpenLog open = openLogs.get(relativePath);
        if (open != null) {
            try {
                open.out.write(bytes);
                open.size += bytes.length;
                return;
            } catch (IOException e) {
                // 已打开的文件写不进去（如文件被删）：关掉它，退回到单独打开文件再写一次
                log.warn("追加阶段日志失败，改为重新打开文件: {} - {}", relativePath, e.getMessage());
                closeAppend(relativePath);
            }
        }
        try {
            ensureParent(relativePath);
            Path path = absolutePath(relativePath);
            Files.write(path, bytes,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (Exception e) {
            log.warn("追加阶段日志失败: {} - {}", relativePath, e.getMessage());
        }
    }

    public boolean exists(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return false;
        }
        Path path = absolutePath(relativePath);
        return path != null && Files.isRegularFile(path);
    }

    public long size(String relativePath) {
        OpenLog open = relativePath == null ? null : openLogs.get(relativePath);
        if (open != null) {
            return open.size;
        }
        try {
            Path path = absolutePath(relativePath);
            if (path == null || !Files.isRegularFile(path)) {
                return 0L;
            }
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 从字节 offset 读取一段日志（UTF-8，尽量按字符边界对齐）。
     */
    public ReadResult readFromOffset(String relativePath, long offset, int maxBytes) {
        ReadResult result = new ReadResult();
        result.setOffset(Math.max(0L, offset));
        result.setNextOffset(result.getOffset());
        result.setEof(true);
        result.setSize(0L);
        result.setContent("");

        Path path = absolutePath(relativePath);
        if (path == null || !Files.isRegularFile(path)) {
            return result;
        }

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long size = raf.length();
            result.setSize(size);
            long start = Math.max(0L, offset);
            if (start > size) {
                // 文件被重写变短，调用方应重置
                result.setOffset(0L);
                result.setNextOffset(0L);
                result.setEof(false);
                result.setReset(true);
                return result;
            }
            if (start == size) {
                result.setEof(true);
                result.setNextOffset(size);
                return result;
            }

            int toRead = (int) Math.min(Math.max(1, maxBytes), size - start);
            raf.seek(start);
            byte[] buf = new byte[toRead];
            int n = raf.read(buf);
            if (n <= 0) {
                result.setEof(start >= size);
                result.setNextOffset(start);
                return result;
            }

            // 若不是从 0 开始且首字节是 UTF-8 续字节，向前对齐（最多回退 3 字节）
            int dataStart = 0;
            long effectiveStart = start;
            if (start > 0) {
                while (dataStart < n && dataStart < 3 && (buf[dataStart] & 0xC0) == 0x80) {
                    dataStart++;
                }
                effectiveStart = start + dataStart;
            }

            // 末尾若截断多字节字符，回退到完整字符边界
            int dataEnd = n;
            if (start + n < size) {
                int back = 0;
                while (dataEnd - back > dataStart && back < 3 && (buf[dataEnd - 1 - back] & 0xC0) == 0x80) {
                    back++;
                }
                // back 为 0 时最后一个字节本身可能就是多字节字符的首字节，同样要退回去
                if (dataEnd - back > dataStart) {
                    int lead = buf[dataEnd - 1 - back] & 0xFF;
                    int need;
                    if (lead >= 0xF0) {
                        need = 4;
                    } else if (lead >= 0xE0) {
                        need = 3;
                    } else if (lead >= 0xC0) {
                        need = 2;
                    } else {
                        need = 1;
                    }
                    if (back + 1 < need) {
                        dataEnd = dataEnd - back - 1;
                    }
                }
            }

            if (dataEnd <= dataStart) {
                // 极端情况：整段都是不完整字符，跳过 1 字节继续
                result.setNextOffset(Math.min(size, start + 1));
                result.setEof(result.getNextOffset() >= size);
                result.setContent("");
                return result;
            }

            String content = new String(buf, dataStart, dataEnd - dataStart, StandardCharsets.UTF_8);
            long next = start + dataEnd;
            result.setContent(content);
            result.setNextOffset(next);
            result.setEof(next >= size);
            result.setOffset(effectiveStart);
            return result;
        } catch (Exception e) {
            log.warn("读取阶段日志失败: {} - {}", relativePath, e.getMessage());
            result.setContent("读取日志失败: " + e.getMessage());
            return result;
        }
    }

    public String tailPreview(String relativePath, int maxChars) {
        long size = size(relativePath);
        if (size <= 0) {
            return "";
        }
        int chars = maxChars > 0 ? maxChars : DEFAULT_PREVIEW_CHARS;
        // 估算字节：按 4 倍字符取尾部再截断
        long byteOffset = Math.max(0L, size - (long) chars * 4);
        ReadResult read = readFromOffset(relativePath, byteOffset, (int) Math.min(Integer.MAX_VALUE, size - byteOffset));
        String content = read.getContent() != null ? read.getContent() : "";
        if (content.length() > chars) {
            content = content.substring(content.length() - chars);
        }
        if (byteOffset > 0 && !content.isEmpty()) {
            return "...\n" + content;
        }
        return content;
    }

    public void deleteJobLogs(Long jobId) {
        if (jobId == null) {
            return;
        }
        closeJobLogs(jobId);
        try {
            Path dir = baseDir.resolve(String.valueOf(jobId));
            if (!Files.isDirectory(dir)) {
                return;
            }
            Files.list(dir).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
            Files.deleteIfExists(dir);
        } catch (Exception e) {
            log.warn("清理任务日志目录失败: jobId={} - {}", jobId, e.getMessage());
        }
    }

    @Data
    public static class ReadResult {
        private long offset;
        private long nextOffset;
        private long size;
        private boolean eof;
        private boolean reset;
        private String content;
    }
}
