package com.opsflow.integration.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段日志落盘与增量读取：页面每秒带着上次的偏移量来拉新内容，这里验证偏移量、
 * 中文字符边界和路径校验。
 */
class StageLogStoreTest {

    @TempDir
    Path tempDir;

    private StageLogStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new StageLogStore();
        ReflectionTestUtils.setField(store, "logDir", tempDir.resolve("logs").toString());
        store.init();
    }

    // ---------- 路径 ----------

    @Test
    void initCreatesBaseDirAndRelativePathIsJobSlashStage() {
        assertTrue(Files.isDirectory(store.getBaseDir()));
        assertEquals("12/34.log", store.relativePath(12L, 34L));
        assertEquals(store.getBaseDir().resolve("12/34.log"), store.absolutePath("12/34.log"));
    }

    @Test
    void blankPathResolvesToNull() {
        assertNull(store.absolutePath(null));
        assertNull(store.absolutePath("  "));
        assertFalse(store.exists(null));
        assertFalse(store.exists(""));
        assertEquals(0L, store.size(null));
    }

    @Test
    void pathOutsideLogDirIsRejected() {
        // log_path 存在库里，读取接口不能被它带出日志目录
        assertThrows(IllegalArgumentException.class, () -> store.absolutePath("../outside.log"));
        assertThrows(IllegalArgumentException.class, () -> store.absolutePath("1/../../outside.log"));
        assertThrows(IllegalArgumentException.class, () -> store.absolutePath("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.readFromOffset("../outside.log", 0, 100));
    }

    @Test
    void writeOutsideLogDirDoesNothing() {
        store.writeFull("../outside.log", "x");
        store.append("../outside.log", "x");
        assertFalse(Files.exists(tempDir.resolve("outside.log")));
    }

    // ---------- 写入 ----------

    @Test
    void appendCreatesParentDirAndAccumulates() {
        store.append("1/2.log", "第一行\n");
        store.append("1/2.log", "");
        store.append("1/2.log", null);
        store.append("1/2.log", "第二行\n");

        assertTrue(store.exists("1/2.log"));
        assertEquals("第一行\n第二行\n", store.readFromOffset("1/2.log", 0, 1024).getContent());
        assertEquals("第一行\n第二行\n".getBytes(StandardCharsets.UTF_8).length, store.size("1/2.log"));
    }

    @Test
    void writeFullReplacesContent() {
        store.append("1/2.log", "很长的旧内容很长的旧内容\n");
        store.writeFull("1/2.log", "new");
        assertEquals("new", store.readFromOffset("1/2.log", 0, 1024).getContent());

        store.writeFull("1/2.log", null);
        assertTrue(store.exists("1/2.log"));
        assertEquals(0L, store.size("1/2.log"));
    }

    // ---------- 增量读取 ----------

    @Test
    void missingFileReadsAsEmptyAndFinished() {
        StageLogStore.ReadResult result = store.readFromOffset("9/9.log", 50, 1024);
        assertEquals("", result.getContent());
        assertEquals(50L, result.getOffset());
        assertEquals(50L, result.getNextOffset());
        assertEquals(0L, result.getSize());
        assertTrue(result.isEof());
        assertFalse(result.isReset());
    }

    @Test
    void incrementalReadFollowsNextOffset() {
        store.append("1/2.log", "line-1\n");
        StageLogStore.ReadResult first = store.readFromOffset("1/2.log", 0, 1024);
        assertEquals("line-1\n", first.getContent());
        assertEquals(7L, first.getNextOffset());
        assertTrue(first.isEof());

        // 没有新内容：返回空串，偏移量不动
        StageLogStore.ReadResult idle = store.readFromOffset("1/2.log", first.getNextOffset(), 1024);
        assertEquals("", idle.getContent());
        assertEquals(7L, idle.getNextOffset());
        assertTrue(idle.isEof());

        store.append("1/2.log", "line-2\n");
        StageLogStore.ReadResult second = store.readFromOffset("1/2.log", idle.getNextOffset(), 1024);
        assertEquals("line-2\n", second.getContent());
        assertEquals(7L, second.getOffset());
        assertEquals(14L, second.getNextOffset());
        assertEquals(14L, second.getSize());
    }

    @Test
    void chunkSmallerThanFileIsNotEof() {
        store.writeFull("1/2.log", "0123456789");
        StageLogStore.ReadResult result = store.readFromOffset("1/2.log", 0, 4);
        assertEquals("0123", result.getContent());
        assertEquals(4L, result.getNextOffset());
        assertFalse(result.isEof());

        StageLogStore.ReadResult rest = store.readFromOffset("1/2.log", 4, 100);
        assertEquals("456789", rest.getContent());
        assertTrue(rest.isEof());
    }

    @Test
    void negativeOffsetAndNonPositiveChunkAreClamped() {
        store.writeFull("1/2.log", "abc");
        assertEquals("abc", store.readFromOffset("1/2.log", -5, 100).getContent());

        StageLogStore.ReadResult oneByte = store.readFromOffset("1/2.log", 0, 0);
        assertEquals("a", oneByte.getContent());
        assertEquals(1L, oneByte.getNextOffset());
    }

    @Test
    void offsetBeyondFileAsksCallerToReset() {
        // 重试时日志被重写变短，页面手里的偏移量已经超出文件
        store.writeFull("1/2.log", "short");
        StageLogStore.ReadResult result = store.readFromOffset("1/2.log", 999, 1024);
        assertTrue(result.isReset());
        assertFalse(result.isEof());
        assertEquals(0L, result.getOffset());
        assertEquals(0L, result.getNextOffset());
        assertEquals("", result.getContent());
        assertEquals(5L, result.getSize());
    }

    @Test
    void chunkEndingInsideChineseCharacterStopsAtCharacterBoundary() {
        // "ab中cd"：a b E4 B8 AD c d。读 4 字节会切在"中"的第 2 个字节上
        store.writeFull("1/2.log", "ab中cd");
        StageLogStore.ReadResult first = store.readFromOffset("1/2.log", 0, 4);
        assertEquals("ab", first.getContent());
        assertEquals(2L, first.getNextOffset());
        assertFalse(first.isEof());

        StageLogStore.ReadResult second = store.readFromOffset("1/2.log", first.getNextOffset(), 100);
        assertEquals("中cd", second.getContent());
        assertTrue(second.isEof());
    }

    @Test
    void offsetInsideChineseCharacterSkipsToNextCharacter() {
        store.writeFull("1/2.log", "ab中cd");
        // 偏移量 3 落在"中"的第 2 个字节：跳过残缺的字节，从 c 开始
        StageLogStore.ReadResult result = store.readFromOffset("1/2.log", 3, 100);
        assertEquals("cd", result.getContent());
        assertEquals(5L, result.getOffset());
        assertEquals(7L, result.getNextOffset());
    }

    @Test
    void readingChineseLogInSmallChunksLosesNothing() {
        String text = "构建开始\n下载依赖 100%\n打包完成 ✅ 用时 12s\n";
        store.writeFull("1/2.log", text);

        // 每次 5 字节：块会切在字符的各个位置上（含恰好切在首字节上），按 next_offset 一路读下去应当能拼回原文
        StringBuilder joined = new StringBuilder();
        long offset = 0;
        for (int i = 0; i < 200; i++) {
            StageLogStore.ReadResult chunk = store.readFromOffset("1/2.log", offset, 5);
            joined.append(chunk.getContent());
            offset = chunk.getNextOffset();
            if (chunk.isEof()) {
                break;
            }
        }
        assertEquals(text, joined.toString());
    }

    // ---------- 预览 ----------

    @Test
    void tailPreviewOfShortLogIsWholeLog() {
        assertEquals("", store.tailPreview("9/9.log", 100));
        store.writeFull("1/2.log", "构建成功\n");
        assertEquals("构建成功\n", store.tailPreview("1/2.log", 100));
    }

    @Test
    void tailPreviewOfLongLogKeepsTailWithEllipsis() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("第").append(i).append("行\n");
        }
        store.writeFull("1/2.log", sb.toString());

        String preview = store.tailPreview("1/2.log", 10);
        assertTrue(preview.startsWith("...\n"));
        assertEquals("...\n" + sb.substring(sb.length() - 10), preview);
    }

    // ---------- 清理 ----------

    @Test
    void deleteJobLogsRemovesOnlyThatJob() {
        store.append("1/2.log", "a");
        store.append("1/3.log", "b");
        store.append("5/6.log", "c");

        store.deleteJobLogs(1L);
        store.deleteJobLogs(null);
        store.deleteJobLogs(404L);

        assertFalse(Files.exists(store.getBaseDir().resolve("1")));
        assertTrue(store.exists("5/6.log"));
    }

    // ---------- 整段覆盖写 ----------

    @Test
    void writeFullOverwritesWithUtf8ContentAndTruncatesOldContent() throws Exception {
        String path = store.relativePath(7L, 8L);
        store.writeFull(path, "旧内容比较长，后面会被覆盖掉 0123456789\n");

        // 含 4 字节字符，长度超过写缓冲，验证流式编码与一次性编码结果一致
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("第 ").append(i).append(" 行 构建成功 \uD83D\uDE80\n");
        }
        String content = sb.toString();
        store.writeFull(path, content);
        assertEquals(content, new String(Files.readAllBytes(store.absolutePath(path)), StandardCharsets.UTF_8));

        // 4 字节字符（一对代理字符）正好跨在写入分块的边界上
        char[] filler = new char[8191];
        java.util.Arrays.fill(filler, 'a');
        String straddling = new String(filler) + "\uD83D\uDE80 尾部";
        store.writeFull(path, straddling);
        assertEquals(straddling, new String(Files.readAllBytes(store.absolutePath(path)), StandardCharsets.UTF_8));

        store.writeFull(path, "短");
        assertEquals("短", new String(Files.readAllBytes(store.absolutePath(path)), StandardCharsets.UTF_8));

        store.writeFull(path, null);
        assertEquals(0L, store.size(path));
    }

    // ---------- 保持文件打开连续追加 ----------

    @Test
    void appendsThroughAnOpenFileAreVisibleToReadersImmediately() {
        String path = store.relativePath(9L, 1L);
        store.writeFull(path, "开头\n");
        store.openForAppend(path);
        store.openForAppend(path);
        assertEquals(1, store.openLogCount());

        store.append(path, "第一行\n");
        // 不关闭文件也能读到，页面轮询依赖这一点
        assertEquals("开头\n第一行\n", store.readFromOffset(path, 0, 1024).getContent());
        assertEquals("开头\n第一行\n".getBytes(StandardCharsets.UTF_8).length, store.size(path));

        store.append(path, "第二行\n");
        assertEquals("...\n二行\n", store.tailPreview(path, 3));

        store.closeAppend(path);
        store.closeAppend(path);
        assertEquals(0, store.openLogCount());
        // 关闭后仍可追加（退回到每次单独打开文件）
        store.append(path, "收尾\n");
        assertEquals("开头\n第一行\n第二行\n收尾\n", store.readFromOffset(path, 0, 1024).getContent());
        assertEquals(store.absolutePath(path).toFile().length(), store.size(path));
    }

    @Test
    void writeFullWhileOpenReplacesContentAndLaterAppendsStillWork() {
        String path = store.relativePath(9L, 2L);
        store.openForAppend(path);
        store.append(path, "旧内容，比较长比较长比较长\n");

        store.writeFull(path, "新内容\n");
        assertEquals(0, store.openLogCount());
        store.append(path, "追加\n");

        assertEquals("新内容\n追加\n", store.readFromOffset(path, 0, 1024).getContent());
        assertEquals("新内容\n追加\n".getBytes(StandardCharsets.UTF_8).length, store.size(path));
    }

    @Test
    void closeJobLogsOnlyClosesFilesOfThatJob() {
        store.openForAppend(store.relativePath(1L, 1L));
        store.openForAppend(store.relativePath(1L, 2L));
        store.openForAppend(store.relativePath(11L, 1L));
        assertEquals(3, store.openLogCount());

        store.closeJobLogs(1L);
        assertEquals(1, store.openLogCount());

        store.deleteJobLogs(11L);
        assertEquals(0, store.openLogCount());
        assertFalse(store.exists(store.relativePath(11L, 1L)));

        store.openForAppend(store.relativePath(2L, 1L));
        store.closeAll();
        assertEquals(0, store.openLogCount());
    }

    @Test
    void openingAnInvalidPathIsIgnored() {
        store.openForAppend(null);
        store.openForAppend("  ");
        assertEquals(0, store.openLogCount());
        assertThrows(IllegalArgumentException.class, () -> store.absolutePath("../x.log"));
        store.openForAppend("../x.log");
        assertEquals(0, store.openLogCount());
    }
}
