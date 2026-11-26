package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.GitRepoKey;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.spi.ProdDeployPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Git 推送自动构建：开关维护与按推送事件触发重建。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineWebhookTriggerService {
    /** 全局 Git Webhook 密钥；为空表示 /api/webhook/git 未启用 */
    @org.springframework.beans.factory.annotation.Value("${opsflow.webhook.secret:}")
    private String webhookSecret;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private ProdDeployPolicy prodDeployPolicy;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private PipelineWebhookTriggerMapper pipelineWebhookTriggerMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineRebuildService rebuildService;

    public boolean isWebhookEnabled(Long anchorJobId) {
        if (anchorJobId == null) {
            return false;
        }
        PipelineWebhookTrigger trigger = pipelineWebhookTriggerMapper.selectOne(
                new LambdaQueryWrapper<PipelineWebhookTrigger>()
                        .eq(PipelineWebhookTrigger::getAnchorJobId, anchorJobId)
                        .last("LIMIT 1"));
        return trigger != null && Integer.valueOf(1).equals(trigger.getEnabled());
    }

    public void setWebhookEnabled(Long jobId, boolean enabled) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(ref);
        projectAccessService.assertCanOperateService(ref.getServiceId());
        if (ref.getDeployTaskId() != null) {
            throw new BusinessException("上线任务产生的流水线不支持自动构建");
        }
        BuildJob anchor = support.resolveAnchorJob(ref);
        if (enabled) {
            if (webhookSecret == null || webhookSecret.trim().isEmpty()) {
                throw new BusinessException("系统未配置 Webhook 密钥（OPSFLOW_WEBHOOK_SECRET），推送自动构建不会生效，请先联系管理员配置");
            }
            Env env = anchor.getEnvId() != null ? envMapper.selectById(anchor.getEnvId()) : null;
            prodDeployPolicy.checkDirectDeploy(env, ProdDeployPolicy.Trigger.WEBHOOK_ENABLE);
            if ("tag".equals(support.configuredGitType(support.resolveLatestJobInGroup(anchor)))) {
                throw new BusinessException("按 Tag 构建的任务不支持推送自动构建，请改为分支");
            }
        }
        PipelineWebhookTrigger existing = pipelineWebhookTriggerMapper.selectOne(
                new LambdaQueryWrapper<PipelineWebhookTrigger>()
                        .eq(PipelineWebhookTrigger::getAnchorJobId, anchor.getId())
                        .last("LIMIT 1"));
        if (existing == null) {
            if (!enabled) {
                return;
            }
            PipelineWebhookTrigger trigger = new PipelineWebhookTrigger();
            trigger.setAnchorJobId(anchor.getId());
            trigger.setEnabled(1);
            trigger.setUpdateBy(support.getCurrentUsername());
            pipelineWebhookTriggerMapper.insert(trigger);
        } else {
            existing.setEnabled(enabled ? 1 : 0);
            existing.setUpdateBy(support.getCurrentUsername());
            existing.setUpdateTime(LocalDateTime.now());
            pipelineWebhookTriggerMapper.updateById(existing);
        }
    }

    public Map<String, Object> triggerByGitPush(Set<String> repoKeys, String branch, String pusher) {
        List<Map<String, Object>> triggered = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<PipelineWebhookTrigger> triggers = pipelineWebhookTriggerMapper.selectList(
                new LambdaQueryWrapper<PipelineWebhookTrigger>().eq(PipelineWebhookTrigger::getEnabled, 1));
        String creatorName = "webhook" + (pusher != null && !pusher.trim().isEmpty() ? "(" + pusher.trim() + ")" : "");

        for (PipelineWebhookTrigger trigger : triggers) {
            BuildJob anchor = buildJobMapper.selectById(trigger.getAnchorJobId());
            if (anchor == null || anchor.getDeployTaskId() != null) {
                continue;
            }
            BuildJob latest = support.resolveLatestJobInGroup(anchor);
            String configuredBranch = support.configuredBranch(latest);
            if (!"branch".equals(support.configuredGitType(latest))
                    || !branch.equals(configuredBranch != null ? configuredBranch.trim() : null)) {
                continue;
            }
            com.opsflow.dao.model.Service service = latest.getServiceId() != null
                    ? serviceMapper.selectById(latest.getServiceId()) : null;
            if (service == null || !repoKeys.contains(GitRepoKey.normalize(service.getGitRepo()))) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("taskId", anchor.getId());
            item.put("taskName", latest.getTaskName());
            Env env = latest.getEnvId() != null ? envMapper.selectById(latest.getEnvId()) : null;
            try {
                prodDeployPolicy.checkDirectDeploy(env, ProdDeployPolicy.Trigger.WEBHOOK_PUSH);
            } catch (BusinessException e) {
                item.put("reason", e.getMessage());
                skipped.add(item);
                continue;
            }
            try {
                BuildResponse response = rebuildService.rebuildForWebhook(anchor.getId(), creatorName);
                item.put("jobNumber", response.getJobNumber());
                triggered.add(item);
                log.info("Webhook 触发构建: task={}, branch={}, job={}, pusher={}",
                        latest.getTaskName(), branch, response.getJobNumber(), pusher);
            } catch (BusinessException e) {
                item.put("reason", e.getMessage());
                skipped.add(item);
                log.info("Webhook 跳过构建: task={}, branch={}, reason={}", latest.getTaskName(), branch, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggered", triggered);
        result.put("skipped", skipped);
        return result;
    }
}
