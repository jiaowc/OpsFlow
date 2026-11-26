package com.opsflow.service;

import java.util.Collection;
import java.util.List;

/**
 * 权限查询与判定
 */
public interface PermissionService {

    /** 超级权限通配符 */
    String WILDCARD = "*";

    List<String> listPermissionCodesByUserId(Long userId);

    List<String> listRoleCodesByUserId(Long userId);

    /**
     * 是否拥有 required 中任一权限（拥有 * 视为全部通过）
     */
    boolean hasAnyPermission(Collection<String> owned, String... required);
}
