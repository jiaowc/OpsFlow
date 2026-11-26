package com.opsflow.service.impl;

import com.opsflow.dao.model.UserRecoveryCode;
import com.opsflow.dao.model.UserTwoFactor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 测试用的内存存储，行为和 {@link MybatisTwoFactorStore} 的条件更新一致 */
public class InMemoryTwoFactorStore implements TwoFactorStore {

    final Map<Long, UserTwoFactor> rows = new HashMap<>();
    final List<UserRecoveryCode> codes = new ArrayList<>();
    private long nextCodeId = 1;

    @Override
    public UserTwoFactor find(Long userId) {
        return rows.get(userId);
    }

    @Override
    public void save(Long userId, String secret, long usedStep, LocalDateTime now) {
        UserTwoFactor row = new UserTwoFactor();
        row.setUserId(userId);
        row.setSecret(secret);
        row.setEnabled(1);
        row.setConfirmedTime(now);
        row.setLastUsedStep(usedStep);
        rows.put(userId, row);
    }

    @Override
    public boolean advanceStep(Long userId, long step) {
        UserTwoFactor row = rows.get(userId);
        if (row == null || (row.getLastUsedStep() != null && row.getLastUsedStep() >= step)) {
            return false;
        }
        row.setLastUsedStep(step);
        return true;
    }

    @Override
    public void delete(Long userId) {
        rows.remove(userId);
        codes.removeIf(c -> c.getUserId().equals(userId));
    }

    @Override
    public List<UserRecoveryCode> unusedCodes(Long userId) {
        return codes.stream().filter(c -> c.getUserId().equals(userId) && c.getUsedTime() == null)
                .collect(Collectors.toList());
    }

    @Override
    public void replaceCodes(Long userId, List<String> hashes, LocalDateTime now) {
        codes.removeIf(c -> c.getUserId().equals(userId));
        for (String hash : hashes) {
            UserRecoveryCode c = new UserRecoveryCode();
            c.setId(nextCodeId++);
            c.setUserId(userId);
            c.setCodeHash(hash);
            codes.add(c);
        }
    }

    @Override
    public boolean markUsed(Long codeId, LocalDateTime now) {
        for (UserRecoveryCode c : codes) {
            if (c.getId().equals(codeId) && c.getUsedTime() == null) {
                c.setUsedTime(now);
                return true;
            }
        }
        return false;
    }

    @Override
    public Set<Long> enrolledUserIds() {
        return rows.keySet();
    }
}
