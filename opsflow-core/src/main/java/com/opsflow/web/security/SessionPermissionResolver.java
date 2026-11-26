package com.opsflow.web.security;

import com.opsflow.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;

/**
 * 会话内角色/权限码缓存。缓存超过 REFRESH_MILLIS 后从库中重新加载，使角色调整无需重新登录即可生效。
 */
@Component
public class SessionPermissionResolver {

    public static final String ATTR_ROLES = "roles";
    public static final String ATTR_PERMISSIONS = "permissions";
    private static final String ATTR_LOADED_AT = "permissionsLoadedAt";

    private static final long REFRESH_MILLIS = 5 * 1000L;

    @Autowired
    private PermissionService permissionService;

    public void bind(HttpSession session, List<String> roles, List<String> permissions) {
        session.setAttribute(ATTR_ROLES, roles != null ? roles : Collections.emptyList());
        session.setAttribute(ATTR_PERMISSIONS, permissions != null ? permissions : Collections.emptyList());
        session.setAttribute(ATTR_LOADED_AT, System.currentTimeMillis());
    }

    @SuppressWarnings("unchecked")
    public List<String> resolvePermissions(HttpSession session) {
        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            bind(session, Collections.emptyList(), Collections.emptyList());
            return Collections.emptyList();
        }
        Object cached = session.getAttribute(ATTR_PERMISSIONS);
        Object loadedAt = session.getAttribute(ATTR_LOADED_AT);
        if (cached instanceof List && loadedAt instanceof Long
                && System.currentTimeMillis() - (Long) loadedAt < REFRESH_MILLIS) {
            return (List<String>) cached;
        }
        List<String> permissions = permissionService.listPermissionCodesByUserId(userId);
        bind(session, permissionService.listRoleCodesByUserId(userId), permissions);
        return permissions;
    }
}
