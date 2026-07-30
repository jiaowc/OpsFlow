package com.opsflow.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.api.dto.PipelineTemplateDTO;
import com.opsflow.dao.mapper.PipelineStepDefMapper;
import com.opsflow.dao.model.PipelineStepDef;
import com.opsflow.service.PipelineStepResolveService;
import com.opsflow.service.PipelineTemplateService;
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

    @Autowired
    private PipelineTemplateService pipelineTemplateService;

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
                step.setTimeoutSeconds(normalizeTimeoutSeconds(def.getTimeoutSeconds()));

                Map<String, String> params = parseContentConfig(def.getContentConfig());
                // 按模版 ID 拉取最新内容，避免步骤里缓存的旧模版正文
                refreshLinkedTemplates(params);
                if (ref.getParameters() != null) {
                    params.putAll(ref.getParameters());
                }
                step.setParameters(params);
                resolved.add(step);
            } else {
                if (ref.getTimeoutSeconds() == null || ref.getTimeoutSeconds() < 1) {
                    ref.setTimeoutSeconds(60);
                }
                if (ref.getParameters() != null) {
                    refreshLinkedTemplates(ref.getParameters());
                }
                resolved.add(ref);
            }
        }

        return resolved.stream()
                .sorted(Comparator.comparing(step -> step.getOrder() != null ? step.getOrder() : 0))
                .collect(Collectors.toList());
    }

    /**
     * 若步骤关联了模版 ID，用模版管理中的最新内容覆盖本地缓存字段。
     */
    private void refreshLinkedTemplates(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        applyTemplateContent(params, "dockerfileTemplateId", "dockerfileContent", null);
        applyTemplateContent(params, "deploymentTemplateId", "deploymentTemplateContent", null);
        applyTemplateContent(params, "serviceTemplateId", "serviceTemplateContent", "serviceType");
    }

    private void applyTemplateContent(Map<String, String> params, String idKey, String contentKey, String serviceTypeKey) {
        String idRaw = params.get(idKey);
        if (idRaw == null || idRaw.trim().isEmpty()) {
            return;
        }
        Long templateId;
        try {
            templateId = Long.parseLong(idRaw.trim());
        } catch (NumberFormatException e) {
            return;
        }
        PipelineTemplateDTO template = pipelineTemplateService.getById(templateId);
        if (template == null || template.getContent() == null) {
            return;
        }
        params.put(contentKey, template.getContent());
        if (serviceTypeKey != null && template.getServiceType() != null && !template.getServiceType().trim().isEmpty()) {
            params.put(serviceTypeKey, template.getServiceType().trim());
        }
    }

    private int normalizeTimeoutSeconds(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            return 60;
        }
        return timeoutSeconds;
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
