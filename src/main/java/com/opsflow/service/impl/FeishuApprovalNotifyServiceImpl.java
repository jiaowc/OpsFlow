package com.opsflow.service.impl;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.opsflow.integration.feishu.FeishuJson;
import com.opsflow.dao.mapper.ApprovalRecordMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.integration.feishu.FeishuClient;
import com.opsflow.integration.feishu.FeishuConfig;
import com.opsflow.service.FeishuApprovalNotifyService;
import com.opsflow.service.FeishuUserResolveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class FeishuApprovalNotifyServiceImpl implements FeishuApprovalNotifyService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private FeishuConfig feishuConfig;

    @Autowired
    private FeishuClient feishuClient;

    @Autowired
    private FeishuUserResolveService feishuUserResolveService;

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    private static final int MESSAGE_IDS_MAX_LENGTH = 2000;

    @Override
    public void notifyPendingApproval(ApprovalRecord record, DeployTask task) {
        if (!feishuConfig.isEnabled() || !feishuClient.isConfigured()) {
            log.debug("飞书审批通知未启用，跳过推送 recordId={}", record != null ? record.getId() : null);
            return;
        }
        if (record == null || record.getId() == null) {
            return;
        }
        if (!record.listApproverCandidates().isEmpty()) {
            notifyCandidates(record, task);
            return;
        }

        String feishuId = record.getApproverFeishuId();
        if (!StringUtils.hasText(feishuId)) {
            feishuId = feishuUserResolveService.resolveFeishuUserId(record.getApprover());
            if (StringUtils.hasText(feishuId)) {
                record.setApproverFeishuId(feishuId);
            }
        }
        if (!StringUtils.hasText(feishuId)) {
            log.warn("审批人未绑定飞书，无法推送卡片: recordId={}, approver={}",
                    record.getId(), record.getApprover());
            return;
        }

        ObjectNode card = buildPendingCard(record, task);
        String messageId = feishuClient.sendInteractiveCard(feishuId, card);
        if (!StringUtils.hasText(messageId)) {
            log.error("飞书审批卡片发送失败: recordId={}, feishuId={}", record.getId(), feishuId);
            return;
        }

        record.setApproverFeishuId(feishuId);
        record.setFeishuMessageId(messageId);
        record.setUpdateTime(LocalDateTime.now());
        approvalRecordMapper.updateById(record);
        log.info("已推送飞书审批卡片: recordId={}, messageId={}", record.getId(), messageId);
    }

    /** 多个候选审批人：给每个已绑定飞书的人各发一张卡片 */
    private void notifyCandidates(ApprovalRecord record, DeployTask task) {
        ObjectNode card = buildPendingCard(record, task);
        List<String> messageIds = new ArrayList<>();
        int length = 0;
        for (String username : record.listApproverCandidates()) {
            String feishuId = feishuUserResolveService.resolveFeishuUserId(username);
            if (!StringUtils.hasText(feishuId)) {
                log.warn("候选审批人未绑定飞书，跳过: recordId={}, username={}", record.getId(), username);
                continue;
            }
            String messageId = feishuClient.sendInteractiveCard(feishuId, card);
            if (!StringUtils.hasText(messageId)) {
                log.error("飞书审批卡片发送失败: recordId={}, username={}", record.getId(), username);
                continue;
            }
            if (length + messageId.length() + 1 <= MESSAGE_IDS_MAX_LENGTH) {
                messageIds.add(messageId);
                length += messageId.length() + 1;
            }
        }
        if (messageIds.isEmpty()) {
            return;
        }
        record.setFeishuMessageId(String.join(",", messageIds));
        record.setUpdateTime(LocalDateTime.now());
        approvalRecordMapper.updateById(record);
        log.info("已推送飞书审批卡片给候选审批人: recordId={}, 卡片数={}", record.getId(), messageIds.size());
    }

    @Override
    public void refreshCard(ApprovalRecord record, DeployTask task, String finalStatus, String operatorName) {
        if (!feishuConfig.isEnabled() || !feishuClient.isConfigured()) {
            return;
        }
        if (record == null || !StringUtils.hasText(record.getFeishuMessageId())) {
            return;
        }
        ObjectNode card = buildResultCard(record, task, finalStatus, operatorName);
        for (String messageId : record.getFeishuMessageId().split(",")) {
            if (!StringUtils.hasText(messageId)) {
                continue;
            }
            if (!feishuClient.updateInteractiveMessage(messageId.trim(), card)) {
                log.warn("更新飞书审批卡片失败: recordId={}, messageId={}", record.getId(), messageId);
            }
        }
    }

    @Override
    public ObjectNode buildPendingCard(ApprovalRecord record, DeployTask task) {
        ObjectNode card = FeishuJson.newObject();
        ObjectNode config = FeishuJson.newObject();
        config.put("wide_screen_mode", true);
        card.set("config", config);

        ObjectNode header = FeishuJson.newObject();
        header.put("template", "orange");
        ObjectNode title = FeishuJson.newObject();
        title.put("tag", "plain_text");
        title.put("content", "上线任务待审批通知");
        header.set("title", title);
        card.set("header", header);

        ArrayNode elements = FeishuJson.newArray();
        elements.add(markdown(
                "**任务编号：** " + safe(task != null ? task.getTaskNumber() : record.getTaskNumber()) + "\n"
                        + "**任务名称：** " + safe(task != null ? task.getTaskName() : "-") + "\n"
                        + "**上线集群：** " + safe(resolveClusterName(task)) + "\n"
                        + "**Namespace：** " + safe(task != null ? task.getK8sNamespace() : "-") + "\n"
                        + "**创建人：** " + safe(task != null ? task.getCreatorName() : "-") + "\n"
                        + "**审批步骤：** 第 " + record.getCurrentStep() + " 步"
                        + (!record.listApproverCandidates().isEmpty()
                            ? "（" + safe(record.getApproverName()) + "，任一人审批即可）" : "") + "\n"
                        + "**上线模块：**\n" + formatModules(task)
        ));

        String portal = feishuConfig.getPortalUrl();
        if (StringUtils.hasText(portal)) {
            elements.add(markdown("请前往 OpsFlow 完成审批：[" + portal + "](" + portal + ")"));
        } else {
            elements.add(note("请登录 OpsFlow「上线任务」页面完成审批（飞书内暂不支持直接同意/拒绝）。"));
        }
        card.set("elements", elements);
        return card;
    }

    @Override
    public ObjectNode buildResultCard(ApprovalRecord record, DeployTask task, String finalStatus, String operatorName) {
        String statusText;
        String template;
        if ("approved".equalsIgnoreCase(finalStatus)) {
            statusText = "已通过";
            template = "green";
        } else if ("rejected".equalsIgnoreCase(finalStatus)) {
            statusText = "已拒绝";
            template = "red";
        } else {
            statusText = finalStatus != null ? finalStatus : "已处理";
            template = "grey";
        }

        ObjectNode card = FeishuJson.newObject();
        ObjectNode config = FeishuJson.newObject();
        config.put("wide_screen_mode", true);
        card.set("config", config);

        ObjectNode header = FeishuJson.newObject();
        header.put("template", template);
        ObjectNode title = FeishuJson.newObject();
        title.put("tag", "plain_text");
        title.put("content", "上线审批" + statusText);
        header.set("title", title);
        card.set("header", header);

        ArrayNode elements = FeishuJson.newArray();
        elements.add(markdown(
                "**任务编号：** " + safe(task != null ? task.getTaskNumber() : record.getTaskNumber()) + "\n"
                        + "**任务名称：** " + safe(task != null ? task.getTaskName() : "-") + "\n"
                        + "**处理结果：** " + statusText + "\n"
                        + "**处理人：** " + safe(operatorName) + "\n"
                        + "**处理时间：** " + LocalDateTime.now().format(TIME_FMT) + "\n"
                        + "**审批意见：** " + safe(record.getComment())
        ));
        elements.add(note("本条为审批结果通知，请在 OpsFlow 中查看详情。"));
        card.set("elements", elements);
        return card;
    }

    private ObjectNode markdown(String content) {
        ObjectNode div = FeishuJson.newObject();
        div.put("tag", "div");
        ObjectNode text = FeishuJson.newObject();
        text.put("tag", "lark_md");
        text.put("content", content);
        div.set("text", text);
        return div;
    }

    private ObjectNode note(String content) {
        ObjectNode note = FeishuJson.newObject();
        note.put("tag", "note");
        ArrayNode elements = FeishuJson.newArray();
        ObjectNode text = FeishuJson.newObject();
        text.put("tag", "plain_text");
        text.put("content", content);
        elements.add(text);
        note.set("elements", elements);
        return note;
    }

    private String resolveClusterName(DeployTask task) {
        if (task == null || task.getClusterId() == null) {
            return null;
        }
        Cluster cluster = clusterMapper.selectById(task.getClusterId());
        return cluster != null ? cluster.getName() : String.valueOf(task.getClusterId());
    }

    private String formatModules(DeployTask task) {
        if (task == null || !StringUtils.hasText(task.getDeployModules())) {
            return "-";
        }
        try {
            List<String> modules = FeishuJson.parseStringList(task.getDeployModules());
            if (modules == null || modules.isEmpty()) {
                return "-";
            }
            StringBuilder sb = new StringBuilder();
            for (String m : modules) {
                sb.append("- ").append(m).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return task.getDeployModules();
        }
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
