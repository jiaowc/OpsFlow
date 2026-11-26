package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

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
     * 扩展字段（如企业版的第三方账号绑定），非 user 表字段；
     * 由 {@link com.opsflow.spi.UserAccountExtension} 读写，核心不解析其中的内容
     */
    private Map<String, Object> extra;

    /** 是否已绑定两步验证（只读，列表和详情返回） */
    private Boolean twoFactorEnabled;
    
    /**
     * 用户角色列表
     */
    private List<RoleDTO> roles;
    
    /**
     * 角色ID列表（用于创建/更新）
     */
    private List<Long> roleIds;
}

