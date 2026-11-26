package com.opsflow.service;

import java.util.Set;

/**
 * 项目级数据权限。
 * <ul>
 *   <li>平台管理员（project:manage）可见、可操作所有项目；</li>
 *   <li>其他用户只能看到自己是成员的项目及其服务、流水线、上线任务；</li>
 *   <li>项目内 VIEWER 只读，DEVELOPER 可构建/部署/维护服务，ADMIN 另可管理成员与项目信息。</li>
 * </ul>
 * 非 Web 请求（定时任务、Webhook 内部触发）不受限制。
 * 功能级权限（如 pipeline:run）仍由 {@code @RequiresPermission} 控制，两者同时满足才可操作。
 */
public interface ProjectAccessService {

    String PLATFORM_PERMISSION = "project:manage";

    boolean isPlatformAdmin();

    /** 当前用户在项目中的角色；平台管理员返回 ADMIN；非成员返回 null */
    String roleOf(Long projectId);

    /** 当前用户可见的项目 ID；null 表示全部 */
    Set<Long> accessibleProjectIds();

    /**
     * 可见服务 ID：projectId 为空时为所有可见项目的服务；null 表示不限制
     */
    Set<Long> accessibleServiceIds(Long projectId);

    void assertCanViewProject(Long projectId);

    void assertCanOperateProject(Long projectId);

    void assertProjectAdmin(Long projectId);

    boolean canViewService(Long serviceId);

    void assertCanViewService(Long serviceId);

    boolean canOperateService(Long serviceId);

    void assertCanOperateService(Long serviceId);
}
