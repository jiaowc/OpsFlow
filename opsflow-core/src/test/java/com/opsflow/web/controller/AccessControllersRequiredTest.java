package com.opsflow.web.controller;

import com.opsflow.api.dto.PermissionDTO;
import com.opsflow.api.dto.RoleDTO;
import com.opsflow.api.dto.UserDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.PermissionMapper;
import com.opsflow.dao.mapper.RoleMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.Permission;
import com.opsflow.dao.model.Role;
import com.opsflow.dao.model.User;
import com.opsflow.support.MockInjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户、角色、权限表单的必填项 */
class AccessControllersRequiredTest {

    private static String error(Runnable call) {
        return assertThrows(BusinessException.class, call::run).getMessage();
    }

    // ---------- 用户 ----------

    @Test
    void userCreateRequiresUsernameAndNonBlankPassword() {
        UserController controller = MockInjector.inject(new UserController());
        UserMapper mapper = MockInjector.field(controller, "userMapper");

        UserDTO noName = new UserDTO();
        noName.setPassword("Passw0rd!");
        assertEquals("用户名不能为空", error(() -> controller.createUser(noName)));

        UserDTO blankPassword = new UserDTO();
        blankPassword.setUsername("alice");
        blankPassword.setPassword("   ");
        assertEquals("密码不能为空", error(() -> controller.createUser(blankPassword)));
        verify(mapper, never()).insert(any(User.class));

        UserDTO ok = new UserDTO();
        ok.setUsername(" alice ");
        ok.setPassword(" Passw0rd! ");
        controller.createUser(ok);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(mapper).insert(captor.capture());
        assertEquals("alice", captor.getValue().getUsername());
        assertNotEquals(" Passw0rd! ", captor.getValue().getPassword(), "入库的是哈希");
    }

    @Test
    void userUpdateRejectsBlankUsernameAndKeepsPasswordWhenBlank() {
        UserController controller = MockInjector.inject(new UserController());
        UserMapper mapper = MockInjector.field(controller, "userMapper");
        User stored = new User();
        stored.setId(3L);
        stored.setUsername("alice");
        stored.setPassword("hashed");
        stored.setStatus(1);
        when(mapper.selectById(3L)).thenReturn(stored);

        UserDTO blank = new UserDTO();
        blank.setUsername(" ");
        assertEquals("用户名不能为空", error(() -> controller.updateUser(3L, blank)));
        verify(mapper, never()).updateById(any(User.class));

        UserDTO partial = new UserDTO();
        partial.setRealName("Alice");
        partial.setPassword("  ");
        controller.updateUser(3L, partial);
        assertEquals("alice", stored.getUsername(), "没带用户名保持原值");
        assertEquals("hashed", stored.getPassword(), "密码留空或只有空白表示不改");
        verify(mapper).updateById(stored);
    }

    // ---------- 角色 ----------

    @Test
    void roleCreateAndUpdateRejectBlankNameOrCode() {
        RoleController controller = MockInjector.inject(new RoleController());
        RoleMapper mapper = MockInjector.field(controller, "roleMapper");

        RoleDTO noCode = new RoleDTO();
        noCode.setName("运维");
        noCode.setCode(" ");
        assertEquals("角色代码不能为空", error(() -> controller.createRole(noCode)));

        Role stored = new Role();
        stored.setId(5L);
        stored.setName("运维");
        stored.setCode("OPS");
        when(mapper.selectById(5L)).thenReturn(stored);
        RoleDTO blankName = new RoleDTO();
        blankName.setName("");
        assertEquals("角色名称不能为空", error(() -> controller.updateRole(5L, blankName)));
        verify(mapper, never()).updateById(any(Role.class));

        RoleDTO partial = new RoleDTO();
        partial.setDescription("只改描述");
        controller.updateRole(5L, partial);
        assertEquals("运维", stored.getName());
        assertEquals("OPS", stored.getCode());

        RoleDTO renamed = new RoleDTO();
        renamed.setName(" 运维组 ");
        controller.updateRole(5L, renamed);
        assertEquals("运维组", stored.getName());
    }

    // ---------- 权限 ----------

    @Test
    void permissionCreateRequiresNameAndCode() {
        PermissionController controller = MockInjector.inject(new PermissionController());
        PermissionMapper mapper = MockInjector.field(controller, "permissionMapper");

        PermissionDTO noName = new PermissionDTO();
        noName.setCode("x:view");
        assertEquals("权限名称不能为空", error(() -> controller.createPermission(noName)));
        PermissionDTO noCode = new PermissionDTO();
        noCode.setName("查看");
        assertEquals("权限代码不能为空", error(() -> controller.createPermission(noCode)));
        verify(mapper, never()).insert(any(Permission.class));

        PermissionDTO ok = new PermissionDTO();
        ok.setName(" 查看 ");
        ok.setCode(" x:view ");
        controller.createPermission(ok);
        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(mapper).insert(captor.capture());
        assertEquals("查看", captor.getValue().getName());
        assertEquals("x:view", captor.getValue().getCode());
    }

    @Test
    void permissionUpdateRejectsBlankAndKeepsAbsent() {
        PermissionController controller = MockInjector.inject(new PermissionController());
        PermissionMapper mapper = MockInjector.field(controller, "permissionMapper");
        Permission stored = new Permission();
        stored.setId(8L);
        stored.setName("查看");
        stored.setCode("x:view");
        when(mapper.selectById(8L)).thenReturn(stored);

        PermissionDTO blank = new PermissionDTO();
        blank.setCode(" ");
        assertEquals("权限代码不能为空", error(() -> controller.updatePermission(8L, blank)));

        PermissionDTO partial = new PermissionDTO();
        partial.setDescription("说明");
        controller.updatePermission(8L, partial);
        assertEquals("查看", stored.getName());
        assertEquals("x:view", stored.getCode());
    }
}
