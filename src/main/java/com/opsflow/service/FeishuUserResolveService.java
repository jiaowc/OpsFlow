package com.opsflow.service;

/**
 * 系统用户名 ↔ 飞书用户 ID 解析
 */
public interface FeishuUserResolveService {

    /**
     * 按系统用户名解析飞书 receiveId（默认 user_id）
     */
    String resolveFeishuUserId(String username);

    /**
     * 按飞书 user_id / open_id 反查系统用户名
     */
    String resolveUsernameByFeishuId(String feishuId);

    /**
     * 保存或更新用户的飞书绑定
     */
    void bindFeishuUser(Long userId, String username, String feishuUserId, String mobile, String email);

    /**
     * SSO 登录成功后回写 open_id / union_id 等，便于下次匹配
     */
    void enrichBindingFromSso(String username,
                              String feishuUserId,
                              String openId,
                              String unionId,
                              String mobile,
                              String email);
}
