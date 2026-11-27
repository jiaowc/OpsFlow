package com.opsflow.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.BuildJobStageMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.BuildJobStage;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Project;
import com.opsflow.integration.notify.NotifyMessage;
import com.opsflow.service.InboxMessageService;
import com.opsflow.service.NotifyConfigService;
import com.opsflow.service.PipelineNotifyService;
import com.opsflow.service.SystemConfigService;
import com.opsflow.spi.JobNotifyContext;
import com.opsflow.spi.JobNotifyExtension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 流水线结果通知实现：组装消息、发站内信给触发人；外部通道由 {@link JobNotifyExtension} 负责（社区版不推送）。
 * <p>发送放在独立的小线程池里：通知慢或对端不可达时，不占用流水线执行线程，也不影响构建任务终态落库。
 * 队列满时直接丢弃并记日志——通知是尽力而为，不能反过来拖住发布。</p>
 */
@Slf4j
@Service
public class PipelineNotifyServiceImpl implements PipelineNotifyService {

    static final int MAX_ERROR_LENGTH = 300;

    @Autowired
    private BuildJobMapper buildJobMapper;
    @Autowired
    private ServiceMapper serviceMapper;
    @Autowired
    private EnvMapper envMapper;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private InboxMessageService inboxMessageService;
    @Autowired
    private NotifyConfigService notifyConfigService;
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private JobNotifyExtension jobNotifyExtension;
    @Autowired
    private PipelineMapper pipelineMapper;
    @Autowired
    private BuildJobStageMapper buildJobStageMapper;

    /** 前端流水线页的深链：打开某次 Job 所在任务组的详情 */
    static final String JOB_DETAIL_PATH = "/pipeline?job=";

    private static final java.time.format.DateTimeFormatter START_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 通知步骤消息里的变量：${名称}，名称只含字母 */
    private static final java.util.regex.Pattern STEP_VARIABLE = java.util.regex.Pattern.compile("\\$\\{([A-Za-z]+)\\}");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService executor = new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "pipeline-notify");
                t.setDaemon(true);
                return t;
            },
            (r, pool) -> log.warn("通知队列已满，丢弃一条流水线通知"));

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public void notifyJobFinished(BuildJob job) {
        if (job == null || job.getId() == null) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    doNotifyJobFinished(job);
                } catch (Exception e) {
                    log.error("流水线结果通知失败 jobId={}", job.getId(), e);
                }
            });
        } catch (Exception e) {
            log.warn("提交流水线结果通知失败 jobId={}: {}", job.getId(), e.getMessage());
        }
    }

    void doNotifyJobFinished(BuildJob job) {
        boolean success = BuildStatus.SUCCESS.equals(job.getStatus());
        if (!success && !BuildStatus.FAILED.equals(job.getStatus())) {
            return;
        }
        com.opsflow.dao.model.Service service = job.getServiceId() == null ? null : serviceMapper.selectById(job.getServiceId());
        Env env = job.getEnvId() == null ? null : envMapper.selectById(job.getEnvId());
        Long projectId = service != null ? service.getProjectId() : null;
        JobNotifyContext context = new JobNotifyContext(job, service, env, projectId, success);
        NotifyMessage message = buildJobMessage(context);

        sendInbox(job, message, success);

        try {
            jobNotifyExtension.onJobFinished(context, message);
        } catch (Exception e) {
            log.error("外部通道通知失败 jobId={}: {}", job.getId(), e.getMessage());
        }
    }

    @Override
    public String sendStepMessage(Long buildJobId, String text, java.util.List<Long> channelIds) {
        BuildJob job = buildJobId == null ? null : buildJobMapper.selectById(buildJobId);
        if (job == null) {
            return "构建任务不存在，跳过";
        }
        com.opsflow.dao.model.Service service = job.getServiceId() == null ? null : serviceMapper.selectById(job.getServiceId());
        Env env = job.getEnvId() == null ? null : envMapper.selectById(job.getEnvId());
        Long projectId = service != null ? service.getProjectId() : null;
        JobNotifyContext context = new JobNotifyContext(job, service, env, projectId, null);
        NotifyMessage message = buildJobMessage(context);
        message.setTitle("流水线通知：" + jobLabel(job, service, env));
        message.setDetail(StringUtils.hasText(text) ? text.trim() : null);
        return jobNotifyExtension.sendStepMessage(context, message,
                channelIds != null ? channelIds : java.util.Collections.emptyList());
    }

    private void sendInbox(BuildJob job, NotifyMessage message, boolean success) {
        if (!notifyConfigService.isInboxEnabled() || !notifyConfigService.isPipelineInboxEnabled()) {
            return;
        }
        String username = BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, job);
        // resolveCreatorName 在找不到用户时返回占位文本，这种情况没有收件人
        if (!StringUtils.hasText(username) || "-".equals(username) || username.startsWith("用户#")) {
            return;
        }
        try {
            StringBuilder content = new StringBuilder();
            message.getFields().forEach((k, v) -> content.append(k).append("：").append(v).append('\n'));
            if (StringUtils.hasText(message.getDetail())) {
                // detail 带飞书 Markdown 加粗标记，站内信是纯文本
                content.append(message.getDetail().replace("**", ""));
            }
            inboxMessageService.sendPipelineResult(username, message.getTitle(), content.toString().trim(),
                    String.valueOf(job.getId()));
        } catch (Exception e) {
            log.error("流水线结果站内信发送失败 jobId={}", job.getId(), e);
        }
    }

    private NotifyMessage buildJobMessage(JobNotifyContext context) {
        BuildJob job = context.getJob();
        com.opsflow.dao.model.Service service = context.getService();
        Env env = context.getEnv();
        Long projectId = context.getProjectId();
        Boolean success = context.getSuccess();
        Pipeline pipeline = findPipeline(job);
        Map<String, Object> params = parseParams(job.getBuildParameters());
        boolean rollback = "true".equalsIgnoreCase(String.valueOf(params.get("rollback")));
        NotifyMessage message = new NotifyMessage();
        message.setSuccess(success);
        if (success != null) {
            String runType = runType(pipeline, rollback);
            message.setTitle((success ? "✅ " : "❌ ") + (runType != null ? runType : "流水线")
                    + (success ? "成功：" : "失败：") + jobLabel(job, service, env));
        }
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            message.field("项目", project != null ? project.getName() : null);
        }
        message.field("服务", service != null ? (StringUtils.hasText(service.getName()) ? service.getName() : service.getCode()) : null);
        message.field("环境", env != null ? env.getName() : null);
        message.field("流水线", pipeline != null ? pipeline.getName() : null);
        message.field("分支/Tag", job.getBranch());
        message.field("镜像", StringUtils.hasText(job.getImageFullName()) ? job.getImageFullName() : job.getImageTag());
        message.field("任务编号", job.getJobNumber());
        // 扩展补充的字段（如关联的上线任务）排在任务编号之后、触发方式之前
        jobNotifyExtension.enrich(context, message);
        message.field("触发方式", triggerLabel(job, params, rollback));
        String creator = BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, job);
        message.field("触发人", "-".equals(creator) ? null : creator);
        if (job.getStartTime() != null) {
            message.field("开始时间", job.getStartTime().format(START_TIME_FORMAT));
        }
        if (job.getStartTime() != null && job.getEndTime() != null) {
            message.field("耗时", formatDuration(Duration.between(job.getStartTime(), job.getEndTime())));
        }
        if (Boolean.FALSE.equals(success)) {
            message.field("失败步骤", failedStepLabel(job.getId()));
        }
        if (Boolean.FALSE.equals(success) && StringUtils.hasText(job.getErrorMessage())) {
            String reason = summarizeError(job.getErrorMessage(), MAX_ERROR_LENGTH);
            if (StringUtils.hasText(reason)) {
                message.setDetail("**失败原因**：" + reason);
            }
        }
        // 扩展 enrich 里给的 linkUrl 视为门户地址（如企业版飞书配置的门户），没有时用系统设置；再拼上本次 Job 的详情页
        String portal = StringUtils.hasText(message.getLinkUrl()) ? message.getLinkUrl() : portalLink();
        message.setLinkUrl(jobDetailUrl(portal, job.getId()));
        return message;
    }

    private Pipeline findPipeline(BuildJob job) {
        if (job.getPipelineTemplateId() == null) {
            return null;
        }
        try {
            return pipelineMapper.selectById(job.getPipelineTemplateId());
        } catch (Exception e) {
            log.debug("查询流水线失败 pipelineId={}: {}", job.getPipelineTemplateId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseParams(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return map != null ? map : Collections.emptyMap();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** 标题里的运行类型：回滚；CI 流水线是构建，CD / CI+CD 是发布；判断不了返回 null */
    static String runType(Pipeline pipeline, boolean rollback) {
        if (rollback) {
            return "回滚";
        }
        String type = pipeline != null ? pipeline.getPipelineType() : null;
        if (type == null) {
            return null;
        }
        switch (type.trim().toLowerCase()) {
            case "ci":
                return "构建";
            case "cd":
            case "cicd":
                return "发布";
            default:
                return null;
        }
    }

    /**
     * 触发方式：只写现有数据能确定的。手动新建和重新构建产生的 Job 字段完全一样，分不清，不写。
     */
    static String triggerLabel(BuildJob job, Map<String, Object> params, boolean rollback) {
        if (rollback) {
            return "回滚";
        }
        if (job.getDeployTaskId() != null) {
            return "上线任务";
        }
        Object creator = params.get(BuildJobCreatorUtils.CREATOR_USERNAME_KEY);
        String name = creator != null ? String.valueOf(creator).trim() : "";
        // PipelineWebhookTriggerService 写入的创建人：webhook 或 webhook(推送人)
        if ("webhook".equalsIgnoreCase(name) || name.toLowerCase().startsWith("webhook(")) {
            return "Webhook（代码推送）";
        }
        return null;
    }

    /** 失败步骤：本 Job 第一条失败的阶段，「第 N 步 步骤名」，N 是它在本 Job 全部阶段里的位置；取不到返回 null */
    String failedStepLabel(Long jobId) {
        if (jobId == null) {
            return null;
        }
        try {
            List<BuildJobStage> stages = buildJobStageMapper.selectList(new QueryWrapper<BuildJobStage>()
                    .eq("build_job_id", jobId).orderByAsc("step_order").orderByAsc("id"));
            if (stages == null) {
                return null;
            }
            for (int i = 0; i < stages.size(); i++) {
                BuildJobStage stage = stages.get(i);
                if ("FAILURE".equalsIgnoreCase(stage.getStatus())) {
                    String name = StringUtils.hasText(stage.getStepName()) ? stage.getStepName().trim() : stage.getStepType();
                    return "第 " + (i + 1) + " 步" + (StringUtils.hasText(name) ? " " + name : "");
                }
            }
        } catch (Exception e) {
            log.debug("查询失败步骤失败 jobId={}: {}", jobId, e.getMessage());
        }
        return null;
    }

    /** 门户地址 + 本次 Job 的详情页；门户地址没配时返回 null（不出按钮） */
    static String jobDetailUrl(String portal, Long jobId) {
        if (!StringUtils.hasText(portal)) {
            return null;
        }
        String base = portal.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return jobId == null ? base : base + JOB_DETAIL_PATH + jobId;
    }

    @Override
    public String renderStepMessage(Long buildJobId, String template, Map<String, String> runtime) {
        if (!StringUtils.hasText(template) || template.indexOf("${") < 0) {
            return template;
        }
        BuildJob job = buildJobId == null ? null : buildJobMapper.selectById(buildJobId);
        Map<String, String> vars = new HashMap<>();
        if (job != null) {
            com.opsflow.dao.model.Service service = job.getServiceId() == null ? null : serviceMapper.selectById(job.getServiceId());
            Env env = job.getEnvId() == null ? null : envMapper.selectById(job.getEnvId());
            Project project = service != null && service.getProjectId() != null ? projectMapper.selectById(service.getProjectId()) : null;
            Pipeline pipeline = findPipeline(job);
            vars.put("service", service != null ? (StringUtils.hasText(service.getCode()) ? service.getCode() : service.getName()) : null);
            vars.put("env", env != null ? env.getName() : null);
            vars.put("branch", job.getBranch());
            vars.put("image", StringUtils.hasText(job.getImageFullName()) ? job.getImageFullName() : job.getImageTag());
            vars.put("jobNumber", job.getJobNumber());
            String creator = BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, job);
            vars.put("trigger", "-".equals(creator) ? null : creator);
            vars.put("project", project != null ? project.getName() : null);
            vars.put("pipeline", pipeline != null ? pipeline.getName() : null);
        }
        // 运行中镜像还没回写到 Job 时，用当前步骤参数里的镜像
        if (!StringUtils.hasText(vars.get("image")) && runtime != null) {
            vars.put("image", runtime.get("image"));
        }
        return renderVariables(template, vars);
    }

    /**
     * 一次性替换 ${变量}：已知变量换成值（空值换成空串），未知变量原样保留；替换进去的值不再解析。
     */
    static String renderVariables(String template, Map<String, String> vars) {
        if (template == null) {
            return null;
        }
        java.util.regex.Matcher m = STEP_VARIABLE.matcher(template);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            String replacement = vars.containsKey(name) ? (vars.get(name) != null ? vars.get(name) : "") : m.group(0);
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String jobLabel(BuildJob job, com.opsflow.dao.model.Service service, Env env) {
        String name = service != null
                ? (StringUtils.hasText(service.getCode()) ? service.getCode() : service.getName())
                : (StringUtils.hasText(job.getTaskName()) ? job.getTaskName() : job.getJobNumber());
        return name + (env != null && StringUtils.hasText(env.getName()) ? " → " + env.getName() : "");
    }

    private String portalLink() {
        String portal = systemConfigService.get("notify", "portalUrl");
        if (!StringUtils.hasText(portal)) {
            return null;
        }
        return portal.trim();
    }

    static String formatDuration(Duration duration) {
        long seconds = Math.max(0, duration.getSeconds());
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    /** 终端颜色等 ANSI 控制序列（Maven、npm 的输出里很常见） */
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

    /**
     * 构建工具在失败输出最后附带的通用提示（怎么看堆栈、帮助链接、分隔线等），对判断失败原因没有帮助。
     * Maven 的这段提示有好几百字，不去掉的话会把前面真正的报错挤出通知。
     */
    private static final java.util.regex.Pattern BOILERPLATE_LINE = java.util.regex.Pattern.compile(
            "^\\[(ERROR|INFO|WARNING)\\]\\s*(-*|"
                    + "-> \\[Help \\d+\\]|"
                    + "\\[Help \\d+\\] .*|"
                    + "To see the full stack trace.*|"
                    + "Re-run Maven using.*|"
                    + "For more information about the errors.*|"
                    + "After correcting the problems.*|"
                    + "mvn <args> -rf .*|"
                    + "Total time:.*|Finished at:.*)$");

    /**
     * 把命令输出整理成适合放进通知的失败原因：去掉 ANSI 控制符和构建工具的通用收尾提示，保留结尾的若干行。
     * <p>命令失败时真正的报错在输出末尾（开头是工具的启动信息），所以超长时从后往前按整行截取。</p>
     */
    static String summarizeError(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String text = ANSI.matcher(raw).replaceAll("").replace("\r", "").trim();
        if (text.length() <= max) {
            return text;
        }
        StringBuilder meaningful = new StringBuilder();
        for (String line : text.split("\n")) {
            if (!BOILERPLATE_LINE.matcher(line.trim()).matches()) {
                meaningful.append(line).append('\n');
            }
        }
        // 全是提示行时退回原文，至少不会给出空的失败原因
        if (meaningful.toString().trim().length() > 0) {
            text = meaningful.toString().trim();
            if (text.length() <= max) {
                return text;
            }
        }
        String[] lines = text.split("\n");
        StringBuilder tail = new StringBuilder();
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            if (tail.length() + line.length() + 1 > max) {
                break;
            }
            tail.insert(0, tail.length() > 0 ? line + "\n" : line);
        }
        if (tail.length() == 0) {
            // 最后一行本身就超长：只留它的结尾
            String last = text.substring(text.length() - max);
            return "..." + (Character.isLowSurrogate(last.charAt(0)) ? last.substring(1) : last);
        }
        return "...\n" + tail;
    }
}
