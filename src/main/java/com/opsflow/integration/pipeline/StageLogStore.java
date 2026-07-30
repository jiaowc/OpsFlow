package com.opsflow.integration.pipeline;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 流水线阶段日志文件存储（方案 B：完整日志落盘，DB 仅保留路径与预览）。
 */
@Slf4j
@Component
public class StageLogStore {

    public static final int DEFAULT_PREVIEW_CHARS = 4096;
    public static final int DEFAULT_READ_CHUNK_BYTES = 128 * 1024;

    @Value("${opsflow.pipeline.log-dir:./data/pipeline-logs}")
    private String logDir;

    private Path baseDir;

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

    public synchronized void writeFull(String relativePath, String content) {
        try {
            ensureParent(relativePath);
            Path path = absolutePath(relativePath);
            byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            log.warn("写入阶段日志失败: {} - {}", relativePath, e.getMessage());
        }
    }

    public synchronized void append(String relativePath, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        try {
            ensureParent(relativePath);
            Path path = absolutePath(relativePath);
            Files.write(path, chunk.getBytes(StandardCharsets.UTF_8),
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
                if (back > 0 && dataEnd - back > dataStart) {
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
