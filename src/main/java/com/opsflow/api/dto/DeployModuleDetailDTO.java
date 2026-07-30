package com.opsflow.api.dto;

import lombok.Data;

/**
 * 上线模块详情 DTO。
 * <p>
 * 由镜像地址解析出服务标识与端口，供任务详情展示及
 * {@link com.opsflow.integration.pipeline.PipelineTemplateRenderer} 渲染 K8s 模版时注入变量。
 * </p>
 */
@Data
public class DeployModuleDetailDTO {

    /**
     * 完整镜像地址，如 {@code harbor.example.com/dev/opsflow:v1.0.0}
     */
    private String imageFullName;

    /**
     * 服务 code（镜像路径最后一段，对应 K8s 服务名）
     */
    private String serviceCode;

    /**
     * 服务显示名称（来自服务表，未匹配时回退为 serviceCode）
     */
    private String serviceName;

    /**
     * 服务端口，写入 Deployment/Service 模版的 ${servicePort} / ${port}，默认 8080
     */
    private Integer servicePort;
}
