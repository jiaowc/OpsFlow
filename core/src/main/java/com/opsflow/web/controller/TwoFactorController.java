package com.opsflow.web.controller;

import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.User;
import com.opsflow.service.TwoFactorService;
import com.opsflow.web.security.TwoFactorSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本人的两步验证：状态、绑定、重新生成恢复码、更换验证器、解绑。
 * <p>放在 /api/auth/ 下，强制绑定期间也能调用；都要求已登录。登录第二步在 {@link AuthController#verifyTwoFactor}。</p>
 * <p>重新生成、更换、解绑要先输入当前动态码或恢复码（参数 {@code code} / {@code recoveryCode}），连续输错 5 次作废会话。
 * 失败时返回 {@code {success:false, code, message}}，code 见各方法说明。</p>
 */
@RestController
@RequestMapping("/api/auth/two-factor")
public class TwoFactorController {

    @Autowired
    private TwoFactorService twoFactorService;

    @Autowired
    private UserMapper userMapper;

    /** 测试里替换成固定时间 */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    @GetMapping("/status")
    public Map<String, Object> status(HttpSession session) {
        User user = currentUser(session);
        if (user == null) {
            return fail("two_factor.not_logged_in", "未登录，请先登录");
        }
        Map<String, Object> out = ok();
        boolean enrolled = twoFactorService.isEnrolled(user.getId());
        LocalDateTime confirmed = enrolled ? twoFactorService.confirmedTime(user.getId()) : null;
        out.put("policy", twoFactorService.policy().code());
        out.put("eligible", twoFactorService.isEligible(user));
        out.put("enrolled", enrolled);
        out.put("mustEnroll", Boolean.TRUE.equals(session.getAttribute(TwoFactorSession.MUST_ENROLL)));
        out.put("confirmedTime", confirmed != null ? confirmed.toString() : null);
        out.put("recoveryCodesLeft", enrolled ? twoFactorService.recoveryCodesLeft(user.getId()) : 0);
        return out;
    }

    /**
     * 开始绑定：生成密钥，只放 session（10 分钟有效），返回 {@code secret}（Base32）和 {@code otpauthUri}。
     * 错误：not_eligible（单点登录来源的账号）、policy_off、already_enrolled（已绑定请用 replace）。
     */
    @PostMapping("/enroll/start")
    public Map<String, Object> startEnroll(HttpSession session) {
        User user = currentUser(session);
        Map<String, Object> denied = checkCanEnroll(user);
        if (denied != null) {
            return denied;
        }
        if (twoFactorService.isEnrolled(user.getId())) {
            return fail("two_factor.already_enrolled", "已绑定两步验证，如需更换请使用「更换验证器」");
        }
        return beginEnroll(session, user, false);
    }

    /**
     * 确认绑定：{@code {code}}。成功后落库并返回 {@code recoveryCodes}（10 个，只返回这一次），清除强制绑定标记。
     * 更换验证器时，确认前旧绑定一直有效。错误：enroll_expired（没有进行中的绑定或已超时）、code_invalid。
     */
    @PostMapping("/enroll/confirm")
    public Map<String, Object> confirmEnroll(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        User user = currentUser(session);
        Map<String, Object> denied = checkCanEnroll(user);
        if (denied != null) {
            return denied;
        }
        Object secret = session.getAttribute(TwoFactorSession.ENROLL_SECRET);
        Object at = session.getAttribute(TwoFactorSession.ENROLL_AT);
        if (!(secret instanceof String) || !(at instanceof Long)
                || clock.getAsLong() - (Long) at > TwoFactorSession.ENROLL_TTL_MILLIS) {
            TwoFactorSession.clearEnroll(session);
            return fail("two_factor.enroll_expired", "绑定已超时，请重新开始");
        }
        if (twoFactorService.isEnrolled(user.getId())
                && !Boolean.TRUE.equals(session.getAttribute(TwoFactorSession.ENROLL_REPLACE))) {
            return fail("two_factor.already_enrolled", "已绑定两步验证，如需更换请使用「更换验证器」");
        }
        List<String> codes = twoFactorService.confirmEnroll(user.getId(), (String) secret, param(body, "code"));
        if (codes == null) {
            return fail("two_factor.code_invalid", "动态码不正确，请确认手机时间准确后输入最新的动态码");
        }
        TwoFactorSession.clearEnroll(session);
        session.removeAttribute(TwoFactorSession.MUST_ENROLL);
        Map<String, Object> out = ok();
        out.put("recoveryCodes", codes);
        return out;
    }

    /** 作废旧恢复码、返回新的 {@code recoveryCodes}；要先输入当前动态码或恢复码 */
    @PostMapping("/recovery-codes/regenerate")
    public Map<String, Object> regenerate(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        User user = currentUser(session);
        Map<String, Object> denied = checkCurrent(user, body, session);
        if (denied != null) {
            return denied;
        }
        Map<String, Object> out = ok();
        out.put("recoveryCodes", twoFactorService.regenerateRecoveryCodes(user.getId()));
        return out;
    }

    /** 更换验证器：验证当前动态码或恢复码后生成新密钥，返回值同 enroll/start，之后照常调 enroll/confirm */
    @PostMapping("/replace")
    public Map<String, Object> replace(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        User user = currentUser(session);
        Map<String, Object> denied = checkCurrent(user, body, session);
        if (denied != null) {
            return denied;
        }
        return beginEnroll(session, user, true);
    }

    /** 解除绑定：全员强制时不允许（disable_forbidden），只能更换 */
    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestBody(required = false) Map<String, String> body, HttpSession session) {
        User user = currentUser(session);
        if (user != null && twoFactorService.policy() == TwoFactorService.Policy.REQUIRED) {
            return fail("two_factor.disable_forbidden", "已要求所有账号使用两步验证，不能解除绑定，只能更换验证器");
        }
        Map<String, Object> denied = checkCurrent(user, body, session);
        if (denied != null) {
            return denied;
        }
        twoFactorService.remove(user.getId());
        TwoFactorSession.clearEnroll(session);
        return ok();
    }

    private Map<String, Object> beginEnroll(HttpSession session, User user, boolean replace) {
        String secret = twoFactorService.newSecret();
        session.setAttribute(TwoFactorSession.ENROLL_SECRET, secret);
        session.setAttribute(TwoFactorSession.ENROLL_AT, clock.getAsLong());
        if (replace) {
            session.setAttribute(TwoFactorSession.ENROLL_REPLACE, Boolean.TRUE);
        } else {
            session.removeAttribute(TwoFactorSession.ENROLL_REPLACE);
        }
        Map<String, Object> out = ok();
        out.put("secret", secret);
        out.put("otpauthUri", twoFactorService.otpauthUri(user.getUsername(), secret));
        out.put("account", user.getUsername());
        return out;
    }

    private Map<String, Object> checkCanEnroll(User user) {
        if (user == null) {
            return fail("two_factor.not_logged_in", "未登录，请先登录");
        }
        if (!twoFactorService.isEligible(user)) {
            return fail("two_factor.not_eligible", "单点登录账号不使用两步验证");
        }
        if (twoFactorService.policy() == TwoFactorService.Policy.OFF) {
            return fail("two_factor.policy_off", "管理员未开启两步验证");
        }
        return null;
    }

    /** 验证当前动态码或恢复码；通过返回 null。错误：not_enrolled、code_invalid / recovery_invalid（带 remainingAttempts）、too_many */
    private Map<String, Object> checkCurrent(User user, Map<String, String> body, HttpSession session) {
        if (user == null) {
            return fail("two_factor.not_logged_in", "未登录，请先登录");
        }
        String recovery = param(body, "recoveryCode");
        TwoFactorService.Verification v = twoFactorService.verify(user.getId(), param(body, "code"), recovery);
        if (v == TwoFactorService.Verification.NOT_ENROLLED) {
            return fail("two_factor.not_enrolled", "尚未绑定两步验证");
        }
        if (v.isOk()) {
            session.removeAttribute(TwoFactorSession.MANAGE_FAILURES);
            return null;
        }
        Object n = session.getAttribute(TwoFactorSession.MANAGE_FAILURES);
        int failures = (n instanceof Integer ? (Integer) n : 0) + 1;
        if (failures >= TwoFactorSession.MAX_ATTEMPTS) {
            session.invalidate();
            return fail("two_factor.too_many", "尝试次数过多，请重新登录");
        }
        session.setAttribute(TwoFactorSession.MANAGE_FAILURES, failures);
        int remaining = TwoFactorSession.MAX_ATTEMPTS - failures;
        Map<String, Object> out = StringUtils.hasText(recovery)
                ? fail("two_factor.recovery_invalid", "恢复码不正确或已经用过，还可以再试 " + remaining + " 次")
                : fail("two_factor.code_invalid", "动态码不正确，还可以再试 " + remaining + " 次");
        out.put("remainingAttempts", remaining);
        return out;
    }

    private User currentUser(HttpSession session) {
        Object uid = session.getAttribute("userId");
        if (!Boolean.TRUE.equals(session.getAttribute("authenticated")) || !(uid instanceof Long)) {
            return null;
        }
        return userMapper.selectById((Long) uid);
    }

    private static String param(Map<String, String> body, String key) {
        return body != null ? body.get(key) : null;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        return out;
    }

    private static Map<String, Object> fail(String code, String message) {
        Map<String, Object> out = new HashMap<>();
        out.put("success", false);
        out.put("code", code);
        out.put("message", message);
        return out;
    }
}
