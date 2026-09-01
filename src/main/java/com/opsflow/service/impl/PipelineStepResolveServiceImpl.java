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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 解析流水线步骤引用：步骤定义提供默认配置，流水线 {@code parameters} / {@code timeoutSeconds} 覆盖差异项。
 * <p>
 * 合并顺序（方案 A）：
 * <ol>
 *   <li>步骤定义 {@code content_config} 作为默认</li>
 *   <li>流水线 {@code parameters} 覆盖（仅写入的键生效）</li>
 *   <li>按最终模版 ID 刷新模版正文</li>
 *   <li>若流水线显式覆盖了正文（dockerfileContent 等），正文再次胜出</li>
 *   <li>引擎超时：流水线 {@code timeoutSeconds} 优先，否则用步骤定义</li>
 * </ol>
 * </p>
 */
@Service
public class PipelineStepResolveServiceImpl implements PipelineStepResolveService {

    /** 流水线可显式覆盖、且应在模版刷新后仍保留的正文键 */
    private static final String[] EXPLICIT_CONTENT_KEYS = {
            "dockerfileContent",
            "deploymentTemplateContent",
            "serviceTemplateContent"
    };

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
                step.setTimeoutSeconds(resolveTimeoutSeconds(ref.getTimeoutSeconds(), def.getTimeoutSeconds()));

                Map<String, String> params = parseContentConfig(def.getContentConfig());
                Map<String, String> overrides = ref.getParameters();
                if (overrides != null && !overrides.isEmpty()) {
                    // 先合并覆盖（含模版 ID），再按最终 ID 刷正文
                    putNonBlank(params, overrides);
                }
                refreshLinkedTemplates(params);
                // 显式正文覆盖优先于「按 ID 刷新」的模版内容
                reapplyExplicitContent(params, overrides);
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

    private void putNonBlank(Map<String, String> target, Map<String, String> overrides) {
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String v = e.getValue();
            if (v == null) {
                continue;
            }
            // 空串表示「不覆盖」，保留步骤默认
            if (!StringUtils.hasText(v)) {
                continue;
            }
            target.put(e.getKey(), v);
        }
    }

    private void reapplyExplicitContent(Map<String, String> params, Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        for (String key : EXPLICIT_CONTENT_KEYS) {
            String v = overrides.get(key);
            if (StringUtils.hasText(v)) {
                params.put(key, v);
            }
        }
    }

    private int resolveTimeoutSeconds(Integer pipelineOverride, Integer defTimeout) {
        if (pipelineOverride != null && pipelineOverride >= 1) {
            return pipelineOverride;
        }
        return normalizeTimeoutSeconds(defTimeout);
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
