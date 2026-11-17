package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.RollbackCandidateDTO;
import com.opsflow.api.dto.RollbackRequest;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.service.impl.pipeline.PipelineJobLiveness;
import com.opsflow.service.impl.pipeline.PipelineJobQueryService;
import com.opsflow.service.impl.pipeline.PipelineJobRunner;
import com.opsflow.service.impl.pipeline.PipelineJobSupport;
import com.opsflow.service.impl.pipeline.PipelineRebuildService;
import com.opsflow.service.impl.pipeline.PipelineRollbackService;
import com.opsflow.service.impl.pipeline.PipelineStageLogService;
import com.opsflow.service.impl.pipeline.PipelineWebhookTriggerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 流水线运行服务门面：对外接口 {@link PipelineRunService} 不变，具体实现分布在
 * {@code com.opsflow.service.impl.pipeline} 包的各个 Bean 中：
 * <ul>
 *   <li>{@link PipelineJobRunner}：准备 BUILDING + 阶段，事务提交后派发执行线程，驱动 PipelineExecutor 并落库终态；</li>
 *   <li>{@link PipelineJobQueryService} / {@link PipelineStageLogService}：列表、阶段视图、阶段日志等只读查询；</li>
 *   <li>{@link PipelineRebuildService} / {@link PipelineRollbackService} / {@link PipelineWebhookTriggerService}：
 *       重新构建、回滚、Git 推送自动构建；</li>
 *   <li>{@link PipelineJobSupport} / {@link PipelineJobLiveness}：任务组定位、运行互斥、僵尸任务回收等公共能力。</li>
 * </ul>
 * 本类只保留任务编辑/删除和少量校验，其余方法均为委托。
 * <p>
 * 两类入口共用同一套「BuildJob + BuildJobStage + 异步执行」模型：流水线视图 Job（{@code deployTaskId} 为空）
 * 和上线任务 CD Job（{@code deployTaskId} 非空，终态经 {@link DeployTaskCdService#onBuildJobFinished} 回写）。
 * </p>
 */
@Slf4j
@Service
public class PipelineRunServiceImpl implements PipelineRunService {

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private StageLogStore stageLogStore;

    @Autowired
    private PipelineViewService pipelineViewService;

    @Autowired
    private PipelineWebhookTriggerMapper pipelineWebhookTriggerMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineJobLiveness liveness;

    @Autowired
    private PipelineJobRunner runner;

    @Autowired
    private PipelineJobQueryService queryService;

    @Autowired
    private PipelineStageLogService stageLogService;

    @Autowired
    private PipelineRebuildService rebuildService;

    @Autowired
    private PipelineRollbackService rollbackService;

    @Autowired
    private PipelineWebhookTriggerService webhookService;

    // ---------- 执行 ----------

    @Override
    public void launchJob(Long buildJobId) {
        runner.launchJob(buildJobId);
    }

    /**
     * @deprecated 请使用 {@link #launchJob(Long)}
     */
    @Override
    public void prepareAndExecuteAsync(Long buildJobId) {
        runner.launchJob(buildJobId);
    }

    @Override
    public void executeBuildJobAsync(Long buildJobId) {
        runner.executeBuildJobAsync(buildJobId);
    }

    @Override
    public void assertNoRunningBuild(Long serviceId, Long envId) {
        support.assertNoRunningBuild(serviceId, envId);
    }

    @Override
    public String findRunningJobNumber(Long serviceId, Long envId, Long excludeJobId) {
        return support.findRunningJobNumber(serviceId, envId, excludeJobId);
    }

    @Override
    public void recoverInterruptedRunningJobs() {
        liveness.recoverInterruptedRunningJobs();
    }

    // ---------- 查询 ----------

    @Override
    public List<PipelineJobViewDTO> listPipelineJobs(Long envId) {
        return listPipelineJobsPage(envId, null, null, 1, 10).getItems();
    }

    @Override
    public PipelineJobPageResult listPipelineJobsPage(Long envId, Long projectId, int page, int pageSize) {
        return listPipelineJobsPage(envId, projectId, null, page, pageSize);
    }

    @Override
    public PipelineJobPageResult listPipelineJobsPage(Long envId, Long projectId, String keyword, int page, int pageSize) {
        return queryService.listPipelineJobsPage(envId, projectId, keyword, page, pageSize);
    }

    @Override
    public PipelineViewDTO getStageView(Long anchorJobId, int page, int pageSize) {
        return queryService.getStageView(anchorJobId, page, pageSize);
    }

    @Override
    public List<PipelineStageDTO> getBuildStages(Long buildJobId) {
        return queryService.getBuildStages(buildJobId);
    }

    @Override
    public Map<String, Object> getBuildRunStatus(Long buildJobId) {
        return queryService.getBuildRunStatus(buildJobId);
    }

    @Override
    public PipelineJobViewDTO getPipelineJob(Long buildJobId) {
        return queryService.getPipelineJob(buildJobId);
    }

    @Override
    public String getStageLog(Long buildJobId, Long stageId) {
        return stageLogService.getStageLog(buildJobId, stageId);
    }

    @Override
    public Map<String, Object> getStageLogDetail(Long buildJobId, Long stageId, long offset) {
        return stageLogService.getStageLogDetail(buildJobId, stageId, offset);
    }

    // ---------- 重建 / Webhook / 回滚 ----------

    @Override
    public BuildResponse rebuildPipelineJob(Long jobId) {
        return rebuildService.rebuildPipelineJob(jobId);
    }

    @Override
    public BuildResponse rebuildForWebhook(Long anchorJobId, String creatorName) {
        return rebuildService.rebuildForWebhook(anchorJobId, creatorName);
    }

    @Override
    public void setWebhookEnabled(Long jobId, boolean enabled) {
        webhookService.setWebhookEnabled(jobId, enabled);
    }

    @Override
    public Map<String, Object> triggerByGitPush(Set<String> repoKeys, String branch, String pusher) {
        return webhookService.triggerByGitPush(repoKeys, branch, pusher);
    }

    @Override
    public List<RollbackCandidateDTO> listRollbackCandidates(Long jobId) {
        return rollbackService.listRollbackCandidates(jobId);
    }

    @Override
    public BuildResponse rollbackPipelineJob(Long jobId, RollbackRequest request) {
        return rollbackService.rollbackPipelineJob(jobId, request);
    }

    // ---------- 编辑 / 删除 ----------

    @Override
    @Transactional
    public PipelineJobViewDTO updatePipelineJob(Long buildJobId, BuildRequest request) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(buildJob);
        if (buildJob.getDeployTaskId() != null) {
            throw new BusinessException("上线任务自动创建的 CD 任务不支持手动编辑");
        }
        if (support.isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法编辑");
        }

        projectAccessService.assertCanOperateService(buildJob.getServiceId());
        validateUpdateRequest(request);
        pipelineViewService.assertAccessibleEnv(request.getEnvId());
        projectAccessService.assertCanOperateService(request.getServiceId());

        com.opsflow.dao.model.Service service = serviceMapper.selectById(request.getServiceId());
        if (service == null) {
            throw new BusinessException("服务不存在");
        }
        String taskName = resolveTaskName(request, service);
        Env env = envMapper.selectById(request.getEnvId());
        if (env == null) {
            throw new BusinessException("环境不存在");
        }
        if (env.isProdEnv()) {
            throw new BusinessException("生产环境请使用上线任务流程");
        }
        Pipeline pipeline = pipelineMapper.selectById(request.getPipelineTemplateId());
        if (pipeline == null || pipeline.getStatus() != 1) {
            throw new BusinessException("Pipeline 模板不存在或已禁用");
        }

        buildJob.setUpdateTime(LocalDateTime.now());

        Long anchorJobId = support.resolveAnchorJob(buildJob).getId();
        List<BuildJob> groupJobs = support.listJobsInGroup(buildJob);
        for (BuildJob job : groupJobs) {
            job.setTaskName(taskName);
            job.setServiceId(request.getServiceId());
            job.setEnvId(request.getEnvId());
            job.setBranch(request.getBranch());
            job.setGitType(support.normalizeGitType(request.getGitType()));
            job.setPipelineTemplateId(request.getPipelineTemplateId());
            job.setUpdateTime(LocalDateTime.now());
            buildJobMapper.updateById(job);
        }

        BuildJob anchor = support.resolveAnchorJob(buildJob);
        BuildJob latest = support.resolveLatestJobInGroup(anchor);
        return queryService.toTaskView(anchor, latest);
    }

    @Override
    @Transactional
    public boolean deletePipelineJob(Long buildJobId) {
        BuildJob buildJob = buildJobMapper.selectById(buildJobId);
        if (buildJob == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(buildJob);
        projectAccessService.assertCanOperateService(buildJob.getServiceId());
        if (buildJob.getDeployTaskId() != null) {
            if (liveness.isJobRunning(buildJob)) {
                throw new BusinessException("任务运行中，无法删除");
            }
            buildJobStageMapper.delete(new QueryWrapper<BuildJobStage>().eq("build_job_id", buildJob.getId()));
            stageLogStore.deleteJobLogs(buildJob.getId());
            return buildJobMapper.deleteById(buildJob.getId()) > 0;
        }
        if (support.isGroupRunning(buildJob)) {
            throw new BusinessException("任务运行中，无法删除");
        }

        Long anchorJobId = support.resolveAnchorJob(buildJob).getId();
        List<BuildJob> groupJobs = support.listJobsInGroup(buildJob);
        for (BuildJob job : groupJobs) {
            buildJobStageMapper.delete(new QueryWrapper<BuildJobStage>().eq("build_job_id", job.getId()));
            stageLogStore.deleteJobLogs(job.getId());
            buildJobMapper.deleteById(job.getId());
        }
        pipelineWebhookTriggerMapper.delete(new LambdaQueryWrapper<PipelineWebhookTrigger>()
                .eq(PipelineWebhookTrigger::getAnchorJobId, anchorJobId));
        return true;
    }

    private void validateUpdateRequest(BuildRequest request) {
        if (request.getServiceId() == null) {
            throw new BusinessException("服务ID不能为空");
        }
        if (request.getEnvId() == null) {
            throw new BusinessException("环境ID不能为空");
        }
        if (request.getPipelineTemplateId() == null) {
            throw new BusinessException("Pipeline模板ID不能为空");
        }
        if (request.getBranch() == null || request.getBranch().trim().isEmpty()) {
            throw new BusinessException("分支不能为空");
        }
    }

    @Override
    public boolean isProdEnvJob(Long jobId) {
        BuildJob job = jobId != null ? buildJobMapper.selectById(jobId) : null;
        if (job == null || job.getEnvId() == null) {
            return false;
        }
        Env env = envMapper.selectById(job.getEnvId());
        return env != null && env.isProdEnv();
    }

    @Override
    public void assertJobAccessible(Long jobId) {
        BuildJob job = jobId != null ? buildJobMapper.selectById(jobId) : null;
        if (job == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(job);
    }

    private String resolveTaskName(BuildRequest request, com.opsflow.dao.model.Service service) {
        if (request.getTaskName() != null && !request.getTaskName().trim().isEmpty()) {
            return request.getTaskName().trim();
        }
        if (service != null && service.getName() != null && !service.getName().trim().isEmpty()) {
            return service.getName().trim();
        }
        throw new BusinessException("服务名称不能为空，无法创建任务");
    }
}
