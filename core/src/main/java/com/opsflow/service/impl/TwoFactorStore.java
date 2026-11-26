package com.opsflow.service.impl;

import com.opsflow.dao.model.UserRecoveryCode;
import com.opsflow.dao.model.UserTwoFactor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 两步验证的存储；拆出来是为了让服务逻辑可以用内存实现做单元测试 */
public interface TwoFactorStore {

    UserTwoFactor find(Long userId);

    /** 写入或覆盖绑定 */
    void save(Long userId, String secret, long usedStep, LocalDateTime now);

    /** 只有新序号大于已记录的序号才更新；返回是否更新成功（并发时只有一个请求成功） */
    boolean advanceStep(Long userId, long step);

    /** 删除绑定和恢复码 */
    void delete(Long userId);

    List<UserRecoveryCode> unusedCodes(Long userId);

    /** 删除该用户全部恢复码，再写入新的哈希 */
    void replaceCodes(Long userId, List<String> hashes, LocalDateTime now);

    /** 只在未使用时标记为已用；返回是否标记成功 */
    boolean markUsed(Long codeId, LocalDateTime now);

    Set<Long> enrolledUserIds();
}
