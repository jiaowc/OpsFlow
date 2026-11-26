package com.opsflow.integration.ldap;

import com.opsflow.common.crypto.SecretCipher;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LDAP 配置读写。绑定密码用 {@link SecretCipher} 加密存储，读接口只返回是否已设置。
 */
@Slf4j
@Service
public class LdapConfigService {

    static final String KEY_BIND_PASSWORD = "bindPassword";

    /** 前端回显用的占位，提交回来时视为「不修改」 */
    public static final String PASSWORD_MASK = "******";

    @Autowired
    private SystemConfigService systemConfigService;

    /** 当前生效的配置（绑定密码已解密，仅内存使用） */
    public LdapSettings load() {
        Map<String, String> stored = systemConfigService.getByType(LdapSettings.CONFIG_TYPE);
        LdapSettings settings = LdapSettings.fromMap(stored);
        String enc = stored != null ? stored.get(KEY_BIND_PASSWORD) : null;
        String plain = null;
        if (StringUtils.hasText(enc)) {
            try {
                plain = SecretCipher.decrypt(enc);
            } catch (RuntimeException e) {
                // 主密钥变了或密文损坏：当作未设置，测试连接时会提示绑定失败，需要重新填写
                log.warn("LDAP 绑定密码无法解密，请在 LDAP 配置中重新填写: {}", e.getClass().getSimpleName());
            }
        }
        settings.setBindPassword(plain);
        return settings;
    }

    /** 给前端的配置：不含密码，只告诉是否已设置 */
    public Map<String, Object> view() {
        LdapSettings s = load();
        Map<String, Object> out = new LinkedHashMap<>(s.toMapWithoutPassword());
        out.put("enabled", s.isEnabled());
        out.put("startTls", s.isStartTls());
        out.put("skipCertVerify", s.isSkipCertVerify());
        out.put("connectTimeoutMs", s.getConnectTimeoutMs());
        out.put("readTimeoutMs", s.getReadTimeoutMs());
        boolean hasPassword = StringUtils.hasText(s.getBindPassword());
        out.put("hasBindPassword", hasPassword);
        out.put(KEY_BIND_PASSWORD, hasPassword ? PASSWORD_MASK : "");
        return out;
    }

    /**
     * 把表单合并到已保存的配置上（不落库）。绑定密码留空或为占位时沿用已保存的；clearBindPassword=true 时清空。
     */
    public LdapSettings merge(Map<String, Object> form) {
        LdapSettings current = load();
        Map<String, String> values = new HashMap<>(current.toMapWithoutPassword());
        if (form != null) {
            for (Map.Entry<String, Object> e : form.entrySet()) {
                if (e.getValue() != null && values.containsKey(e.getKey())) {
                    values.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
        }
        LdapSettings merged = LdapSettings.fromMap(values);
        merged.setBindPassword(current.getBindPassword());
        if (form != null) {
            Object pw = form.get(KEY_BIND_PASSWORD);
            if (pw != null) {
                String p = String.valueOf(pw);
                if (!p.isEmpty() && !PASSWORD_MASK.equals(p)) {
                    merged.setBindPassword(p);
                }
            }
            if (Boolean.parseBoolean(String.valueOf(form.get("clearBindPassword")))) {
                merged.setBindPassword(null);
            }
        }
        if (!StringUtils.hasText(merged.getBindDn())) {
            // 匿名搜索时不保留旧密码
            merged.setBindPassword(null);
        }
        return merged;
    }

    /** 保存；启用状态下要求配置完整 */
    public LdapSettings save(Map<String, Object> form) {
        LdapSettings merged = merge(form);
        if (merged.isEnabled()) {
            String err = merged.validate();
            if (err != null) {
                throw new BusinessException(err);
            }
        }
        Map<String, String> values = merged.toMapWithoutPassword();
        values.put(KEY_BIND_PASSWORD, StringUtils.hasText(merged.getBindPassword())
                ? SecretCipher.encrypt(merged.getBindPassword()) : "");
        systemConfigService.saveBatch(LdapSettings.CONFIG_TYPE, values);
        return merged;
    }
}
