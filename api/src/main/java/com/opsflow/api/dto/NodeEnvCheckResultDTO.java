package com.opsflow.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 节点环境检测结果
 */
@Data
public class NodeEnvCheckResultDTO {

    private Long nodeId;

    private String nodeName;

    private String nodeType;

    /**
     * 必检项是否全部通过
     */
    private Boolean passed;

    private String summary;

    private List<NodeEnvCheckItemDTO> items;
}
