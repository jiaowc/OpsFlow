package com.opsflow.api.dto;

import lombok.Data;
import java.util.List;

/**
 * 项目DTO
 */
@Data
public class ProjectDTO {
    
    private Long id;
    
    private String name;
    
    private String code;
    
    private String description;
    
    private List<Long> envIds;
    
    private List<String> envNames;
    
    private Integer status;
}


