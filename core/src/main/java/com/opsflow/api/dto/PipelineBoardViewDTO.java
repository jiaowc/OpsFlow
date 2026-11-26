package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 流水线列表视图（按环境筛选，类似 Jenkins View）
 */
@Data
public class PipelineBoardViewDTO {

    private Long id;

    private String name;

    private Long envId;

    /** 关联环境名称（展示用） */
    private String envName;

    private String description;

    private Integer sortOrder;

    private Integer status;

    /** 可见角色 ID 列表（管理端读写；列表过滤用） */
    private List<Long> roleIds;

    /** 可见角色名称（管理端展示） */
    private List<String> roleNames;
}
