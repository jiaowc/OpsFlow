package com.opsflow.service.impl;

import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限查询服务
 */
@Service
public class PermissionServiceImpl implements PermissionService {

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Override
    public List<String> listPermissionCodesByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<String> codes = userRoleMapper.selectPermissionCodesByUserId(userId);
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            if (StringUtils.hasText(code)) {
                unique.add(code.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    @Override
    public List<String> listRoleCodesByUserId(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<String> codes = userRoleMapper.selectRoleCodesByUserId(userId);
        if (codes == null || codes.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            if (StringUtils.hasText(code)) {
                unique.add(code.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    @Override
    public boolean hasAnyPermission(Collection<String> owned, String... required) {
        if (required == null || required.length == 0) {
            return true;
        }
        if (owned == null || owned.isEmpty()) {
            return false;
        }
        if (owned.contains(WILDCARD)) {
            return true;
        }
        for (String need : required) {
            if (StringUtils.hasText(need) && owned.contains(need.trim())) {
                return true;
            }
        }
        return false;
    }
}
