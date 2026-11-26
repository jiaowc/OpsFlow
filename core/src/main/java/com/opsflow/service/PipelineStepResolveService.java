package com.opsflow.service;

import com.opsflow.api.dto.PipelineStepDTO;

import java.util.List;

/**
 * 将 Pipeline 中的步骤引用解析为可执行步骤
 */
public interface PipelineStepResolveService {

    List<PipelineStepDTO> resolveSteps(List<PipelineStepDTO> rawSteps);
}
