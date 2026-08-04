package com.opsflow.api.dto;

import lombok.Data;

/**
 * 上线任务中的单个模块项（含归属人，用于协作编辑权限控制）。
 */
@Data
public class DeployModuleItemDTO {

    /** 完整镜像地址 registry/project/service:tag */
    private String image;

    /** 添加人用户名；仅本人可改删该模块 */
    private String ownerName;

    /** 添加人用户 ID（可选） */
    private Long ownerId;
}
