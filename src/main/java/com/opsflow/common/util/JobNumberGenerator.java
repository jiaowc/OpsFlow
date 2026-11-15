package com.opsflow.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务编号生成器
 */
public class JobNumberGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final int MAX_SEQ_PER_SECOND = 999;

    private static String lastTimestamp = "";

    private static int seq;

    private JobNumberGenerator() {
    }

    /**
     * 生成构建任务编号，同一秒内按序号递增，单实例内不重复。
     * 格式: BUILD-20231230120000-001
     */
    public static synchronized String generateBuildJobNumber() {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        if (timestamp.equals(lastTimestamp)) {
            seq++;
            if (seq > MAX_SEQ_PER_SECOND) {
                timestamp = waitNextSecond(timestamp);
                seq = 1;
            }
        } else {
            seq = 1;
        }
        lastTimestamp = timestamp;
        return String.format("BUILD-%s-%03d", timestamp, seq);
    }

    private static String waitNextSecond(String current) {
        String timestamp = current;
        while (timestamp.equals(current)) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            timestamp = LocalDateTime.now().format(FORMATTER);
        }
        return timestamp;
    }
}
