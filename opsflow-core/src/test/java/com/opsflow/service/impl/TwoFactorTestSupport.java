package com.opsflow.service.impl;

import com.opsflow.common.security.Base32;
import com.opsflow.common.security.Totp;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.service.SystemConfigService;
import com.opsflow.service.TwoFactorService;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 两步验证测试的公共装配：内存存储、可调的时钟、可调的策略 */
public final class TwoFactorTestSupport {

    public final InMemoryTwoFactorStore store = new InMemoryTwoFactorStore();
    public final SystemConfigService config = mock(SystemConfigService.class);
    public final UserMapper userMapper = mock(UserMapper.class);
    public final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    public final TwoFactorServiceImpl service = new TwoFactorServiceImpl();

    public TwoFactorTestSupport() {
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "systemConfigService", config);
        ReflectionTestUtils.setField(service, "userMapper", userMapper);
        ReflectionTestUtils.setField(service, "clock", new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.systemDefault();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        });
        policy(TwoFactorService.Policy.OFF);
    }

    public void policy(TwoFactorService.Policy policy) {
        when(config.get(anyString(), anyString())).thenAnswer(inv ->
                TwoFactorService.CONFIG_TYPE.equals(inv.getArgument(0))
                        && TwoFactorService.POLICY_KEY.equals(inv.getArgument(1)) ? policy.code() : null);
    }

    /** 当前时间窗的动态码 */
    public String codeNow(String secret) {
        return codeAt(secret, 0);
    }

    /** 相对当前偏移若干个时间窗的动态码 */
    public String codeAt(String secret, int offsetSteps) {
        return Totp.code(Base32.decode(secret), Totp.stepAt(now.get()) + offsetSteps);
    }

    /** 前后三个时间窗都不匹配的动态码 */
    public String wrongCode(String secret) {
        java.util.Set<String> valid = new java.util.HashSet<>(java.util.Arrays.asList(
                codeAt(secret, -1), codeAt(secret, 0), codeAt(secret, 1)));
        for (int i = 0; ; i++) {
            String candidate = String.format("%06d", i);
            if (!valid.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** 时钟前进若干个时间窗 */
    public void advance(int steps) {
        now.addAndGet(steps * Totp.PERIOD_SECONDS * 1000L);
    }

    /** 直接绑定（跳过 session 流程），返回密钥；绑定时用掉了当前时间窗 */
    public String enroll(Long userId) {
        String secret = service.newSecret();
        service.confirmEnroll(userId, secret, codeNow(secret));
        return secret;
    }
}
