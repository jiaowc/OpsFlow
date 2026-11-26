package com.opsflow.service;

/**
 * 外部身份（扩展提供的单点登录方式）和本地用户的对应关系，存在 user_external_identity：
 * (provider, subject) 唯一，(user_id, provider) 唯一。
 * <p>provider 由扩展自己定义（只允许小写字母、数字、横线和下划线），核心不解析。
 * subject 去掉首尾空白后按区分大小写的精确匹配存取；平台 ID 不区分大小写时，由扩展在调用前自行归一化。</p>
 */
public interface ExternalIdentityService {

    /** 按外部用户 ID 找本地用户；没有对应关系返回 null */
    Long findUserId(String provider, String subject);

    /** 某个本地用户在该登录方式下的外部用户 ID；没有返回 null */
    String findSubject(Long userId, String provider);

    /**
     * 设置对应关系：subject 为空表示解除；该外部用户 ID 已对应到其他本地用户时抛
     * {@link com.opsflow.common.exception.BusinessException}。
     */
    void bind(Long userId, String provider, String subject);

    /** 删除用户时清理 */
    void removeAll(Long userId);
}
