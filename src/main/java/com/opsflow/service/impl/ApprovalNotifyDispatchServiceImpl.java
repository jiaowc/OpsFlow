package com.opsflow.service.impl;

import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalNotifyDispatchService;
import com.opsflow.service.FeishuApprovalNotifyService;
import com.opsflow.service.InboxMessageService;
import com.opsflow.service.NotifyConfigService;
import com.opsflow.integration.feishu.FeishuConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 审批通知分发实现：解析任务 notifyChannels，分别调用站内信与飞书服务。
 */
@Slf4j
@Service
public class ApprovalNotifyDispatchServiceImpl implements ApprovalNotifyDispatchService {

    /** 站内信渠道标识 */
    public static final String CHANNEL_INBOX = "inbox";
    /** 飞书渠道标识 */
    public static final String CHANNEL_FEISHU = "feishu";
    /** 未配置渠道时的默认值 */
    public static final String DEFAULT_CHANNELS = CHANNEL_INBOX;

    @Autowired
    private InboxMessageService inboxMessageService;

    @Autowired
    private FeishuApprovalNotifyService feishuApprovalNotifyService;

    @Autowired
    private NotifyConfigService notifyConfigService;

    @Autowired
    private FeishuConfig feishuConfig;

    /** {@inheritDoc} 各渠道独立 try-catch，单渠道失败不影响其他渠道 */
    @Override
    public void notifyPending(ApprovalRecord record, DeployTask task) {
        if (record == null || task == null) {
            return;
        }
        Set<String> channels = parseChannels(task.getNotifyChannels());
        if (channels.contains(CHANNEL_INBOX)) {
            if (!notifyConfigService.isInboxEnabled()) {
                log.info("站内信通知已关闭，跳过 recordId={}", record.getId());
            } else {
                try {
                    inboxMessageService.sendApprovalNotify(record, task);
                } catch (Exception e) {
                    log.error("站内信审批通知失败 recordId={}", record.getId(), e);
                }
            }
        }
        if (channels.contains(CHANNEL_FEISHU)) {
            if (!feishuConfig.isEnabled()) {
                log.info("飞书审批通知未启用，跳过 recordId={}", record.getId());
            } else {
                try {
                    feishuApprovalNotifyService.notifyPendingApproval(record, task);
                } catch (Exception e) {
                    log.error("飞书审批通知失败 recordId={}", record.getId(), e);
                }
            }
        }
    }

    /** {@inheritDoc} 站内信标记已读；飞书渠道则刷新卡片为已通过 */
    @Override
    public void notifyApproved(ApprovalRecord record, DeployTask task, String operator) {
        if (record == null || task == null) {
            return;
        }
        try {
            inboxMessageService.markReadByBiz(null, InboxMessageServiceImpl.BIZ_APPROVAL_RECORD,
                    String.valueOf(record.getId()));
        } catch (Exception e) {
            log.warn("标记站内信已读失败 recordId={}", record.getId(), e);
        }
        if (parseChannels(task.getNotifyChannels()).contains(CHANNEL_FEISHU)) {
            try {
                feishuApprovalNotifyService.refreshCard(record, task, "approved", operator);
            } catch (Exception e) {
                log.error("飞书卡片刷新失败 recordId={}", record.getId(), e);
            }
        }
    }

    /** {@inheritDoc} 站内信标记已读；飞书渠道则刷新卡片为已拒绝 */
    @Override
    public void notifyRejected(ApprovalRecord record, DeployTask task, String operator) {
        if (record == null || task == null) {
            return;
        }
        try {
            inboxMessageService.markReadByBiz(null, InboxMessageServiceImpl.BIZ_APPROVAL_RECORD,
                    String.valueOf(record.getId()));
        } catch (Exception e) {
            log.warn("标记站内信已读失败 recordId={}", record.getId(), e);
        }
        if (parseChannels(task.getNotifyChannels()).contains(CHANNEL_FEISHU)) {
            try {
                feishuApprovalNotifyService.refreshCard(record, task, "rejected", operator);
            } catch (Exception e) {
                log.error("飞书卡片刷新失败 recordId={}", record.getId(), e);
            }
        }
    }

    /**
     * 解析任务 notifyChannels 字符串为渠道集合；空值默认 inbox。
     *
     * @param raw 逗号/分号/竖线/空白分隔的渠道字符串
     */
    public static Set<String> parseChannels(String raw) {
        Set<String> set = new HashSet<>();
        if (!StringUtils.hasText(raw)) {
            set.add(CHANNEL_INBOX);
            return set;
        }
        Arrays.stream(raw.split("[,;|\\s]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .forEach(set::add);
        if (set.isEmpty()) {
            set.add(CHANNEL_INBOX);
        }
        return set;
    }

    /**
     * 将渠道集合规范化为稳定顺序的逗号分隔字符串（inbox,feishu）。
     *
     * @param channels 前端提交的渠道列表
     */
    public static String normalizeChannels(java.util.Collection<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return DEFAULT_CHANNELS;
        }
        Set<String> set = new HashSet<>();
        for (String c : channels) {
            if (!StringUtils.hasText(c)) {
                continue;
            }
            String v = c.trim().toLowerCase();
            if (CHANNEL_INBOX.equals(v) || CHANNEL_FEISHU.equals(v)) {
                set.add(v);
            }
        }
        if (set.isEmpty()) {
            return DEFAULT_CHANNELS;
        }
        // 稳定顺序：inbox,feishu
        StringBuilder sb = new StringBuilder();
        if (set.contains(CHANNEL_INBOX)) {
            sb.append(CHANNEL_INBOX);
        }
        if (set.contains(CHANNEL_FEISHU)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(CHANNEL_FEISHU);
        }
        return sb.toString();
    }
}
