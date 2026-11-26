package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.api.dto.InboxMessageDTO;
import com.opsflow.api.dto.InboxMessageDraft;
import com.opsflow.dao.mapper.InboxMessageMapper;
import com.opsflow.dao.model.InboxMessage;
import com.opsflow.service.InboxMessageService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class InboxMessageServiceImpl implements InboxMessageService {

    public static final String BIZ_APPROVAL_RECORD = "approval_record";
    public static final String BIZ_BUILD_JOB = "build_job";

    @Autowired
    private InboxMessageMapper inboxMessageMapper;

    @Override
    public void send(Collection<String> usernames, InboxMessageDraft draft) {
        if (usernames == null || draft == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String username : usernames) {
            if (!StringUtils.hasText(username)) {
                continue;
            }
            InboxMessage msg = new InboxMessage();
            msg.setUsername(username.trim());
            msg.setTitle(draft.getTitle());
            msg.setContent(draft.getContent());
            msg.setMsgType(draft.getMsgType());
            msg.setBizType(draft.getBizType());
            msg.setBizId(draft.getBizId());
            msg.setLinkPath(draft.getLinkPath());
            msg.setReadFlag(0);
            msg.setCreateTime(now);
            msg.setUpdateTime(now);
            inboxMessageMapper.insert(msg);
        }
    }

    @Override
    public void sendPipelineResult(String username, String title, String content, String buildJobId) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        InboxMessage msg = new InboxMessage();
        msg.setUsername(username.trim());
        msg.setTitle(truncate(title, 200));
        msg.setContent(truncate(content, 2000));
        msg.setMsgType("pipeline");
        msg.setBizType(BIZ_BUILD_JOB);
        msg.setBizId(buildJobId);
        msg.setLinkPath("/pipeline");
        msg.setReadFlag(0);
        msg.setCreateTime(now);
        msg.setUpdateTime(now);
        inboxMessageMapper.insert(msg);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
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
}
