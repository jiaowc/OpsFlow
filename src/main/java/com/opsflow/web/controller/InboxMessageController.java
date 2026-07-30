package com.opsflow.web.controller;

import com.opsflow.api.dto.InboxMessageDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.service.InboxMessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inbox")
public class InboxMessageController {

    @Autowired
    private InboxMessageService inboxMessageService;

    @GetMapping("/list")
    public List<InboxMessageDTO> list(@RequestParam(required = false) Boolean unreadOnly,
                                      @RequestParam(required = false, defaultValue = "30") Integer limit,
                                      HttpSession session) {
        return inboxMessageService.listMine(currentUser(session), unreadOnly, limit != null ? limit : 30);
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        result.put("count", inboxMessageService.countUnread(currentUser(session)));
        return result;
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id, HttpSession session) {
        inboxMessageService.markRead(id, currentUser(session));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(HttpSession session) {
        inboxMessageService.markAllRead(currentUser(session));
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    private String currentUser(HttpSession session) {
        Object user = session.getAttribute("user");
        if (user == null) {
            throw new BusinessException("未登录");
        }
        return String.valueOf(user);
    }
}
