package com.opsflow.service;

import com.opsflow.api.dto.PipelineStepDefDTO;

import java.util.List;

public interface PipelineStepDefService {

    PipelineStepDefDTO create(PipelineStepDefDTO request);

    List<PipelineStepDefDTO> list(String phase, String stepType);

    PipelineStepDefDTO getById(Long id);

    PipelineStepDefDTO update(Long id, PipelineStepDefDTO request);

    boolean delete(Long id);
}
