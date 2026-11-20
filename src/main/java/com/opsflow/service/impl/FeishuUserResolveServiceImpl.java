package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.UserFeishuMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.dao.model.UserFeishu;
import com.opsflow.integration.feishu.FeishuClient;
import com.opsflow.service.FeishuUserResolveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Slf4j
@Service
public class FeishuUserResolveServiceImpl implements FeishuUserResolveService {

    @Autowired
    private UserFeishuMapper userFeishuMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private FeishuClient feishuClient;

    @Override
    public String resolveFeishuUserId(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        UserFeishu binding = findByUsername(username.trim());
        if (binding != null && StringUtils.hasText(binding.getFeishuUserId())) {
            return binding.getFeishuUserId().trim();
        }
        if (binding != null && StringUtils.hasText(binding.getFeishuOpenId())) {
            return binding.getFeishuOpenId().trim();
        }

        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        if (user == null) {
            return null;
        }

        String feishuId = null;
        if (StringUtils.hasText(user.getPhone())) {
            feishuId = feishuClient.getUserIdByMobile(user.getPhone().trim());
        }
        if (!StringUtils.hasText(feishuId) && StringUtils.hasText(user.getEmail())) {
            feishuId = feishuClient.getUserIdByEmail(user.getEmail().trim());
        }
        if (StringUtils.hasText(feishuId)) {
            bindFeishuUser(user.getId(), user.getUsername(), feishuId, user.getPhone(), user.getEmail());
            return feishuId.trim();
        }
        log.warn("无法解析审批人飞书 ID, username={}", username);
        return null;
    }

    @Override
    public String resolveUsernameByFeishuId(String feishuId) {
        if (!StringUtils.hasText(feishuId)) {
            return null;
        }
        String id = feishuId.trim();
        QueryWrapper<UserFeishu> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w.eq("feishu_user_id", id).or().eq("feishu_open_id", id).or().eq("feishu_union_id", id));
        wrapper.last("LIMIT 1");
        UserFeishu binding = userFeishuMapper.selectOne(wrapper);
        if (binding != null && StringUtils.hasText(binding.getUsername())) {
            return binding.getUsername().trim();
        }
        return null;
    }

    @Override
    public void bindFeishuUser(Long userId, String username, String feishuUserId, String mobile, String email) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        UserFeishu existing = findByUsername(username.trim());
        if (existing == null) {
            UserFeishu row = new UserFeishu();
            row.setUserId(userId);
            row.setUsername(username.trim());
            row.setFeishuUserId(trimToNull(feishuUserId));
            row.setFeishuMobile(trimToNull(mobile));
            row.setFeishuEmail(trimToNull(email));
            row.setCreateTime(now);
            row.setUpdateTime(now);
            userFeishuMapper.insert(row);
            return;
        }
        if (userId != null) {
            existing.setUserId(userId);
        }
        if (StringUtils.hasText(feishuUserId)) {
            existing.setFeishuUserId(feishuUserId.trim());
        }
        if (StringUtils.hasText(mobile)) {
            existing.setFeishuMobile(mobile.trim());
        }
        if (StringUtils.hasText(email)) {
            existing.setFeishuEmail(email.trim());
        }
        existing.setUpdateTime(now);
        userFeishuMapper.updateById(existing);
    }

    @Override
    public void enrichBindingFromSso(String username,
                                     String feishuUserId,
                                     String openId,
                                     String unionId,
                                     String mobile,
                                     String email) {
        if (!StringUtils.hasText(username)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        UserFeishu existing = findByUsername(username.trim());
        if (existing == null) {
            User user = userMapper.selectOne(
                    new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
            UserFeishu row = new UserFeishu();
            if (user != null) {
                row.setUserId(user.getId());
            }
            row.setUsername(username.trim());
            row.setFeishuUserId(trimToNull(feishuUserId));
            row.setFeishuOpenId(trimToNull(openId));
            row.setFeishuUnionId(trimToNull(unionId));
            row.setFeishuMobile(trimToNull(mobile));
            row.setFeishuEmail(trimToNull(email));
            row.setCreateTime(now);
            row.setUpdateTime(now);
            userFeishuMapper.insert(row);
            return;
        }
        if (StringUtils.hasText(feishuUserId)) {
            existing.setFeishuUserId(feishuUserId.trim());
        }
        if (StringUtils.hasText(openId)) {
            existing.setFeishuOpenId(openId.trim());
        }
        if (StringUtils.hasText(unionId)) {
            existing.setFeishuUnionId(unionId.trim());
        }
        if (StringUtils.hasText(mobile)) {
            existing.setFeishuMobile(mobile.trim());
        }
        if (StringUtils.hasText(email)) {
            existing.setFeishuEmail(email.trim());
        }
        existing.setUpdateTime(now);
        userFeishuMapper.updateById(existing);
    }

    private UserFeishu findByUsername(String username) {
        return userFeishuMapper.selectOne(
                new QueryWrapper<UserFeishu>().eq("username", username).last("LIMIT 1"));
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
