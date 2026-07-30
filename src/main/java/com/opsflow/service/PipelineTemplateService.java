package com.opsflow.service;

import com.opsflow.api.dto.PipelineTemplateDTO;

import java.util.List;

public interface PipelineTemplateService {

    PipelineTemplateDTO create(PipelineTemplateDTO request);

    List<PipelineTemplateDTO> list(String type);

    PipelineTemplateDTO getById(Long id);

    PipelineTemplateDTO update(Long id, PipelineTemplateDTO request);

    boolean delete(Long id);
}
