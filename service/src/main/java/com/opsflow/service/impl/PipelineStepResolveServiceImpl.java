package com.opsflow.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.PipelineStepDefMapper;
import com.opsflow.dao.model.PipelineStepDef;
import com.opsflow.service.PipelineStepResolveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PipelineStepResolveServiceImpl implements PipelineStepResolveService {

    @Autowired
    private PipelineStepDefMapper pipelineStepDefMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<PipelineStepDTO> resolveSteps(List<PipelineStepDTO> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty()) {
            return new ArrayList<>();
        }

        List<PipelineStepDTO> resolved = new ArrayList<>();
        for (PipelineStepDTO ref : rawSteps) {
            if (ref.getStepTemplateId() != null) {
                PipelineStepDef def = pipelineStepDefMapper.selectById(ref.getStepTemplateId());
                if (def == null) {
                    continue;
                }
                PipelineStepDTO step = new PipelineStepDTO();
                step.setStepTemplateId(def.getId());
                step.setStepType(def.getStepType());
                step.setStepName(def.getName());
                step.setOrder(ref.getOrder());
                step.setEnabled(ref.getEnabled() == null ? Boolean.TRUE : ref.getEnabled());
                step.setNodeSelection(ref.getNodeSelection());
                step.setNodeId(ref.getNodeId());

                Map<String, String> params = parseContentConfig(def.getContentConfig());
                if (ref.getParameters() != null) {
                    params.putAll(ref.getParameters());
                }
                step.setParameters(params);
                resolved.add(step);
            } else {
                resolved.add(ref);
            }
        }

        return resolved.stream()
                .sorted(Comparator.comparing(step -> step.getOrder() != null ? step.getOrder() : 0))
                .collect(Collectors.toList());
    }

    private Map<String, String> parseContentConfig(String contentConfig) {
        try {
            if (contentConfig == null || contentConfig.trim().isEmpty()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(contentConfig, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
