package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ApprovalRecordDTO;
import com.opsflow.api.dto.PipelineBoardViewDTO;
import com.opsflow.api.dto.StatisticsOverviewDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Env;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.InboxMessageService;
import com.opsflow.service.LicenseService;
import com.opsflow.service.PipelineViewService;
import com.opsflow.service.StatisticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class StatisticsServiceImpl implements StatisticsService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private InboxMessageService inboxMessageService;

    @Autowired
    private PipelineViewService pipelineViewService;

    @Autowired
    private LicenseService licenseService;

    @Override
    public StatisticsOverviewDTO getOverview() {
        StatisticsOverviewDTO dto = new StatisticsOverviewDTO();
        String username = currentUsername();
        Set<Long> allowedEnvIds = resolveAllowedEnvIds();

        fillActionSummary(dto.getAction(), username, allowedEnvIds);
        dto.setBuildTrend(buildTrend(allowedEnvIds));
        dto.setDeployFunnel(buildDeployFunnel());
        dto.setRunningJobs(listRunningJobs(allowedEnvIds, 8));
        dto.setRecentFailures(listRecentFailures(allowedEnvIds, 8));
        dto.setViews(listViewShortcuts());
        return dto;
    }

    private void fillActionSummary(StatisticsOverviewDTO.ActionSummary action,
                                   String username,
                                   Set<Long> allowedEnvIds) {
        if (StringUtils.hasText(username)) {
            if (licenseService.isFeatureEnabled(LicenseFeatures.DEPLOY_APPROVAL)) {
                try {
                    List<ApprovalRecordDTO> pending = approvalService.listMyPending(username);
                    action.setPendingApprovals(pending != null ? pending.size() : 0);
                } catch (Exception ignored) {
                    action.setPendingApprovals(0);
                }
            } else {
                action.setPendingApprovals(0);
            }
            try {
                action.setUnreadInbox(inboxMessageService.countUnread(username));
            } catch (Exception ignored) {
                action.setUnreadInbox(0);
            }
        }

        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime dayEnd = LocalDate.now().atTime(LocalTime.MAX);

        QueryWrapper<BuildJob> todayWrapper = new QueryWrapper<>();
        todayWrapper.ge("create_time", dayStart).le("create_time", dayEnd);
        applyEnvFilter(todayWrapper, allowedEnvIds);
        List<BuildJob> todayJobs = buildJobMapper.selectList(todayWrapper);
        long todaySuccess = 0;
        long todayFailed = 0;
        for (BuildJob job : todayJobs) {
            String st = normalizeStatus(job.getStatus());
            if (BuildStatus.SUCCESS.equals(st)) {
                todaySuccess++;
            } else if (BuildStatus.FAILED.equals(st)) {
                todayFailed++;
            }
        }
        action.setTodayBuilds(todayJobs.size());
        action.setTodaySuccess(todaySuccess);
        action.setTodayFailed(todayFailed);
        if (!todayJobs.isEmpty()) {
            long finished = todaySuccess + todayFailed;
            if (finished > 0) {
                action.setTodaySuccessRate(Math.round(todaySuccess * 1000.0 / finished) / 10.0);
            } else {
                action.setTodaySuccessRate(null);
            }
        }

        QueryWrapper<BuildJob> runningWrapper = new QueryWrapper<>();
        runningWrapper.in("status", BuildStatus.BUILDING, BuildStatus.DEPLOYING, BuildStatus.PENDING);
        applyEnvFilter(runningWrapper, allowedEnvIds);
        Long running = buildJobMapper.selectCount(runningWrapper);
        action.setRunningJobs(running != null ? running : 0);

        List<BuildNode> nodes = buildNodeMapper.selectList(new QueryWrapper<>());
        long offline = 0;
        for (BuildNode node : nodes) {
            if (!isNodeOnline(node.getStatus())) {
                offline++;
            }
        }
        action.setTotalNodes(nodes.size());
        action.setOfflineNodes(offline);
    }

    private List<StatisticsOverviewDTO.DayTrend> buildTrend(Set<Long> allowedEnvIds) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(6);
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        wrapper.ge("create_time", start.atStartOfDay());
        applyEnvFilter(wrapper, allowedEnvIds);
        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);

        Map<LocalDate, StatisticsOverviewDTO.DayTrend> map = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            StatisticsOverviewDTO.DayTrend t = new StatisticsOverviewDTO.DayTrend();
            t.setDate(d.format(DAY_FMT));
            map.put(d, t);
        }
        for (BuildJob job : jobs) {
            if (job.getCreateTime() == null) {
                continue;
            }
            LocalDate d = job.getCreateTime().toLocalDate();
            StatisticsOverviewDTO.DayTrend t = map.get(d);
            if (t == null) {
                continue;
            }
            t.setTotal(t.getTotal() + 1);
            String st = normalizeStatus(job.getStatus());
            if (BuildStatus.SUCCESS.equals(st)) {
                t.setSuccess(t.getSuccess() + 1);
            } else if (BuildStatus.FAILED.equals(st)) {
                t.setFailed(t.getFailed() + 1);
            }
        }
        return new ArrayList<>(map.values());
    }

    private StatisticsOverviewDTO.DeployFunnel buildDeployFunnel() {
        StatisticsOverviewDTO.DeployFunnel funnel = new StatisticsOverviewDTO.DeployFunnel();
        List<DeployTask> tasks = deployTaskMapper.selectList(
                new QueryWrapper<DeployTask>().orderByDesc("create_time").last("LIMIT 500"));
        for (DeployTask task : tasks) {
            String approval = lower(task.getApprovalStatus());
            String status = lower(task.getTaskStatus());
            if ("pending".equals(approval)) {
                funnel.setPendingApproval(funnel.getPendingApproval() + 1);
            }
            if (("approved".equals(approval) || "none".equals(approval)) && "pending".equals(status)) {
                funnel.setApprovedPending(funnel.getApprovedPending() + 1);
            }
            if ("building".equals(status) || "deploying".equals(status)) {
                funnel.setDeploying(funnel.getDeploying() + 1);
            }
            if ("success".equals(status)) {
                funnel.setSuccess(funnel.getSuccess() + 1);
            }
            if ("failed".equals(status)) {
                funnel.setFailed(funnel.getFailed() + 1);
            }
        }
        return funnel;
    }

    private List<StatisticsOverviewDTO.RecentItem> listRunningJobs(Set<Long> allowedEnvIds, int limit) {
        QueryWrapper<BuildJob> wrapper = new QueryWrapper<>();
        wrapper.in("status", BuildStatus.BUILDING, BuildStatus.DEPLOYING, BuildStatus.PENDING)
                .orderByDesc("update_time")
                .last("LIMIT " + Math.max(limit, 1) * 3);
        applyEnvFilter(wrapper, allowedEnvIds);
        List<BuildJob> jobs = buildJobMapper.selectList(wrapper);
        List<StatisticsOverviewDTO.RecentItem> items = new ArrayList<>();
        for (BuildJob job : jobs) {
            if (items.size() >= limit) {
                break;
            }
            items.add(toJobItem(job));
        }
        return items;
    }

    private List<StatisticsOverviewDTO.RecentItem> listRecentFailures(Set<Long> allowedEnvIds, int limit) {
        List<StatisticsOverviewDTO.RecentItem> items = new ArrayList<>();

        QueryWrapper<BuildJob> jobWrapper = new QueryWrapper<>();
        jobWrapper.eq("status", BuildStatus.FAILED)
                .orderByDesc("update_time")
                .last("LIMIT " + limit);
        applyEnvFilter(jobWrapper, allowedEnvIds);
        for (BuildJob job : buildJobMapper.selectList(jobWrapper)) {
            items.add(toJobItem(job));
        }

        List<DeployTask> failedTasks = deployTaskMapper.selectList(
                new QueryWrapper<DeployTask>()
                        .eq("task_status", "failed")
                        .orderByDesc("update_time")
                        .last("LIMIT " + limit));
        for (DeployTask task : failedTasks) {
            StatisticsOverviewDTO.RecentItem item = new StatisticsOverviewDTO.RecentItem();
            item.setType("deploy_task");
            item.setId(task.getId());
            item.setTitle(firstNonEmpty(task.getTaskName(), task.getTaskNumber(), "上线任务"));
            item.setMeta("上线任务 · " + firstNonEmpty(task.getTaskNumber(), "-"));
            item.setStatus("FAILED");
            item.setTimeText(formatTime(task.getUpdateTime() != null ? task.getUpdateTime() : task.getCreateTime()));
            item.setLinkSection("tasks");
            items.add(item);
        }

        items.sort(Comparator.comparing(StatisticsOverviewDTO.RecentItem::getTimeText,
                Comparator.nullsLast(Comparator.reverseOrder())));
        if (items.size() > limit) {
            return items.subList(0, limit);
        }
        return items;
    }

    private List<StatisticsOverviewDTO.ViewShortcut> listViewShortcuts() {
        try {
            List<PipelineBoardViewDTO> views = pipelineViewService.list();
            List<StatisticsOverviewDTO.ViewShortcut> result = new ArrayList<>();
            for (PipelineBoardViewDTO view : views) {
                StatisticsOverviewDTO.ViewShortcut sc = new StatisticsOverviewDTO.ViewShortcut();
                sc.setId(view.getId());
                sc.setName(view.getName());
                sc.setEnvId(view.getEnvId());
                sc.setEnvName(view.getEnvName());
                result.add(sc);
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private StatisticsOverviewDTO.RecentItem toJobItem(BuildJob job) {
        StatisticsOverviewDTO.RecentItem item = new StatisticsOverviewDTO.RecentItem();
        item.setType("job");
        item.setId(job.getId());
        item.setTitle(firstNonEmpty(job.getTaskName(), job.getJobNumber(), "流水线任务"));
        String serviceName = resolveServiceName(job.getServiceId());
        String envName = resolveEnvName(job.getEnvId());
        item.setMeta(serviceName + " / " + envName + (StringUtils.hasText(job.getBranch()) ? " / " + job.getBranch() : ""));
        item.setStatus(normalizeStatus(job.getStatus()));
        item.setTimeText(formatTime(job.getUpdateTime() != null ? job.getUpdateTime() : job.getCreateTime()));
        item.setLinkSection("pipeline");
        return item;
    }

    private Set<Long> resolveAllowedEnvIds() {
        if (pipelineViewService.canAccessAllViews()) {
            return null; // null = 不限制
        }
        Set<Long> envIds = pipelineViewService.listAccessibleEnvIds();
        return envIds != null ? envIds : Collections.emptySet();
    }

    private void applyEnvFilter(QueryWrapper<BuildJob> wrapper, Set<Long> allowedEnvIds) {
        if (allowedEnvIds == null) {
            return;
        }
        if (allowedEnvIds.isEmpty()) {
            wrapper.eq("id", -1L);
            return;
        }
        wrapper.in("env_id", allowedEnvIds);
    }

    private String resolveServiceName(Long serviceId) {
        if (serviceId == null) {
            return "-";
        }
        com.opsflow.dao.model.Service service = serviceMapper.selectById(serviceId);
        return service != null && StringUtils.hasText(service.getName()) ? service.getName() : ("svc-" + serviceId);
    }

    private String resolveEnvName(Long envId) {
        if (envId == null) {
            return "-";
        }
        Env env = envMapper.selectById(envId);
        return env != null && StringUtils.hasText(env.getName()) ? env.getName() : ("env-" + envId);
    }

    private boolean isNodeOnline(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String s = status.trim();
        return "在线".equals(s) || "ONLINE".equalsIgnoreCase(s) || "online".equalsIgnoreCase(s);
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? "-" : time.format(TIME_FMT);
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return "-";
        }
        for (String v : values) {
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        return "-";
    }

    private String currentUsername() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object user = session != null ? session.getAttribute("user") : null;
                if (user != null && StringUtils.hasText(String.valueOf(user))) {
                    return String.valueOf(user).trim();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }
}
