package com.opsflow.license;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * License 载荷（签名对象）
 */
@Data
public class LicensePayload {

    private String product = "OpsFlow";

    private String licenseId;

    private String customer;

    /** 订阅计划：pro / enterprise 等（可选） */
    private String plan;

    private List<String> features = new ArrayList<>();

    /** ISO-8601 日期时间，如 2026-07-23T00:00:00 */
    private String issuedAt;

    /**
     * ISO-8601 订阅到期时间。
     * 商业订阅应必填；空表示永不过期（仅建议用于内部测试）。
     */
    private String expiresAt;
}
