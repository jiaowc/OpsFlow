package com.opsflow.common.util;

import com.opsflow.common.exception.BusinessException;
import org.springframework.util.StringUtils;

/**
 * 必填字段校验。null、空串、只有空白都算空，报「X不能为空」（400）。
 *
 * <p>约定：新建时必填字段用 {@link #requireText}；部分更新时请求里没带（null）表示不改，
 * 带了空值才拒绝，用 {@link #optionalText}。密码、密钥这类值不 trim，用 {@link #requireSecret}。</p>
 */
public final class RequiredFields {

    private RequiredFields() {
    }

    /** 必填文本：空则报错，否则返回 trim 后的值 */
    public static String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(label + "不能为空");
        }
        return value.trim();
    }

    /** 部分更新：null 返回 null（表示不改），带了空值报错，否则返回 trim 后的值 */
    public static String optionalText(String value, String label) {
        return value == null ? null : requireText(value, label);
    }

    /** 必填的密码 / 密钥：空则报错，原样返回（不 trim） */
    public static String requireSecret(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(label + "不能为空");
        }
        return value;
    }

    /** 必填的非文本值（id 等） */
    public static <T> T requireValue(T value, String label) {
        if (value == null) {
            throw new BusinessException(label + "不能为空");
        }
        return value;
    }
}
