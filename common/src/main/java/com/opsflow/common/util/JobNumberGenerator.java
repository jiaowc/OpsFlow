package com.opsflow.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 任务编号生成器
 */
public class JobNumberGenerator {
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    
    /**
     * 生成构建任务编号
     * 格式: BUILD-20231230120000-001
     */
    public static String generateBuildJobNumber() {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        int random = (int) (Math.random() * 1000);
        return String.format("BUILD-%s-%03d", timestamp, random);
    }
}



