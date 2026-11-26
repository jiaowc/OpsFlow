package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.dao.mapper.UserRecoveryCodeMapper;
import com.opsflow.dao.mapper.UserTwoFactorMapper;
import com.opsflow.dao.model.UserRecoveryCode;
import com.opsflow.dao.model.UserTwoFactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class MybatisTwoFactorStore implements TwoFactorStore {

    @Autowired
    private UserTwoFactorMapper twoFactorMapper;

    @Autowired
    private UserRecoveryCodeMapper recoveryCodeMapper;

    @Override
    public UserTwoFactor find(Long userId) {
        return twoFactorMapper.selectOne(new QueryWrapper<UserTwoFactor>()
                .eq("user_id", userId).eq("enabled", 1).last("LIMIT 1"));
    }

    @Override
    public void save(Long userId, String secret, long usedStep, LocalDateTime now) {
        UserTwoFactor existing = twoFactorMapper.selectOne(new QueryWrapper<UserTwoFactor>()
                .eq("user_id", userId).last("LIMIT 1"));
        UserTwoFactor row = existing != null ? existing : new UserTwoFactor();
        row.setUserId(userId);
        row.setSecret(secret);
        row.setEnabled(1);
        row.setConfirmedTime(now);
        row.setLastUsedStep(usedStep);
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            twoFactorMapper.insert(row);
        } else {
            twoFactorMapper.updateById(row);
        }
    }

    @Override
    public boolean advanceStep(Long userId, long step) {
        UpdateWrapper<UserTwoFactor> w = new UpdateWrapper<>();
        w.set("last_used_step", step)
                .eq("user_id", userId)
                .and(q -> q.isNull("last_used_step").or().lt("last_used_step", step));
        return twoFactorMapper.update(null, w) == 1;
    }

    @Override
    public void delete(Long userId) {
        twoFactorMapper.delete(new QueryWrapper<UserTwoFactor>().eq("user_id", userId));
        recoveryCodeMapper.delete(new QueryWrapper<UserRecoveryCode>().eq("user_id", userId));
    }

    @Override
    public List<UserRecoveryCode> unusedCodes(Long userId) {
        return recoveryCodeMapper.selectList(new QueryWrapper<UserRecoveryCode>()
                .eq("user_id", userId).isNull("used_time"));
    }

    @Override
    public void replaceCodes(Long userId, List<String> hashes, LocalDateTime now) {
        recoveryCodeMapper.delete(new QueryWrapper<UserRecoveryCode>().eq("user_id", userId));
        for (String hash : hashes) {
            UserRecoveryCode code = new UserRecoveryCode();
            code.setUserId(userId);
            code.setCodeHash(hash);
            code.setCreateTime(now);
            recoveryCodeMapper.insert(code);
        }
    }

    @Override
    public boolean markUsed(Long codeId, LocalDateTime now) {
        UpdateWrapper<UserRecoveryCode> w = new UpdateWrapper<>();
        w.set("used_time", now).eq("id", codeId).isNull("used_time");
        return recoveryCodeMapper.update(null, w) == 1;
    }

    @Override
    public Set<Long> enrolledUserIds() {
        Set<Long> ids = new HashSet<>();
        for (UserTwoFactor row : twoFactorMapper.selectList(new QueryWrapper<UserTwoFactor>()
                .select("user_id").eq("enabled", 1))) {
            ids.add(row.getUserId());
        }
        return ids;
    }
}
