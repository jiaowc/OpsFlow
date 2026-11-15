package com.opsflow.service;

import com.opsflow.api.dto.PipelineTemplateDTO;
import com.opsflow.api.dto.TemplateVariableCheckDTO;

import java.util.List;

public interface PipelineTemplateService {

    PipelineTemplateDTO create(PipelineTemplateDTO request);

    List<PipelineTemplateDTO> list(String type);

    PipelineTemplateDTO getById(Long id);

    PipelineTemplateDTO update(Long id, PipelineTemplateDTO request);

    boolean delete(Long id);

    /**
     * 检查模版内容里的 ${变量} 写法，只检查不保存。
     *
     * @param request 只用到 type 和 content
     */
    TemplateVariableCheckDTO checkVariables(PipelineTemplateDTO request);
}
