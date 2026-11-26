package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.UserExternalIdentityMapper;
import com.opsflow.dao.model.UserExternalIdentity;
import com.opsflow.service.ExternalIdentityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
public class ExternalIdentityServiceImpl implements ExternalIdentityService {

    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,31}$");
    private static final int MAX_SUBJECT = 128;

    @Autowired
    private UserExternalIdentityMapper mapper;

    @Override
    public Long findUserId(String provider, String subject) {
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        UserExternalIdentity row = mapper.selectOne(new QueryWrapper<UserExternalIdentity>()
                .eq("provider", checkProvider(provider)).eq("subject", subject.trim()).last("LIMIT 1"));
        return row != null ? row.getUserId() : null;
    }

    @Override
    public String findSubject(Long userId, String provider) {
        if (userId == null) {
            return null;
        }
        UserExternalIdentity row = mapper.selectOne(new QueryWrapper<UserExternalIdentity>()
                .eq("provider", checkProvider(provider)).eq("user_id", userId).last("LIMIT 1"));
        return row != null ? row.getSubject() : null;
    }

    @Override
    public void bind(Long userId, String provider, String subject) {
        String p = checkProvider(provider);
        if (userId == null) {
            throw new BusinessException("用户不存在");
        }
        if (!StringUtils.hasText(subject)) {
            mapper.delete(new QueryWrapper<UserExternalIdentity>().eq("provider", p).eq("user_id", userId));
            return;
        }
        String s = subject.trim();
        if (s.length() > MAX_SUBJECT) {
            throw new BusinessException("外部用户 ID 不能超过 " + MAX_SUBJECT + " 个字符");
        }
        try {
            upsert(userId, p, s);
        } catch (DuplicateKeyException e) {
            // 并发绑定：另一个请求刚插入了同一用户同一方式的记录，或抢先占用了这个外部 ID；重读后再处理一次
            upsert(userId, p, s);
        }
    }

    /** 同一用户同一登录方式只保留一条：已有记录则更新，没有才插入 */
    private void upsert(Long userId, String p, String s) {
        UserExternalIdentity taken = mapper.selectOne(new QueryWrapper<UserExternalIdentity>()
                .eq("provider", p).eq("subject", s).last("LIMIT 1"));
        if (taken != null && !userId.equals(taken.getUserId())) {
            throw new BusinessException("该外部用户 ID 已对应到其他用户：" + s);
        }
        if (taken != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        UserExternalIdentity own = mapper.selectOne(new QueryWrapper<UserExternalIdentity>()
                .eq("provider", p).eq("user_id", userId).last("LIMIT 1"));
        if (own != null) {
            own.setSubject(s);
            own.setUpdateTime(now);
            mapper.updateById(own);
            return;
        }
        UserExternalIdentity row = new UserExternalIdentity();
        row.setUserId(userId);
        row.setProvider(p);
        row.setSubject(s);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        mapper.insert(row);
    }

    @Override
    public void removeAll(Long userId) {
        if (userId != null) {
            mapper.delete(new QueryWrapper<UserExternalIdentity>().eq("user_id", userId));
        }
    }

    private static String checkProvider(String provider) {
        if (provider == null || !PROVIDER.matcher(provider).matches()) {
            throw new IllegalArgumentException("provider 不合法: " + provider);
        }
        return provider;
    }
}
