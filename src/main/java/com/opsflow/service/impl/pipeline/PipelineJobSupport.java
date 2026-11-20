package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.*;
import com.opsflow.dao.model.*;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.ProjectAccessService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 流水线任务公共能力：任务组定位、运行互斥校验、访问校验、当前用户、构建参数/步骤解析、阶段记录初始化。
 * <p>由 {@link com.opsflow.service.impl.PipelineRunServiceImpl} 拆分而来，行为保持不变。</p>
 */
@Slf4j
@Component
public class PipelineJobSupport {
    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PipelineViewService pipelineViewService;

    @Autowired
    private ProjectAccessService projectAccessService;

    @Autowired
    private PipelineJobLiveness liveness;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryWrapper<BuildJob> buildTaskGroupWrapper(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        if (isDeployTaskManaged(anchor)) {
            wrapper.isNotNull("deploy_task_id");
        } else {
            wrapper.isNull("deploy_task_id");
            if (anchor.getTaskName() != null && !anchor.getTaskName().trim().isEmpty()) {
                wrapper.eq("task_name", anchor.getTaskName().trim());
            } else {
                wrapper.and(w -> w.isNull("task_name").or().eq("task_name", ""));
            }
        }
        if (anchor.getServiceId() != null) {
            wrapper.eq("service_id", anchor.getServiceId());
        }
        if (anchor.getEnvId() != null) {
            wrapper.eq("env_id", anchor.getEnvId());
        }
        if (anchor.getPipelineTemplateId() != null) {
            wrapper.eq("pipeline_template_id", anchor.getPipelineTemplateId());
        }
        return wrapper;
    }

    public String buildTaskGroupKey(BuildJob job) {
        if (isDeployTaskManaged(job)) {
            return String.format("deploy|%s|%s|%s",
                job.getServiceId(),
                job.getEnvId(),
                job.getPipelineTemplateId());
        }
        return String.format("manual|%s|%s|%s|%s",
            job.getTaskName() != null ? job.getTaskName().trim() : "",
            job.getServiceId(),
            job.getEnvId(),
            job.getPipelineTemplateId());
    }

    public boolean isDeployTaskManaged(BuildJob job) {
        return job != null && job.getDeployTaskId() != null;
    }

    public BuildJob resolveAnchorJob(BuildJob job) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(job);
        wrapper.orderByAsc("id").last("LIMIT 1");
        BuildJob anchor = buildJobMapper.selectOne(wrapper);
        return anchor != null ? anchor : job;
    }

    public BuildJob resolveLatestJobInGroup(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(anchor);
        wrapper.orderByDesc("create_time").last("LIMIT 1");
        BuildJob latest = buildJobMapper.selectOne(wrapper);
        if (latest != null) {
            liveness.refreshStaleRunningJob(latest);
            return latest;
        }
        return anchor;
    }

    public List<BuildJob> listJobsInGroup(BuildJob anchor) {
        QueryWrapper<BuildJob> wrapper = buildTaskGroupWrapper(anchor);
        wrapper.orderByDesc("create_time");
        return buildJobMapper.selectList(wrapper);
    }

    public boolean isGroupRunning(BuildJob anchor) {
        BuildJob groupAnchor = resolveAnchorJob(anchor);
        BuildJob latest = resolveLatestJobInGroup(groupAnchor);
        return latest != null && liveness.isJobRunning(latest);
    }

    public String firstNonEmpty(String a, String b) {
        if (a != null && !a.trim().isEmpty()) {
            return a;
        }
        return b;
    }

    @Transactional
    public void assertNoRunningBuild(Long serviceId, Long envId) {
        String running = findRunningJobNumber(serviceId, envId, null);
        if (running != null) {
            throw new BusinessException("该服务在此环境已有任务在运行（" + running + "），请等待完成后再试");
        }
    }

    @Transactional
    public String findRunningJobNumber(Long serviceId, Long envId, Long excludeJobId) {
        if (serviceId == null) {
            return null;
        }
        // 先锁服务行，让同一服务的并发提交排队；再用当前读（FOR UPDATE）查运行中任务，
        // 避免可重复读隔离级别下读到事务开始前的旧快照
        serviceMapper.selectOne(
            new QueryWrapper<com.opsflow.dao.model.Service>().eq("id", serviceId).last("FOR UPDATE")
        );
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<BuildJob>()
                .eq("service_id", serviceId)
                .in("status", BuildStatus.BUILDING, BuildStatus.DEPLOYING);
        if (envId != null) {
            wrapper.eq("env_id", envId);
        } else {
            wrapper.isNull("env_id");
        }
        if (excludeJobId != null) {
            wrapper.ne("id", excludeJobId);
        }
        for (BuildJob job : buildJobMapper.selectList(wrapper.last("FOR UPDATE"))) {
            if (liveness.isJobRunning(job)) {
                return job.getJobNumber();
            }
        }
        return null;
    }

    public void assertJobEnvAccessible(BuildJob job) {
        if (job == null) {
            return;
        }
        pipelineViewService.assertAccessibleEnv(job.getEnvId());
        projectAccessService.assertCanViewService(job.getServiceId());
    }

    public Long getCurrentUserId() {
        String username = getCurrentUsername();
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        return user != null ? user.getId() : null;
    }

    public String getCurrentUsername() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object user = session != null ? session.getAttribute("user") : null;
                if (user != null && !String.valueOf(user).trim().isEmpty()) {
                    return String.valueOf(user).trim();
                }
            }
        } catch (Exception e) {
            log.debug("读取当前登录用户名失败: {}", e.getMessage());
        }
        return null;
    }

    public List<PipelineStepDTO> parseSteps(String stepsConfig) throws Exception {
        if (stepsConfig == null || stepsConfig.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<PipelineStepDTO> steps = objectMapper.readValue(stepsConfig, new TypeReference<List<PipelineStepDTO>>() {});
        return steps.stream()
            .filter(step -> step.getEnabled() == null || Boolean.TRUE.equals(step.getEnabled()))
            .sorted(Comparator.comparing(step -> step.getOrder() != null ? step.getOrder() : 0))
            .collect(Collectors.toList());
    }

    public Map<Integer, BuildJobStage> createStageRecords(Long buildJobId, List<PipelineStepDTO> steps) {
        Map<Integer, BuildJobStage> stageByOrder = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();
        for (PipelineStepDTO step : steps) {
            int order = step.getOrder() != null ? step.getOrder() : stageByOrder.size() + 1;
            BuildJobStage stage = new BuildJobStage();
            stage.setBuildJobId(buildJobId);
            stage.setStepOrder(order);
            stage.setStepType(step.getStepType());
            stage.setStepName(step.getStepName() != null ? step.getStepName() : step.getStepType());
            stage.setStatus("PENDING");
            stage.setCreateTime(now);
            stage.setUpdateTime(now);
            buildJobStageMapper.insert(stage);
            stageByOrder.put(order, stage);
        }
        return stageByOrder;
    }

    public Map<Integer, BuildJobStage> loadOrCreateStageRecords(Long buildJobId, List<PipelineStepDTO> steps) {
        List<BuildJobStage> existing = buildJobStageMapper.selectList(
            new QueryWrapper<BuildJobStage>()
                .eq("build_job_id", buildJobId)
                .orderByAsc("step_order")
        );
        if (!existing.isEmpty()) {
            Map<Integer, BuildJobStage> stageByOrder = new HashMap<>();
            for (BuildJobStage stage : existing) {
                stageByOrder.put(stage.getStepOrder(), stage);
            }
            return stageByOrder;
        }
        return createStageRecords(buildJobId, steps);
    }

    public Map<String, String> parseBuildParamMap(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null || json.trim().isEmpty()) {
            return result;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            if (raw != null) {
                for (Map.Entry<String, Object> entry : raw.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        result.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("解析 buildParameters 失败: {}", e.getMessage());
        }
        return result;
    }

    public String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    public String normalizeGitType(String gitType) {
        return "tag".equalsIgnoreCase(gitType) ? "tag" : "branch";
    }

    /** 任务当前配置的分支/Tag：编辑过则取 nextBranch，否则就是这次构建的 branch。传入组内最新一行。 */
    public String configuredBranch(BuildJob latest) {
        return firstNonEmpty(latest.getNextBranch(), latest.getBranch());
    }

    /** 任务当前配置的 Git 类型，规则同 {@link #configuredBranch}。 */
    public String configuredGitType(BuildJob latest) {
        if (latest.getNextGitType() != null && !latest.getNextGitType().trim().isEmpty()) {
            return normalizeGitType(latest.getNextGitType());
        }
        return resolveJobGitType(latest);
    }

    public String resolveJobGitType(BuildJob job) {
        if (job.getGitType() != null && !job.getGitType().trim().isEmpty()) {
            return normalizeGitType(job.getGitType());
        }
        if (job.getServiceId() != null) {
            com.opsflow.dao.model.Service service = serviceMapper.selectById(job.getServiceId());
            if (service != null && service.getGitType() != null && !service.getGitType().trim().isEmpty()) {
                return normalizeGitType(service.getGitType());
            }
        }
        return "branch";
    }
}
