package com.opsflow.service;

import com.opsflow.api.dto.InboxMessageDTO;
import com.opsflow.api.dto.InboxMessageDraft;

import java.util.Collection;
import java.util.List;

public interface InboxMessageService {

    /**
     * 给一批用户各发一条站内信（与业务无关，内容由调用方组装）。空用户名会被跳过。
     */
    void send(Collection<String> usernames, InboxMessageDraft draft);

    /**
     * 流水线构建/发布结果站内信，发给触发人。
     *
     * @param username   接收人用户名
     * @param buildJobId 构建任务 ID（写入 bizId）
     */
    void sendPipelineResult(String username, String title, String content, String buildJobId);

    List<InboxMessageDTO> listMine(String username, Boolean unreadOnly, int limit);

    long countUnread(String username);

    void markRead(Long id, String username);

    void markAllRead(String username);

    void markReadByBiz(String username, String bizType, String bizId);
}
