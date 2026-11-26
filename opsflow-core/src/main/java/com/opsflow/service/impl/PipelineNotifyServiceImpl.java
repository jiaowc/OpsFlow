package com.opsflow.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.common.util.BuildJobCreatorUtils;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.BuildJob;
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
    public String sendStepMessage(Long buildJobId, String text) {
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
        return jobNotifyExtension.sendStepMessage(context, message);
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
        NotifyMessage message = new NotifyMessage();
        message.setSuccess(success);
        if (success != null) {
            message.setTitle((success ? "✅ 流水线成功：" : "❌ 流水线失败：") + jobLabel(job, service, env));
        }
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            message.field("项目", project != null ? project.getName() : null);
        }
        message.field("服务", service != null ? (StringUtils.hasText(service.getName()) ? service.getName() : service.getCode()) : null);
        message.field("环境", env != null ? env.getName() : null);
        message.field("分支/Tag", job.getBranch());
        message.field("镜像", StringUtils.hasText(job.getImageFullName()) ? job.getImageFullName() : job.getImageTag());
        message.field("任务编号", job.getJobNumber());
        // 扩展补充的字段（如关联的上线任务）排在任务编号之后、触发人之前
        jobNotifyExtension.enrich(context, message);
        String creator = BuildJobCreatorUtils.resolveCreatorName(objectMapper, userMapper, job);
        message.field("触发人", "-".equals(creator) ? null : creator);
        if (job.getStartTime() != null && job.getEndTime() != null) {
            message.field("耗时", formatDuration(Duration.between(job.getStartTime(), job.getEndTime())));
        }
        if (Boolean.FALSE.equals(success) && StringUtils.hasText(job.getErrorMessage())) {
            String reason = summarizeError(job.getErrorMessage(), MAX_ERROR_LENGTH);
            if (StringUtils.hasText(reason)) {
                message.setDetail("**失败原因**：" + reason);
            }
        }
        if (!StringUtils.hasText(message.getLinkUrl())) {
            message.setLinkUrl(portalLink());
        }
        return message;
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
