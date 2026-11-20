package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * 用户DTO
 */
@Data
public class UserDTO {
    
    private Long id;
    
    private String username;
    
    /**
     * 密码（用于创建/更新，不返回给前端）
     */
    private String password;
    
    private String realName;
    
    private String email;
    
    private String phone;
    
    private Integer status;

    /**
     * 来源：local / feishu / ldap
     */
    private String source;

    /**
     * 飞书 user_id（用于审批卡片推送，非 user 表字段）
     */
    private String feishuUserId;
    
    /**
     * 用户角色列表
     */
    private List<RoleDTO> roles;
    
    /**
     * 角色ID列表（用于创建/更新）
     */
    private List<Long> roleIds;
}

