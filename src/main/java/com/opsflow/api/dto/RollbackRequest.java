package com.opsflow.api.dto;

import lombok.Data;

/**
 * 回滚请求：选择一条历史成功 BuildJob 的镜像重新部署
 */
@Data
public class RollbackRequest {

    /**
     * 作为回滚目标的历史成功 Job ID（须同服务、同环境，且有 imageFullName）
     */
    private Long sourceJobId;

    /**
     * 前端二次确认标记；生产环境必须为 true
     */
    private Boolean confirmed;
}
