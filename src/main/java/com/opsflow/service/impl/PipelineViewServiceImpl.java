package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.PipelineBoardViewDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineViewMapper;
import com.opsflow.dao.mapper.PipelineViewRoleMapper;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.mapper.UserRoleMapper;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.PipelineView;
import com.opsflow.dao.model.PipelineViewRole;
import com.opsflow.dao.model.Role;
import com.opsflow.dao.model.User;
import com.opsflow.service.PermissionService;
import com.opsflow.service.PipelineViewService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineViewServiceImpl implements PipelineViewService {

    @Autowired
    private PipelineViewMapper pipelineViewMapper;

    @Autowired
    private PipelineViewRoleMapper pipelineViewRoleMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private RoleMapper roleMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private PermissionService permissionService;

    @Override
    @Transactional
    public PipelineBoardViewDTO create(PipelineBoardViewDTO request) {
        validate(request, null);
        PipelineView entity = toEntity(request);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        entity.setSortOrder(request.getSortOrder() != null ? request.getSortOrder() : nextSortOrder());
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        pipelineViewMapper.insert(entity);
        saveViewRoles(entity.getId(), request.getRoleIds());
        return toDto(entity, true);
    }

    @Override
    public List<PipelineBoardViewDTO> list() {
        QueryWrapper<PipelineView> wrapper = new QueryWrapper<>();
        wrapper.eq("status", 1);
        wrapper.orderByAsc("sort_order").orderByAsc("id");
        List<PipelineView> all = pipelineViewMapper.selectList(wrapper);
        boolean manageAll = canAccessAllViews();
        boolean includeRoles = manageAll;
        if (manageAll) {
            return all.stream().map(v -> toDto(v, includeRoles)).collect(Collectors.toList());
        }
        Set<Long> allowedViewIds = listAccessibleViewIds();
        return all.stream()
                .filter(v -> allowedViewIds.contains(v.getId()))
                .map(v -> toDto(v, false))
                .collect(Collectors.toList());
    }

    @Override
    public PipelineBoardViewDTO getById(Long id) {
        PipelineView entity = pipelineViewMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        if (!canAccessAllViews() && !listAccessibleViewIds().contains(id)) {
            throw new BusinessException("无权访问该视图");
        }
        return toDto(entity, true);
    }

    @Override
    @Transactional
    public PipelineBoardViewDTO update(Long id, PipelineBoardViewDTO request) {
        PipelineView entity = pipelineViewMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("视图不存在");
        }
        validate(request, id);
        entity.setName(request.getName().trim());
        entity.setEnvId(request.getEnvId());
        entity.setDescription(request.getDescription() != null ? request.getDescription().trim() : null);
        if (request.getSortOrder() != null) {
            entity.setSortOrder(request.getSortOrder());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }
        entity.setUpdateTime(LocalDateTime.now());
        pipelineViewMapper.updateById(entity);
        if (request.getRoleIds() != null) {
            saveViewRoles(id, request.getRoleIds());
        }
        return toDto(entity, true);
    }

    @Override
    @Transactional
    public boolean delete(Long id) {
        pipelineViewRoleMapper.deleteByViewId(id);
        return pipelineViewMapper.deleteById(id) > 0;
    }

    @Override
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            if (id == null) {
                continue;
            }
            PipelineView entity = pipelineViewMapper.selectById(id);
            if (entity == null) {
                continue;
            }
            entity.setSortOrder(i);
            entity.setUpdateTime(now);
            pipelineViewMapper.updateById(entity);
        }
    }

    @Override
    public boolean canAccessAllViews() {
        return permissionService.hasAnyPermission(currentPermissions(), "pipeline_config:manage");
    }

    @Override
    public Set<Long> listAccessibleEnvIds() {
        if (canAccessAllViews()) {
            QueryWrapper<PipelineView> wrapper = new QueryWrapper<>();
            wrapper.eq("status", 1).select("env_id");
            Set<Long> envIds = new LinkedHashSet<>();
            for (PipelineView view : pipelineViewMapper.selectList(wrapper)) {
                if (view.getEnvId() != null) {
                    envIds.add(view.getEnvId());
                }
            }
            return envIds;
        }
        Set<Long> viewIds = listAccessibleViewIds();
        if (viewIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Long> envIds = new LinkedHashSet<>();
        for (Long viewId : viewIds) {
            PipelineView view = pipelineViewMapper.selectById(viewId);
            if (view != null && view.getStatus() != null && view.getStatus() == 1 && view.getEnvId() != null) {
                envIds.add(view.getEnvId());
            }
        }
        return envIds;
    }

    @Override
    public void assertAccessibleEnv(Long envId) {
        if (canAccessAllViews()) {
            return;
        }
        Set<Long> allowed = listAccessibleEnvIds();
        if (envId == null) {
            // 「全部」：非管理员不允许无 env 过滤（避免绕过视图隔离）
            if (allowed.isEmpty()) {
                throw new BusinessException("无权查看流水线任务，请联系管理员分配视图权限");
            }
            return;
        }
        if (!allowed.contains(envId)) {
            throw new BusinessException("无权访问该环境的流水线任务");
        }
    }

    private Set<Long> listAccessibleViewIds() {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Collections.emptySet();
        }
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserId(userId);
        if (roleIds == null || roleIds.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> viewIds = pipelineViewRoleMapper.selectViewIdsByRoleIds(roleIds);
        if (viewIds == null || viewIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(viewIds);
    }

    private void saveViewRoles(Long viewId, List<Long> roleIds) {
        pipelineViewRoleMapper.deleteByViewId(viewId);
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            if (roleId != null) {
                unique.add(roleId);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long roleId : unique) {
            Role role = roleMapper.selectById(roleId);
            if (role == null) {
                continue;
            }
            PipelineViewRole rel = new PipelineViewRole();
            rel.setViewId(viewId);
            rel.setRoleId(roleId);
            rel.setCreateTime(now);
            pipelineViewRoleMapper.insert(rel);
        }
    }

    private int nextSortOrder() {
        QueryWrapper<PipelineView> wrapper = new QueryWrapper<>();
        wrapper.select("sort_order");
        wrapper.orderByDesc("sort_order").last("LIMIT 1");
        PipelineView last = pipelineViewMapper.selectOne(wrapper);
        if (last == null || last.getSortOrder() == null) {
            return 0;
        }
        return last.getSortOrder() + 1;
    }

    private void validate(PipelineBoardViewDTO request, Long excludeId) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("视图名称不能为空");
        }
        if (request.getEnvId() == null) {
            throw new BusinessException("请选择关联环境");
        }
        Env env = envMapper.selectById(request.getEnvId());
        if (env == null) {
            throw new BusinessException("关联环境不存在");
        }
        QueryWrapper<PipelineView> wrapper = new QueryWrapper<>();
        wrapper.eq("name", request.getName().trim());
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (pipelineViewMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("视图名称已存在");
        }
    }

    private PipelineView toEntity(PipelineBoardViewDTO request) {
        PipelineView entity = new PipelineView();
        BeanUtils.copyProperties(request, entity, "roleIds", "roleNames", "envName");
        entity.setName(request.getName().trim());
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription().trim());
        }
        return entity;
    }

    private PipelineBoardViewDTO toDto(PipelineView entity, boolean includeRoles) {
        PipelineBoardViewDTO dto = new PipelineBoardViewDTO();
        BeanUtils.copyProperties(entity, dto);
        if (entity.getEnvId() != null) {
            Env env = envMapper.selectById(entity.getEnvId());
            if (env != null) {
                dto.setEnvName(env.getName());
            }
        }
        if (includeRoles) {
            List<Long> roleIds = pipelineViewRoleMapper.selectRoleIdsByViewId(entity.getId());
            dto.setRoleIds(roleIds != null ? roleIds : Collections.emptyList());
            if (roleIds != null && !roleIds.isEmpty()) {
                List<Role> roles = roleMapper.selectBatchIds(roleIds);
                List<String> names = new ArrayList<>();
                if (roles != null) {
                    for (Role role : roles) {
                        if (role != null && role.getName() != null) {
                            names.add(role.getName());
                        }
                    }
                }
                dto.setRoleNames(names);
            } else {
                dto.setRoleNames(Collections.emptyList());
            }
        }
        return dto;
    }

    @SuppressWarnings("unchecked")
    private List<String> currentPermissions() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object perms = session != null ? session.getAttribute("permissions") : null;
                if (perms instanceof List) {
                    return (List<String>) perms;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Collections.emptyList();
        }
        return permissionService.listPermissionCodesByUserId(userId);
    }

    private Long getCurrentUserId() {
        String username = getCurrentUsername();
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        User user = userMapper.selectOne(new QueryWrapper<User>().eq("username", username.trim()).last("LIMIT 1"));
        return user != null ? user.getId() : null;
    }

    private String getCurrentUsername() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes) {
                HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
                HttpSession session = request != null ? request.getSession(false) : null;
                Object user = session != null ? session.getAttribute("user") : null;
                if (user != null && !String.valueOf(user).trim().isEmpty()) {
                    return String.valueOf(user).trim();
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }
}
