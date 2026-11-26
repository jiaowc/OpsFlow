package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.security.Totp;
import com.opsflow.common.util.PasswordHasher;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.dao.model.UserRecoveryCode;
import com.opsflow.dao.model.UserTwoFactor;
import com.opsflow.service.SystemConfigService;
import com.opsflow.service.TwoFactorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class TwoFactorServiceImpl implements TwoFactorService {

    /** 验证器 App 里显示的发行方 */
    static final String ISSUER = "OpsFlow";

    /** 恢复码字母表：去掉 0/o、1/l/i 这类容易看错的字符 */
    static final String RECOVERY_ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private TwoFactorStore store;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private UserMapper userMapper;

    /** 测试里替换成固定时钟 */
    private Clock clock = Clock.systemDefaultZone();

    @Override
    public Policy policy() {
        return Policy.parse(systemConfigService.get(CONFIG_TYPE, POLICY_KEY));
    }

    @Override
    public boolean isEligible(User user) {
        if (user == null) {
            return false;
        }
        String source = user.getSource() == null ? "" : user.getSource().trim().toLowerCase(Locale.ROOT);
        return source.isEmpty() || "local".equals(source) || "ldap".equals(source);
    }

    @Override
    public boolean isEnrolled(Long userId) {
        return userId != null && store.find(userId) != null;
    }

    @Override
    public int recoveryCodesLeft(Long userId) {
        return userId == null ? 0 : store.unusedCodes(userId).size();
    }

    @Override
    public LocalDateTime confirmedTime(Long userId) {
        UserTwoFactor row = userId == null ? null : store.find(userId);
        return row != null ? row.getConfirmedTime() : null;
    }

    @Override
    public String newSecret() {
        return Totp.newSecret();
    }

    @Override
    public String otpauthUri(String account, String secret) {
        return Totp.otpauthUri(ISSUER, account, secret);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> confirmEnroll(Long userId, String secret, String code) {
        long step = Totp.verify(secret, code, clock.millis(), Long.MIN_VALUE);
        if (step < 0) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 记下确认时用的时间窗：同一个动态码不能紧接着再拿去登录
        store.save(userId, secret, step, now);
        return replaceRecoveryCodes(userId, now);
    }

    @Override
    public Verification verify(Long userId, String code, String recoveryCode) {
        UserTwoFactor row = userId == null ? null : store.find(userId);
        if (row == null) {
            return Verification.NOT_ENROLLED;
        }
        if (StringUtils.hasText(recoveryCode)) {
            return consumeRecoveryCode(userId, recoveryCode) ? Verification.RECOVERY_OK : Verification.RECOVERY_INVALID;
        }
        long after = row.getLastUsedStep() != null ? row.getLastUsedStep() : Long.MIN_VALUE;
        long step = Totp.verify(row.getSecret(), code, clock.millis(), after);
        if (step < 0 || !store.advanceStep(userId, step)) {
            return Verification.CODE_INVALID;
        }
        return Verification.CODE_OK;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> regenerateRecoveryCodes(Long userId) {
        return replaceRecoveryCodes(userId, LocalDateTime.now(clock));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void remove(Long userId) {
        if (userId != null) {
            store.delete(userId);
        }
    }

    @Override
    public Set<Long> enrolledAmong(Collection<Long> userIds) {
        Set<Long> result = new HashSet<>(store.enrolledUserIds());
        result.retainAll(new HashSet<>(userIds));
        return result;
    }

    @Override
    public Map<String, Object> summary() {
        List<User> users = userMapper.selectList(new QueryWrapper<User>().select("id", "source", "status").eq("status", 1));
        Set<Long> eligible = new HashSet<>();
        for (User user : users) {
            if (isEligible(user)) {
                eligible.add(user.getId());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", policy().code());
        out.put("enrolled", enrolledAmong(eligible).size());
        out.put("eligible", eligible.size());
        return out;
    }

    private List<String> replaceRecoveryCodes(Long userId, LocalDateTime now) {
        List<String> plain = new ArrayList<>(RECOVERY_CODE_COUNT);
        List<String> hashes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = newRecoveryCode();
            plain.add(code);
            hashes.add(PasswordHasher.hash(normalizeRecoveryCode(code)));
        }
        store.replaceCodes(userId, hashes, now);
        return plain;
    }

    private boolean consumeRecoveryCode(Long userId, String input) {
        String normalized = normalizeRecoveryCode(input);
        if (normalized.length() != 8) {
            return false;
        }
        for (UserRecoveryCode code : store.unusedCodes(userId)) {
            if (PasswordHasher.matches(normalized, code.getCodeHash())) {
                return store.markUsed(code.getId(), LocalDateTime.now(clock));
            }
        }
        return false;
    }

    /** xxxx-xxxx，8 个字符 */
    static String newRecoveryCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** 忽略大小写、空格和连字符 */
    static String normalizeRecoveryCode(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
