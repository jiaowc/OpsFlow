package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.api.dto.RollbackCandidateDTO;
import com.opsflow.api.dto.RollbackRequest;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.integration.pipeline.StageLogStore;
import com.opsflow.service.PipelineStepResolveService;
import com.opsflow.service.ProjectAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 流水线回滚：列出可回滚的历史镜像版本，在原 Job 上回滚到所选版本。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineRollbackService {
    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobLifecycleNotifier lifecycleNotifier;

    @Autowired
    private PipelineStepResolveService pipelineStepResolveService;

    @Autowired
    private StageLogStore stageLogStore;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private PipelineJobSupport support;

    @Autowired
    private PipelineJobRunner runner;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<RollbackCandidateDTO> listRollbackCandidates(Long jobId) {
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        BuildJob latest = support.resolveLatestJobInGroup(support.resolveAnchorJob(ref));
        List<BuildJob> successJobs = listSuccessfulJobsWithImage(latest.getServiceId(), latest.getEnvId());
        String currentImage = support.firstNonEmpty(latest.getImageFullName(),
                support.parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                support.parseBuildParamMap(latest.getBuildParameters()).get("image"));

        List<RollbackCandidateDTO> result = new ArrayList<>();
        Set<String> seenImages = new HashSet<>();
        for (BuildJob job : successJobs) {
            String image = support.firstNonEmpty(job.getImageFullName(),
                    support.parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    support.parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image == null || image.isEmpty() || !seenImages.add(image)) {
                continue;
            }
            RollbackCandidateDTO dto = new RollbackCandidateDTO();
            dto.setJobId(job.getId());
            dto.setJobNumber(job.getJobNumber());
            dto.setImageFullName(image);
            dto.setImageTag(support.firstNonEmpty(job.getImageTag(), job.getBranch(),
                    support.parseBuildParamMap(job.getBuildParameters()).get("imageTag")));
            if (job.getCreateTime() != null) {
                dto.setCreateTimeText(job.getCreateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
            dto.setCurrent(currentImage != null && currentImage.equals(image)
                    && BuildStatus.SUCCESS.equals(latest.getStatus()));
            result.add(dto);
            if (result.size() >= 20) {
                break;
            }
        }
        return result;
    }

    @Transactional
    public BuildResponse rollbackPipelineJob(Long jobId, RollbackRequest request) {
        if (request == null || request.getSourceJobId() == null) {
            throw new BusinessException("请选择要回滚到的历史版本");
        }
        BuildJob ref = buildJobMapper.selectById(jobId);
        if (ref == null) {
            throw new BusinessException("流水线任务不存在");
        }
        support.assertJobEnvAccessible(ref);
        projectAccessService.assertCanOperateService(ref.getServiceId());
        BuildJob anchor = support.resolveAnchorJob(ref);
        BuildJob latest = support.resolveLatestJobInGroup(anchor);
        if (support.isGroupRunning(anchor)) {
            throw new BusinessException("任务正在运行中，请稍后再试");
        }
        support.assertNoRunningBuild(latest.getServiceId(), latest.getEnvId());

        Pipeline origin = pipelineMapper.selectById(latest.getPipelineTemplateId());
        if (origin == null || origin.getStatus() == null || origin.getStatus() != 1) {
            throw new BusinessException("Pipeline 模版不存在或已禁用");
        }
        if (!PipelineTypes.supportsImageRollback(origin.getPipelineType())) {
            throw new BusinessException("仅 CD / CI/CD 流水线支持回滚到历史镜像版本");
        }

        Env env = envMapper.selectById(latest.getEnvId());
        if (env != null && env.isProdEnv() && !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new BusinessException("生产环境回滚需要二次确认");
        }

        BuildJob source = buildJobMapper.selectById(request.getSourceJobId());
        if (source == null) {
            throw new BusinessException("回滚目标版本不存在");
        }
        if (!BuildStatus.SUCCESS.equals(source.getStatus())) {
            throw new BusinessException("只能回滚到历史成功版本");
        }
        if (!Objects.equals(source.getServiceId(), latest.getServiceId())
                || !Objects.equals(source.getEnvId(), latest.getEnvId())) {
            throw new BusinessException("回滚目标必须属于同一服务与环境");
        }
        String imageFullName = support.firstNonEmpty(source.getImageFullName(),
                support.parseBuildParamMap(source.getBuildParameters()).get("imageFullName"),
                support.parseBuildParamMap(source.getBuildParameters()).get("image"));
        if (imageFullName == null || imageFullName.isEmpty()) {
            throw new BusinessException("回滚目标缺少镜像地址");
        }
        String imageTag = support.firstNonEmpty(source.getImageTag(), source.getBranch(),
                support.parseBuildParamMap(source.getBuildParameters()).get("imageTag"));
        if (imageTag == null || imageTag.isEmpty()) {
            int colon = imageFullName.lastIndexOf(':');
            imageTag = colon > 0 ? imageFullName.substring(colon + 1) : imageFullName;
        }

        String currentImage = support.firstNonEmpty(latest.getImageFullName(),
                support.parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                support.parseBuildParamMap(latest.getBuildParameters()).get("image"));
        if (imageFullName.equals(currentImage) && BuildStatus.SUCCESS.equals(latest.getStatus())) {
            throw new BusinessException("所选版本已是当前成功部署版本，无需回滚");
        }

        Map<String, String> params = new HashMap<>(support.parseBuildParamMap(latest.getBuildParameters()));
        Map<String, String> sourceParams = support.parseBuildParamMap(source.getBuildParameters());
        for (String key : Arrays.asList("clusterId", "k8sNamespace", "harborProject",
                "servicePort", "port", "serviceName", "serviceCode")) {
            if (sourceParams.containsKey(key) && !params.containsKey(key)) {
                params.put(key, sourceParams.get(key));
            }
        }
        params.put("imageFullName", imageFullName);
        params.put("image", imageFullName);
        params.put("imageTag", imageTag);
        params.put("rollback", "true");
        params.put("rollbackFromJobId", String.valueOf(source.getId()));
        params.put("rollbackFromImage", currentImage != null ? currentImage : "");

        // 在原 Job 上更新镜像与参数，不新建任务
        latest.setImageTag(imageTag);
        latest.setImageFullName(imageFullName);
        latest.setBranch(imageTag);
        latest.setGitType("tag");
        latest.setErrorMessage(null);
        latest.setEndTime(null);
        latest.setStatus(BuildStatus.PENDING);
        latest.setUpdateTime(LocalDateTime.now());
        try {
            latest.setBuildParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new BusinessException("序列化回滚参数失败");
        }
        buildJobMapper.updateById(latest);

        resetCdStagesForRollback(latest.getId(), origin);

        // 扩展（如上线任务）同步自己的状态，和回滚同一事务
        lifecycleNotifier.rollbackStarted(latest);

        log.info("原 Job 回滚启动: jobId={}, fromJobId={}, image={}", latest.getId(), source.getId(), imageFullName);
        runner.launchJob(latest.getId());

        BuildResponse response = new BuildResponse();
        response.setJobId(latest.getId());
        response.setJobNumber(latest.getJobNumber());
        response.setTaskName(latest.getTaskName());
        response.setStatus(BuildStatus.BUILDING);
        response.setImageTag(imageTag);
        response.setStartTime(LocalDateTime.now());
        return response;
    }

    /**
     * 回滚前仅重置 CD 阶段为 PENDING，CI 阶段保留原成功记录。
     */
    private void resetCdStagesForRollback(Long buildJobId, Pipeline pipeline) {
        List<PipelineStepDTO> steps;
        try {
            steps = pipelineStepResolveService.resolveSteps(support.parseSteps(pipeline.getStepsConfig()));
        } catch (Exception e) {
            throw new BusinessException("解析 Pipeline 步骤失败: " + e.getMessage());
        }
        if (steps.isEmpty()) {
            return;
        }
        Map<Integer, BuildJobStage> stageByOrder = support.loadOrCreateStageRecords(buildJobId, steps);
        LocalDateTime now = LocalDateTime.now();
        for (PipelineStepDTO step : steps) {
            int order = step.getOrder() != null ? step.getOrder() : 0;
            BuildJobStage stage = stageByOrder.get(order);
            if (stage == null) {
                continue;
            }
            if (isCiOnlyStepType(step.getStepType())) {
                continue;
            }
            stage.setStatus("PENDING");
            stage.setDurationMs(0L);
            stage.setErrorMessage(null);
            stage.setStartTime(null);
            stage.setEndTime(null);
            stage.setLogPreview(null);
            // 保留 logPath，执行时 onStageStart 会覆盖写入新日志
            if (stage.getLogPath() == null || stage.getLogPath().isEmpty()) {
                stage.setLogPath(stageLogStore.relativePath(buildJobId, stage.getId()));
            }
            stage.setUpdateTime(now);
            buildJobStageMapper.updateById(stage);
        }
    }

    boolean isCiOnlyStepType(String stepType) {
        if (stepType == null) {
            return false;
        }
        String t = stepType.trim().toLowerCase();
        return "checkout".equals(t)
            || "build".equals(t)
            || "clean".equals(t)
            || "clean_workspace".equals(t)
            || "docker_build".equals(t)
            || "docker-build".equals(t)
            || "push_image".equals(t)
            || "push-image".equals(t);
    }

    public boolean canRollback(BuildJob latest) {
        if (latest == null || latest.getServiceId() == null || latest.getEnvId() == null) {
            return false;
        }
        if (latest.getPipelineTemplateId() == null) {
            return false;
        }
        Pipeline pipeline = pipelineMapper.selectById(latest.getPipelineTemplateId());
        if (pipeline == null) {
            return false;
        }
        if (!PipelineTypes.supportsImageRollback(pipeline.getPipelineType())) {
            return false;
        }
        List<BuildJob> successJobs = listSuccessfulJobsWithImage(latest.getServiceId(), latest.getEnvId());
        if (successJobs.isEmpty()) {
            return false;
        }
        String currentImage = support.firstNonEmpty(latest.getImageFullName(),
                support.parseBuildParamMap(latest.getBuildParameters()).get("imageFullName"),
                support.parseBuildParamMap(latest.getBuildParameters()).get("image"));
        for (BuildJob job : successJobs) {
            String image = support.firstNonEmpty(job.getImageFullName(),
                    support.parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    support.parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image == null || image.isEmpty()) {
                continue;
            }
            if (!BuildStatus.SUCCESS.equals(latest.getStatus()) || currentImage == null || !image.equals(currentImage)) {
                return true;
            }
        }
        // 仅有当前成功版本、没有更早版本时不可回滚
        return successJobs.size() > 1;
    }

    private List<BuildJob> listSuccessfulJobsWithImage(Long serviceId, Long envId) {
        if (serviceId == null || envId == null) {
            return Collections.emptyList();
        }
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        wrapper.eq("service_id", serviceId)
                .eq("env_id", envId)
                .eq("status", BuildStatus.SUCCESS)
                .and(w -> w.isNotNull("image_full_name").ne("image_full_name", "")
                        .or().isNotNull("build_parameters").ne("build_parameters", ""))
                .orderByDesc("create_time")
                .last("LIMIT 50");
        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);
        List<BuildJob> filtered = new ArrayList<>();
        for (BuildJob job : jobs) {
            String image = support.firstNonEmpty(job.getImageFullName(),
                    support.parseBuildParamMap(job.getBuildParameters()).get("imageFullName"),
                    support.parseBuildParamMap(job.getBuildParameters()).get("image"));
            if (image != null && !image.isEmpty()) {
                filtered.add(job);
            }
        }
        return filtered;
    }
}
