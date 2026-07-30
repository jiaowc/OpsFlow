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

    private List<String> features = new ArrayList<>();

    /** ISO-8601 日期时间，如 2026-07-23T00:00:00 */
    private String issuedAt;

    /** ISO-8601，空表示永不过期 */
    private String expiresAt;
}
