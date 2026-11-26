package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ProjectMemberMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.ProjectMember;
import com.opsflow.dao.model.User;
import com.opsflow.service.PermissionService;
import com.opsflow.service.ProjectAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProjectAccessServiceImpl implements ProjectAccessService {

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PermissionService permissionService;

    /** 当前请求的身份信息；null 表示非 Web 请求 */
    private static final class Caller {
        Long userId;
        boolean platformAdmin;
    }

    @Override
    public boolean isPlatformAdmin() {
        Caller caller = caller();
        return caller == null || caller.platformAdmin;
    }

    @Override
    public String roleOf(Long projectId) {
        Caller caller = caller();
        if (caller == null || caller.platformAdmin) {
            return ProjectMember.ROLE_ADMIN;
        }
        if (projectId == null || caller.userId == null) {
            return null;
        }
        ProjectMember member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, caller.userId)
                .last("LIMIT 1"));
        return member != null ? member.getRole() : null;
    }

    @Override
    public Set<Long> accessibleProjectIds() {
        Caller caller = caller();
        if (caller == null || caller.platformAdmin) {
            return null;
        }
        if (caller.userId == null) {
            return Collections.emptySet();
        }
        return projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .select(ProjectMember::getProjectId)
                        .eq(ProjectMember::getUserId, caller.userId))
                .stream().map(ProjectMember::getProjectId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public Set<Long> accessibleServiceIds(Long projectId) {
        Collection<Long> projectIds;
        if (projectId != null) {
            assertCanViewProject(projectId);
            projectIds = Collections.singleton(projectId);
        } else {
            Set<Long> accessible = accessibleProjectIds();
            if (accessible == null) {
                return null;
            }
            projectIds = accessible;
        }
        if (projectIds.isEmpty()) {
            return Collections.emptySet();
        }
        return serviceMapper.selectList(new QueryWrapper<com.opsflow.dao.model.Service>()
                        .select("id").in("project_id", projectIds))
                .stream().map(com.opsflow.dao.model.Service::getId).collect(Collectors.toSet());
    }

    @Override
    public void assertCanViewProject(Long projectId) {
        if (roleOf(projectId) == null) {
            throw new BusinessException("无权访问该项目，请联系项目管理员添加成员");
        }
    }

    @Override
    public void assertCanOperateProject(Long projectId) {
        String role = roleOf(projectId);
        if (role == null) {
            throw new BusinessException("无权访问该项目，请联系项目管理员添加成员");
        }
        if (ProjectMember.ROLE_VIEWER.equals(role)) {
            throw new BusinessException("你在该项目中是只读成员，不能执行此操作");
        }
    }

    @Override
    public void assertProjectAdmin(Long projectId) {
        if (!ProjectMember.ROLE_ADMIN.equals(roleOf(projectId))) {
            throw new BusinessException("仅项目管理员可以执行此操作");
        }
    }

    @Override
    public boolean canViewService(Long serviceId) {
        Long projectId = projectOfService(serviceId);
        return projectId == null || roleOf(projectId) != null;
    }

    @Override
    public void assertCanViewService(Long serviceId) {
        if (!canViewService(serviceId)) {
            throw new BusinessException("无权访问该服务所属项目，请联系项目管理员添加成员");
        }
    }

    @Override
    public boolean canOperateService(Long serviceId) {
        Long projectId = projectOfService(serviceId);
        if (projectId == null) {
            return true;
        }
        String role = roleOf(projectId);
        return role != null && !ProjectMember.ROLE_VIEWER.equals(role);
    }

    @Override
    public void assertCanOperateService(Long serviceId) {
        Long projectId = projectOfService(serviceId);
        if (projectId != null) {
            assertCanOperateProject(projectId);
        }
    }

    private Long projectOfService(Long serviceId) {
        if (serviceId == null) {
            return null;
        }
        com.opsflow.dao.model.Service service = serviceMapper.selectById(serviceId);
        return service != null ? service.getProjectId() : null;
    }

    private Caller caller() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes)) {
            return null;
        }
        HttpServletRequest request = ((ServletRequestAttributes) attrs).getRequest();
        HttpSession session = request != null ? request.getSession(false) : null;
        Caller caller = new Caller();
        if (session == null) {
            return caller;
        }
        Object id = session.getAttribute("userId");
        if (id instanceof Number) {
            caller.userId = ((Number) id).longValue();
        } else if (session.getAttribute("user") != null) {
            User user = userMapper.selectOne(new QueryWrapper<User>()
                    .eq("username", String.valueOf(session.getAttribute("user"))).last("LIMIT 1"));
            caller.userId = user != null ? user.getId() : null;
        }
        caller.platformAdmin = permissionService.hasAnyPermission(permissions(session, caller.userId), PLATFORM_PERMISSION);
        return caller;
    }

    @SuppressWarnings("unchecked")
    private List<String> permissions(HttpSession session, Long userId) {
        Object perms = session.getAttribute("permissions");
        if (perms instanceof List) {
            return (List<String>) perms;
        }
        return userId != null ? permissionService.listPermissionCodesByUserId(userId) : Collections.emptyList();
    }
}
