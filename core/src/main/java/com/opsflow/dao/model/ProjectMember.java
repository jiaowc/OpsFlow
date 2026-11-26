package com.opsflow.dao.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("project_member")
public class ProjectMember {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_VIEWER = "VIEWER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private Long userId;

    /**
     * ADMIN 项目管理员 / DEVELOPER 开发 / VIEWER 只读
     */
    private String role;

    private LocalDateTime createTime;

    public static boolean isValidRole(String role) {
        return ROLE_ADMIN.equals(role) || ROLE_DEVELOPER.equals(role) || ROLE_VIEWER.equals(role);
    }
}
