package com.opsflow.service.impl.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.common.util.JobNumberGenerator;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.ProjectAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 流水线任务「重新构建」：按任务组最近一次配置新建 BuildJob 并执行。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineRebuildService {
    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineJobRunner runner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 流水线视图「重新构建」：在同一任务组（taskName + service + env + pipeline）下新建一次 BuildJob 并执行。
     * <p><b>业务流程</b></p>
     * <ol>
     *   <li>解析 anchor 与组内最新 Job 作为配置来源（分支、模板、构建参数等）；</li>
     *   <li>若组内仍有 BUILDING/DEPLOYING 任务则拒绝；</li>
     *   <li>insert 新 BuildJob（BUILDING + startTime），{@code deployTaskId} 为空（非 CD 路径）；</li>
     *   <li>调用 {@link PipelineJobRunner#launchJob} 初始化阶段并异步执行。</li>
     * </ol>
     * 与上线任务 CD 的区别：不经过 {@link DeployTaskCdService}，不会设置 deployTaskId，也不会触发 CD 回写。
     *
     * @param jobId 组内任意一次历史 BuildJob ID（用于定位任务组）
     * @return 新建 BuildJob 的基本信息（jobId、jobNumber、status 等）
     */
    @Transactional
    public BuildResponse rebuildPipelineJob(Long jobId) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(ref);
        if (ref.getDeployTaskId() != null) {
            return deployTaskCdService.retryCdJob(ref.getId());
        }
        projectAccessService.assertCanOperateService(ref.getServiceId());
        return startRebuild(ref, support.getCurrentUserId(), support.getCurrentUsername());
    }

    /**
     * 按任务组最近一次配置新建一次构建；不做登录态/视图权限校验，调用方负责。
     *
     * @param creatorId 为 null 时沿用上次构建的创建人
     */
    private BuildResponse startRebuild(BuildJob ref, Long creatorId, String creatorName) {
        BuildJob anchor = support.resolveAnchorJob(ref);
        BuildJob configSource = support.resolveLatestJobInGroup(anchor);

        if (support.isGroupRunning(anchor)) {
            throw new BusinessException("任务正在构建中，请稍后再试");
        }
        support.assertNoRunningBuild(configSource.getServiceId(), configSource.getEnvId());

        BuildJob newJob = new BuildJob();
        newJob.setJobNumber(JobNumberGenerator.generateBuildJobNumber());
        newJob.setTaskName(configSource.getTaskName());
        newJob.setServiceId(configSource.getServiceId());
        newJob.setEnvId(configSource.getEnvId());
        newJob.setBranch(support.configuredBranch(configSource));
        newJob.setGitType(support.configuredGitType(configSource));
        newJob.setPipelineTemplateId(configSource.getPipelineTemplateId());
        // Run 必须跑完整 CI+CD：若上次是回滚，buildParameters 会残留 rollback=true，需剔除
        newJob.setBuildParameters(copyParamsForFullRebuild(configSource.getBuildParameters()));
        newJob.setStatus(BuildStatus.BUILDING);
        newJob.setStartTime(LocalDateTime.now());
        BuildJobCreatorUtils.stampCreator(objectMapper, newJob,
                creatorId != null ? creatorId : configSource.getCreatorId(),
                creatorName);
        buildJobMapper.insert(newJob);

        // 同步准备阶段 + 提交后异步执行
        runner.launchJob(newJob.getId());

        BuildResponse response = new BuildResponse();
        response.setJobId(newJob.getId());
        response.setJobNumber(newJob.getJobNumber());
        response.setTaskName(newJob.getTaskName());
        response.setStatus(BuildStatus.BUILDING);
        response.setStartTime(newJob.getStartTime());
        return response;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BuildResponse rebuildForWebhook(Long anchorJobId, String creatorName) {
        BuildJob anchor = buildJobMapper.selectById(anchorJobId);
        if (anchor == null) {
            throw new BusinessException("流水线任务不存在");
        }
        return startRebuild(anchor, null, creatorName);
    }

    /**
     * 复制构建参数供「Run」完整重建：去掉回滚标记，避免误跳过 CI。
     */
    String copyParamsForFullRebuild(String sourceJson) {
        Map<String, String> params = new HashMap<>(support.parseBuildParamMap(sourceJson));
        params.remove("rollback");
        params.remove("rollbackFromJobId");
        params.remove("rollbackFromImage");
        if (params.isEmpty()) {
            return sourceJson == null || sourceJson.trim().isEmpty() ? null : "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            log.warn("序列化重建参数失败，回退原参数并尝试剔除 rollback: {}", e.getMessage());
            return sourceJson;
        }
    }
}
