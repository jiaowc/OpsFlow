package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.AuditLogMapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.model.AuditLog;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.service.BuildRetentionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保留策略：
 * <ul>
 *   <li>完整阶段日志文件保留 log-days 天，之后删除文件，只保留末尾摘要（log_preview）；</li>
 *   <li>构建记录保留 record-days 天；超期且不在任务最近 keep-latest-per-task 次之内的记录删除。
 *       任务组首条记录（流水线任务 ID）与上线任务产生的 CD 记录始终保留；</li>
 *   <li>操作审计保留 audit-days 天。</li>
 * </ul>
 * 任一天数 ≤ 0 表示不清理该项。
 */
@Slf4j
@Service
public class BuildRetentionServiceImpl implements BuildRetentionService {

    private static final int BATCH_SIZE = 500;
    private static final String PREVIEW_MARKER = "[完整日志已按保留策略清理，以下为末尾摘要]\n";
    private static final int PREVIEW_MAX = 4000;
    private static final List<String> RUNNING_STATUSES =
            Arrays.asList(BuildStatus.PENDING, BuildStatus.BUILDING, BuildStatus.DEPLOYING);

    @Value("${opsflow.retention.enabled:true}")
    private boolean enabled;

    @Value("${opsflow.retention.log-days:30}")
    private int logDays;

    @Value("${opsflow.retention.record-days:180}")
    private int recordDays;

    @Value("${opsflow.retention.keep-latest-per-task:50}")
    private int keepLatestPerTask;

    @Value("${opsflow.retention.audit-days:180}")
    private int auditDays;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private StageLogStore stageLogStore;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(cron = "${opsflow.retention.cron:0 30 3 * * ?}")
    public void scheduledCleanup() {
        if (!enabled) {
            return;
        }
        Map<String, Object> result = runCleanup(false);
        log.info("保留策略清理完成: {}", result);
    }

    @Override
    public Map<String, Object> runCleanup(boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dryRun", dryRun);
        result.put("logDays", logDays);
        result.put("recordDays", recordDays);
        result.put("keepLatestPerTask", keepLatestPerTask);
        result.put("auditDays", auditDays);
        if (!running.compareAndSet(false, true)) {
            result.put("message", "清理任务正在执行中");
            return result;
        }
        try {
            result.put("deletedRecords", cleanupRecords(dryRun));
            result.put("clearedLogJobs", cleanupLogs(dryRun));
            result.put("deletedAuditLogs", cleanupAudit(dryRun));
        } finally {
            running.set(false);
        }
        return result;
    }

    /**
     * 先删记录（连带其日志），再清理剩余记录的日志文件，避免重复处理
     */
    private int cleanupRecords(boolean dryRun) {
        if (recordDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recordDays);
        List<BuildJob> candidates = buildJobMapper.selectList(new LambdaQueryWrapper<BuildJob>()
                .select(BuildJob::getId, BuildJob::getTaskName, BuildJob::getServiceId,
                        BuildJob::getEnvId, BuildJob::getPipelineTemplateId)
                .isNull(BuildJob::getDeployTaskId)
                .notIn(BuildJob::getStatus, RUNNING_STATUSES)
                .lt(BuildJob::getCreateTime, cutoff));
        if (candidates.isEmpty()) {
            return 0;
        }

        Map<String, Set<Long>> keepByGroup = new HashMap<>();
        int deleted = 0;
        for (BuildJob job : candidates) {
            Set<Long> keep = keepByGroup.computeIfAbsent(groupKey(job), k -> keepIdsOfGroup(job));
            if (keep.contains(job.getId())) {
                continue;
            }
            if (!dryRun) {
                buildJobStageMapper.delete(new LambdaQueryWrapper<BuildJobStage>()
                        .eq(BuildJobStage::getBuildJobId, job.getId()));
                stageLogStore.deleteJobLogs(job.getId());
                buildJobMapper.deleteById(job.getId());
            }
            deleted++;
        }
        return deleted;
    }

    /**
     * 任务组的首条记录（任务 ID）+ 最近 N 次
     */
    private Set<Long> keepIdsOfGroup(BuildJob job) {
        Set<Long> keep = new HashSet<>();
        BuildJob anchor = buildJobMapper.selectOne(groupWrapper(job).orderByAsc(BuildJob::getId).last("LIMIT 1"));
        if (anchor != null) {
            keep.add(anchor.getId());
        }
        if (keepLatestPerTask > 0) {
            buildJobMapper.selectList(groupWrapper(job).orderByDesc(BuildJob::getCreateTime)
                            .last("LIMIT " + keepLatestPerTask))
                    .forEach(j -> keep.add(j.getId()));
        }
        return keep;
    }

    /**
     * 与 PipelineRunServiceImpl#buildTaskGroupWrapper 的分组规则一致
     */
    private LambdaQueryWrapper<BuildJob> groupWrapper(BuildJob job) {
        LambdaQueryWrapper<BuildJob> w = new LambdaQueryWrapper<BuildJob>()
                .select(BuildJob::getId)
                .isNull(BuildJob::getDeployTaskId);
        if (job.getTaskName() != null && !job.getTaskName().trim().isEmpty()) {
            w.eq(BuildJob::getTaskName, job.getTaskName().trim());
        } else {
            w.and(x -> x.isNull(BuildJob::getTaskName).or().eq(BuildJob::getTaskName, ""));
        }
        if (job.getServiceId() != null) {
            w.eq(BuildJob::getServiceId, job.getServiceId());
        }
        if (job.getEnvId() != null) {
            w.eq(BuildJob::getEnvId, job.getEnvId());
        }
        if (job.getPipelineTemplateId() != null) {
            w.eq(BuildJob::getPipelineTemplateId, job.getPipelineTemplateId());
        }
        return w;
    }

    private static String groupKey(BuildJob job) {
        return (job.getTaskName() == null ? "" : job.getTaskName().trim()) + "|" + job.getServiceId()
                + "|" + job.getEnvId() + "|" + job.getPipelineTemplateId();
    }

    private int cleanupLogs(boolean dryRun) {
        if (logDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(logDays);
        int cleared = 0;
        long lastId = 0;
        while (true) {
            List<BuildJob> jobs = buildJobMapper.selectList(new LambdaQueryWrapper<BuildJob>()
                    .select(BuildJob::getId)
                    .notIn(BuildJob::getStatus, RUNNING_STATUSES)
                    .lt(BuildJob::getCreateTime, cutoff)
                    .gt(BuildJob::getId, lastId)
                    .inSql(BuildJob::getId, "SELECT build_job_id FROM build_job_stage WHERE log_path IS NOT NULL")
                    .orderByAsc(BuildJob::getId)
                    .last("LIMIT " + BATCH_SIZE));
            if (jobs.isEmpty()) {
                break;
            }
            for (BuildJob job : jobs) {
                if (!dryRun) {
                    clearJobLogs(job.getId());
                }
                cleared++;
                lastId = job.getId();
            }
            if (jobs.size() < BATCH_SIZE) {
                break;
            }
        }
        return cleared;
    }

    private void clearJobLogs(Long jobId) {
        List<BuildJobStage> stages = buildJobStageMapper.selectList(new LambdaQueryWrapper<BuildJobStage>()
                .select(BuildJobStage::getId, BuildJobStage::getLogPreview)
                .eq(BuildJobStage::getBuildJobId, jobId));
        stageLogStore.deleteJobLogs(jobId);
        for (BuildJobStage stage : stages) {
            String preview = stage.getLogPreview();
            if (preview != null && !preview.isEmpty() && !preview.startsWith(PREVIEW_MARKER)) {
                preview = PREVIEW_MARKER + preview;
                if (preview.length() > PREVIEW_MAX) {
                    preview = PREVIEW_MARKER + preview.substring(preview.length() - (PREVIEW_MAX - PREVIEW_MARKER.length()));
                }
            }
            buildJobStageMapper.update(null, new LambdaUpdateWrapper<BuildJobStage>()
                    .eq(BuildJobStage::getId, stage.getId())
                    .set(BuildJobStage::getLogPath, null)
                    .set(BuildJobStage::getLogPreview, preview));
        }
    }

    private int cleanupAudit(boolean dryRun) {
        if (auditDays <= 0) {
            return 0;
        }
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<AuditLog>()
                .lt(AuditLog::getCreateTime, LocalDateTime.now().minusDays(auditDays));
        if (dryRun) {
            return Math.toIntExact(auditLogMapper.selectCount(w));
        }
        return auditLogMapper.delete(w);
    }
}
