package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.api.dto.InboxMessageDTO;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.InboxMessageMapper;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.InboxMessage;
import com.opsflow.service.InboxMessageService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InboxMessageServiceImpl implements InboxMessageService {

    public static final String BIZ_APPROVAL_RECORD = "approval_record";

    @Autowired
    private InboxMessageMapper inboxMessageMapper;

    @Autowired
    private ClusterMapper clusterMapper;

    @Override
    public void sendApprovalNotify(ApprovalRecord record, DeployTask task) {
        if (record == null || !StringUtils.hasText(record.getApprover())) {
            // 未指定审批人：任意人可批，不发个人站内信
            return;
        }
        String username = record.getApprover().trim();
        String clusterName = "-";
        if (task != null && task.getClusterId() != null) {
            Cluster cluster = clusterMapper.selectById(task.getClusterId());
            if (cluster != null) {
                clusterName = cluster.getName();
            }
        }
        String title = "上线审批待办：" + (task != null && StringUtils.hasText(task.getTaskName())
                ? task.getTaskName() : record.getTaskNumber());
        String content = "任务编号：" + safe(task != null ? task.getTaskNumber() : record.getTaskNumber())
                + "\n任务名称：" + safe(task != null ? task.getTaskName() : "-")
                + "\n上线集群：" + clusterName
                + "\nNamespace：" + safe(task != null ? task.getK8sNamespace() : "-")
                + "\n审批步骤：第 " + record.getCurrentStep() + " 步"
                + "\n请在「上线任务」中完成审批。";

        LocalDateTime now = LocalDateTime.now();
        InboxMessage msg = new InboxMessage();
        msg.setUsername(username);
        msg.setTitle(title);
        msg.setContent(content);
        msg.setMsgType("approval");
        msg.setBizType(BIZ_APPROVAL_RECORD);
        msg.setBizId(String.valueOf(record.getId()));
        msg.setLinkPath("/tasks");
        msg.setReadFlag(0);
        msg.setCreateTime(now);
        msg.setUpdateTime(now);
        inboxMessageMapper.insert(msg);
    }

    @Override
    public List<InboxMessageDTO> listMine(String username, Boolean unreadOnly, int limit) {
        if (!StringUtils.hasText(username)) {
            return new ArrayList<>();
        }
        QueryWrapper<InboxMessage> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username.trim());
        if (Boolean.TRUE.equals(unreadOnly)) {
            wrapper.eq("read_flag", 0);
        }
        wrapper.orderByDesc("create_time");
        if (limit > 0) {
            wrapper.last("LIMIT " + Math.min(limit, 100));
        }
        return inboxMessageMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public long countUnread(String username) {
        if (!StringUtils.hasText(username)) {
            return 0;
        }
        Long count = inboxMessageMapper.selectCount(
                new QueryWrapper<InboxMessage>().eq("username", username.trim()).eq("read_flag", 0));
        return count != null ? count : 0;
    }

    @Override
    public void markRead(Long id, String username) {
        if (id == null || !StringUtils.hasText(username)) {
            return;
        }
        InboxMessage msg = inboxMessageMapper.selectById(id);
        if (msg == null || !username.trim().equalsIgnoreCase(msg.getUsername())) {
            return;
        }
        msg.setReadFlag(1);
        msg.setUpdateTime(LocalDateTime.now());
        inboxMessageMapper.updateById(msg);
    }

    @Override
    public void markAllRead(String username) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        UpdateWrapper<InboxMessage> update = new UpdateWrapper<>();
        update.eq("username", username.trim()).eq("read_flag", 0);
        update.set("read_flag", 1);
        update.set("update_time", LocalDateTime.now());
        inboxMessageMapper.update(null, update);
    }

    @Override
    public void markReadByBiz(String username, String bizType, String bizId) {
        if (!StringUtils.hasText(bizType) || !StringUtils.hasText(bizId)) {
            return;
        }
        UpdateWrapper<InboxMessage> update = new UpdateWrapper<>();
        update.eq("biz_type", bizType).eq("biz_id", bizId).eq("read_flag", 0);
        if (StringUtils.hasText(username)) {
            update.eq("username", username.trim());
        }
        update.set("read_flag", 1);
        update.set("update_time", LocalDateTime.now());
        inboxMessageMapper.update(null, update);
    }

    private InboxMessageDTO toDto(InboxMessage msg) {
        InboxMessageDTO dto = new InboxMessageDTO();
        BeanUtils.copyProperties(msg, dto);
        dto.setActionable(msg.getReadFlag() == null || msg.getReadFlag() == 0);
        return dto;
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value : "-";
    }
}
