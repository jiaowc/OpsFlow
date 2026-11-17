package com.opsflow.service.impl.pipeline;

import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.StageLogStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 阶段日志读取：优先读日志文件，按 offset 分段返回；无文件时回退到摘要。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineStageLogService {
    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private StageLogStore stageLogStore;

    public String getStageLog(Long buildJobId, Long stageId) {
        Map<String, Object> detail = getStageLogDetail(buildJobId, stageId, 0L);
        Object log = detail.get("log");
        return log != null ? String.valueOf(log) : "暂无日志";
    }

    public Map<String, Object> getStageLogDetail(Long buildJobId, Long stageId, long offset) {
        Map<String, Object> result = new HashMap<>();
        result.put("jobId", String.valueOf(buildJobId));
        result.put("stageId", String.valueOf(stageId));
        result.put("offset", Math.max(0L, offset));
        result.put("nextOffset", Math.max(0L, offset));
        result.put("eof", true);
        result.put("reset", false);
        result.put("size", 0L);

        BuildJobStage stage = buildJobStageMapper.selectById(stageId);
        if (stage == null || !buildJobId.equals(stage.getBuildJobId())) {
            result.put("log", "阶段不存在");
            result.put("status", "UNKNOWN");
            result.put("running", false);
            return result;
        }

        String status = stage.getStatus() != null ? stage.getStatus() : "PENDING";
        boolean running = "RUNNING".equalsIgnoreCase(status) || "IN_PROGRESS".equalsIgnoreCase(status);
        result.put("status", status);
        result.put("running", running);
        result.put("stepName", stage.getStepName());
        result.put("stepType", stage.getStepType());
        result.put("logPath", stage.getLogPath());

        // 优先读文件日志
        if (stage.getLogPath() != null && stageLogStore.exists(stage.getLogPath())) {
            StageLogStore.ReadResult read = stageLogStore.readFromOffset(
                stage.getLogPath(), offset, StageLogStore.DEFAULT_READ_CHUNK_BYTES);
            result.put("log", read.getContent() != null ? read.getContent() : "");
            result.put("offset", read.getOffset());
            result.put("nextOffset", read.getNextOffset());
            result.put("size", read.getSize());
            result.put("eof", read.isEof());
            result.put("reset", read.isReset());
            if (read.isReset()) {
                result.put("log", "");
            }
            return result;
        }

        // 无日志文件（已被保留策略清理或尚未产生）时回退到摘要
        String fallback;
        if (stage.getLogPreview() != null && !stage.getLogPreview().isEmpty()) {
            fallback = stage.getLogPreview();
        } else if (stage.getErrorMessage() != null && !stage.getErrorMessage().isEmpty()) {
            fallback = stage.getErrorMessage();
        } else if (running) {
            fallback = "步骤执行中，正在等待日志输出...";
        } else {
            fallback = "暂无日志";
        }
        byte[] bytes = fallback.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        result.put("size", (long) bytes.length);
        long start = Math.max(0L, offset);
        if (start >= bytes.length) {
            result.put("log", "");
            result.put("nextOffset", (long) bytes.length);
            result.put("eof", true);
            return result;
        }
        int len = (int) Math.min(StageLogStore.DEFAULT_READ_CHUNK_BYTES, bytes.length - start);
        result.put("log", new String(bytes, (int) start, len, java.nio.charset.StandardCharsets.UTF_8));
        result.put("nextOffset", start + len);
        result.put("eof", start + len >= bytes.length);
        return result;
    }
}
