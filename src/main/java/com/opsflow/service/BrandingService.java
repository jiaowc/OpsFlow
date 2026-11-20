package com.opsflow.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 企业品牌（商业功能）：自定义登录页 / 控制台 Logo。
 */
public interface BrandingService {

    /** 登录页等公开读取：无 License 或不存在 Logo 时 custom=false */
    Map<String, Object> publicBranding();

    /** 管理端状态 */
    Map<String, Object> adminStatus();

    /** 上传 Logo，需已开通 branding */
    Map<String, Object> uploadLogo(MultipartFile file);

    /** 清除自定义 Logo */
    void clearLogo();

    /** 已开通且文件存在时返回磁盘路径，否则 null */
    java.nio.file.Path resolveLogoFile();

    String resolveLogoContentType();
}
