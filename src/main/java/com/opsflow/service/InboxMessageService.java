package com.opsflow.service;

import com.opsflow.api.dto.InboxMessageDTO;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;

import java.util.List;

public interface InboxMessageService {

    void sendApprovalNotify(ApprovalRecord record, DeployTask task);

    List<InboxMessageDTO> listMine(String username, Boolean unreadOnly, int limit);

    long countUnread(String username);

    void markRead(Long id, String username);

    void markAllRead(String username);

    void markReadByBiz(String username, String bizType, String bizId);
}
