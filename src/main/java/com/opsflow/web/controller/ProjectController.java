package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.ProjectMemberMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.ProjectMember;
import com.opsflow.dao.model.Service;
import com.opsflow.dao.model.User;
import com.opsflow.service.ProjectAccessService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 项目管理：项目是服务、流水线、上线任务的隔离边界。
 * <p>
 * 创建/删除项目需平台权限 project:manage；项目信息与成员由项目管理员维护。
 * </p>
 */
@RestController
@RequestMapping("/api/project")
public class ProjectController {

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ProjectMemberMapper projectMemberMapper;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ProjectAccessService projectAccessService;

    /**
     * 当前用户可见的项目（平台管理员可见全部），含我的角色与统计
     */
    @GetMapping("/list")
    public List<Map<String, Object>> list() {
        Set<Long> accessible = projectAccessService.accessibleProjectIds();
        if (accessible != null && accessible.isEmpty()) {
            return Collections.emptyList();
        }
        QueryWrapper<Project> wrapper = new QueryWrapper<Project>().orderByAsc("name").orderByAsc("id");
        if (accessible != null) {
            wrapper.in("id", accessible);
        }
        List<Project> projects = projectMapper.selectList(wrapper);
        if (projects.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = projects.stream().map(Project::getId).collect(Collectors.toList());

        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().in(ProjectMember::getProjectId, ids));
        Map<Long, Long> memberCount = members.stream()
                .collect(Collectors.groupingBy(ProjectMember::getProjectId, Collectors.counting()));
        Map<Long, Long> serviceCount = serviceMapper.selectList(new QueryWrapper<Service>()
                        .select("id", "project_id").in("project_id", ids))
                .stream().collect(Collectors.groupingBy(Service::getProjectId, Collectors.counting()));
        Map<Long, String> ownerNames = userNames(projects.stream()
                .map(Project::getOwnerUserId).filter(Objects::nonNull).collect(Collectors.toSet()));

        boolean platformAdmin = projectAccessService.isPlatformAdmin();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Project p : projects) {
            Map<String, Object> item = toMap(p, ownerNames);
            item.put("memberCount", memberCount.getOrDefault(p.getId(), 0L));
            item.put("serviceCount", serviceCount.getOrDefault(p.getId(), 0L));
            item.put("myRole", platformAdmin ? ProjectMember.ROLE_ADMIN : projectAccessService.roleOf(p.getId()));
            result.add(item);
        }
        return result;
    }

    @RequiresPermission("project:manage")
    @PostMapping("/create")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(@RequestBody Project request) {
        String name = normalizeName(request.getName());
        assertNameUnique(name, null);
        Project project = new Project();
        project.setName(name);
        project.setDescription(trimToNull(request.getDescription()));
        project.setOwnerUserId(requireUser(request.getOwnerUserId()));
        project.setStatus(1);
        project.setCreateTime(LocalDateTime.now());
        project.setUpdateTime(LocalDateTime.now());
        projectMapper.insert(project);
        if (project.getOwnerUserId() != null) {
            upsertMember(project.getId(), project.getOwnerUserId(), ProjectMember.ROLE_ADMIN);
        }
        return toMap(project, userNames(singletonOrEmpty(project.getOwnerUserId())));
    }

    /**
     * 修改项目信息；服务通过 project_id 关联，改名无需回写服务表；更换负责人会自动设为项目管理员
     */
    @PutMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Project request) {
        Project project = requireProject(id);
        projectAccessService.assertProjectAdmin(id);
        if (request.getName() != null) {
            String name = normalizeName(request.getName());
            if (!name.equals(project.getName())) {
                assertNameUnique(name, id);
                project.setName(name);
            }
        }
        if (request.getDescription() != null) {
            project.setDescription(trimToNull(request.getDescription()));
        }
        project.setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(project);

        Long ownerUserId = requireUser(request.getOwnerUserId());
        projectMapper.update(null, new UpdateWrapper<Project>().eq("id", id).set("owner_user_id", ownerUserId));
        project.setOwnerUserId(ownerUserId);
        if (ownerUserId != null) {
            upsertMember(id, ownerUserId, ProjectMember.ROLE_ADMIN);
        }
        return toMap(project, userNames(singletonOrEmpty(ownerUserId)));
    }

    /**
     * 删除项目：仅允许删除没有服务和上线任务的空项目
     */
    @RequiresPermission("project:manage")
    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(@PathVariable Long id) {
        requireProject(id);
        Long services = serviceMapper.selectCount(new QueryWrapper<Service>().eq("project_id", id));
        if (services != null && services > 0) {
            throw new BusinessException("项目下还有 " + services + " 个服务，请先迁移或删除服务");
        }
        Long tasks = deployTaskMapper.selectCount(new QueryWrapper<DeployTask>().eq("project_id", id));
        if (tasks != null && tasks > 0) {
            throw new BusinessException("项目下还有 " + tasks + " 个上线任务，不能删除");
        }
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, id));
        return projectMapper.deleteById(id) > 0;
    }

    @GetMapping("/{id}/members")
    public List<Map<String, Object>> members(@PathVariable Long id) {
        requireProject(id);
        projectAccessService.assertCanViewProject(id);
        List<ProjectMember> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id).orderByAsc(ProjectMember::getId));
        Map<Long, String> names = userNames(members.stream().map(ProjectMember::getUserId).collect(Collectors.toSet()));
        return members.stream().map(m -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", m.getUserId());
            item.put("name", names.getOrDefault(m.getUserId(), "用户#" + m.getUserId()));
            item.put("role", m.getRole());
            item.put("createTime", m.getCreateTime());
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 添加成员或修改成员角色；userIds 用于一次添加多个成员，userId 用于单个
     */
    @PostMapping("/{id}/members")
    @Transactional(rollbackFor = Exception.class)
    public boolean saveMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Project project = requireProject(id);
        projectAccessService.assertProjectAdmin(id);
        Set<Long> userIds = new LinkedHashSet<>();
        if (body.get("userIds") instanceof List) {
            for (Object raw : (List<?>) body.get("userIds")) {
                Long userId = toLong(raw);
                if (userId != null) {
                    userIds.add(userId);
                }
            }
        }
        Long single = toLong(body.get("userId"));
        if (single != null) {
            userIds.add(single);
        }
        if (userIds.isEmpty()) {
            throw new BusinessException("请选择用户");
        }
        String role = body.get("role") != null ? String.valueOf(body.get("role")).trim().toUpperCase() : "";
        if (!ProjectMember.isValidRole(role)) {
            throw new BusinessException("角色只能是 ADMIN / DEVELOPER / VIEWER");
        }
        for (Long userId : userIds) {
            requireUser(userId);
            if (userId.equals(project.getOwnerUserId()) && !ProjectMember.ROLE_ADMIN.equals(role)) {
                throw new BusinessException("项目负责人必须是项目管理员，如需降级请先更换负责人");
            }
        }
        userIds.forEach(userId -> upsertMember(id, userId, role));
        return true;
    }

    @DeleteMapping("/{id}/members/{userId}")
    public boolean removeMember(@PathVariable Long id, @PathVariable Long userId) {
        Project project = requireProject(id);
        projectAccessService.assertProjectAdmin(id);
        if (userId.equals(project.getOwnerUserId())) {
            throw new BusinessException("不能移除项目负责人，请先更换负责人");
        }
        return projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id).eq(ProjectMember::getUserId, userId)) > 0;
    }

    /**
     * 添加成员时的候选用户（仅平台管理员或某个项目的管理员可调用）
     */
    @GetMapping("/user-options")
    public List<Map<String, Object>> userOptions() {
        if (!projectAccessService.isPlatformAdmin() && !isAdminOfAnyProject()) {
            throw new BusinessException("仅项目管理员可以查看用户列表");
        }
        return userMapper.selectList(new QueryWrapper<User>().eq("status", 1).orderByAsc("username"))
                .stream().map(u -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", u.getId());
                    item.put("name", displayName(u));
                    return item;
                }).collect(Collectors.toList());
    }

    private boolean isAdminOfAnyProject() {
        Set<Long> ids = projectAccessService.accessibleProjectIds();
        if (ids == null) {
            return true;
        }
        return ids.stream().anyMatch(pid -> ProjectMember.ROLE_ADMIN.equals(projectAccessService.roleOf(pid)));
    }

    private void upsertMember(Long projectId, Long userId, String role) {
        ProjectMember existing = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId).eq(ProjectMember::getUserId, userId).last("LIMIT 1"));
        if (existing == null) {
            ProjectMember member = new ProjectMember();
            member.setProjectId(projectId);
            member.setUserId(userId);
            member.setRole(role);
            member.setCreateTime(LocalDateTime.now());
            projectMemberMapper.insert(member);
        } else if (!role.equals(existing.getRole())) {
            existing.setRole(role);
            projectMemberMapper.updateById(existing);
        }
    }

    private Project requireProject(Long id) {
        Project project = id != null ? projectMapper.selectById(id) : null;
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    /** null 视为未指定；非 null 时必须是存在的用户 */
    private Long requireUser(Long userId) {
        if (userId != null && userMapper.selectById(userId) == null) {
            throw new BusinessException("用户不存在");
        }
        return userId;
    }

    private String normalizeName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new BusinessException("项目名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new BusinessException("项目名称不能超过 100 个字符");
        }
        return trimmed;
    }

    private void assertNameUnique(String name, Long excludeId) {
        QueryWrapper<Project> wrapper = new QueryWrapper<Project>().eq("name", name);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (projectMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("项目名称已存在");
        }
    }

    private Map<String, Object> toMap(Project p, Map<Long, String> ownerNames) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", p.getId());
        item.put("name", p.getName());
        item.put("description", p.getDescription());
        item.put("ownerUserId", p.getOwnerUserId());
        item.put("ownerName", p.getOwnerUserId() != null ? ownerNames.get(p.getOwnerUserId()) : null);
        item.put("status", p.getStatus());
        item.put("createTime", p.getCreateTime());
        return item;
    }

    private Map<Long, String> userNames(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, this::displayName, (a, b) -> a));
    }

    private static Set<Long> singletonOrEmpty(Long id) {
        return id != null ? Collections.singleton(id) : Collections.emptySet();
    }

    private String displayName(User user) {
        return user.getRealName() != null && !user.getRealName().trim().isEmpty()
                ? user.getRealName().trim() + "(" + user.getUsername() + ")" : user.getUsername();
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static Long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Long.parseLong(String.valueOf(value).trim());
            } catch (NumberFormatException e) {
                throw new BusinessException("用户 ID 格式错误");
            }
        }
        return null;
    }
}
