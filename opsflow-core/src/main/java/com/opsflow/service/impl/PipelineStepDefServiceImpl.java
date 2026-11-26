package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineStepDefDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.RequiredFields;
import com.opsflow.dao.mapper.PipelineStepDefMapper;
import com.opsflow.dao.model.PipelineStepDef;
import com.opsflow.service.PipelineStepDefService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PipelineStepDefServiceImpl implements PipelineStepDefService {

    @Autowired
    private PipelineStepDefMapper pipelineStepDefMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public PipelineStepDefDTO create(PipelineStepDefDTO request) {
        validate(request);
        PipelineStepDef entity = new PipelineStepDef();
        BeanUtils.copyProperties(request, entity, "contentConfig");
        entity.setContentConfig(serializeContent(request.getContentConfig()));
        entity.setTimeoutSeconds(normalizeTimeoutSeconds(request.getTimeoutSeconds()));
        entity.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        pipelineStepDefMapper.insert(entity);
        return toDto(entity);
    }

    @Override
    public List<PipelineStepDefDTO> list(String phase, String stepType) {
        QueryWrapper<PipelineStepDef> wrapper = new QueryWrapper<>();
        if (phase != null && !phase.isEmpty()) {
            wrapper.eq("phase", phase);
        }
        if (stepType != null && !stepType.isEmpty()) {
            wrapper.eq("step_type", stepType);
        }
        wrapper.orderByDesc("create_time");
        return pipelineStepDefMapper.selectList(wrapper).stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public PipelineStepDefDTO getById(Long id) {
        PipelineStepDef entity = pipelineStepDefMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toDto(entity);
    }

    @Override
    public PipelineStepDefDTO update(Long id, PipelineStepDefDTO request) {
        PipelineStepDef entity = pipelineStepDefMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("步骤不存在");
        }
        validate(request);
        BeanUtils.copyProperties(request, entity, "id", "createTime", "contentConfig");
        entity.setContentConfig(serializeContent(request.getContentConfig()));
        entity.setTimeoutSeconds(normalizeTimeoutSeconds(request.getTimeoutSeconds()));
        entity.setUpdateTime(LocalDateTime.now());
        pipelineStepDefMapper.updateById(entity);
        return toDto(entity);
    }

    @Override
    public boolean delete(Long id) {
        return pipelineStepDefMapper.deleteById(id) > 0;
    }

    /** 各步骤类型必填的内容项，与步骤定义表单上带星号的字段一致（step-def.js stepContentFields 里 required: true 的项） */
    private static final Map<String, String[][]> REQUIRED_CONTENT = new java.util.HashMap<>();

    static {
        REQUIRED_CONTENT.put("build", new String[][]{{"buildCommand", "构建命令"}});
        REQUIRED_CONTENT.put("docker_build", new String[][]{
                {"dockerfileTemplateId", "Dockerfile 模版"}, {"dockerBuildCommand", "docker build 命令"}});
        REQUIRED_CONTENT.put("push_image", new String[][]{{"dockerPushCommand", "docker push 命令"}});
        REQUIRED_CONTENT.put("render_template", new String[][]{{"deploymentTemplateId", "Deployment 模版"}});
    }

    /** 新建和修改都是整体提交，必填项缺了就拒绝；名称、类型、阶段保存 trim 后的值，内容项原样保存 */
    private void validate(PipelineStepDefDTO request) {
        request.setName(RequiredFields.requireText(request.getName(), "步骤名称"));
        request.setStepType(RequiredFields.requireText(request.getStepType(), "步骤类型"));
        request.setPhase(RequiredFields.requireText(request.getPhase(), "步骤阶段"));
        // 超时时间没带默认 60（见 normalizeTimeoutSeconds）
        if (request.getTimeoutSeconds() != null && request.getTimeoutSeconds() < 1) {
            throw new BusinessException("超时时间不能小于1秒");
        }
        String[][] required = REQUIRED_CONTENT.get(request.getStepType());
        if (required != null) {
            Map<String, String> content = request.getContentConfig();
            for (String[] item : required) {
                if (content == null || !org.springframework.util.StringUtils.hasText(content.get(item[0]))) {
                    throw new BusinessException(item[1] + "不能为空");
                }
            }
        }
    }

    private int normalizeTimeoutSeconds(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds < 1) {
            return 60;
        }
        return timeoutSeconds;
    }

    private String serializeContent(Map<String, String> contentConfig) {
        try {
            if (contentConfig == null || contentConfig.isEmpty()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(contentConfig);
        } catch (Exception e) {
            throw new BusinessException("步骤内容配置格式错误");
        }
    }

    private PipelineStepDefDTO toDto(PipelineStepDef entity) {
        PipelineStepDefDTO dto = new PipelineStepDefDTO();
        BeanUtils.copyProperties(entity, dto, "contentConfig");
        try {
            if (entity.getContentConfig() != null && !entity.getContentConfig().isEmpty()) {
                dto.setContentConfig(objectMapper.readValue(
                        entity.getContentConfig(), new TypeReference<Map<String, String>>() {}));
            }
        } catch (Exception ignored) {
            // ignore parse error
        }
        return dto;
    }
}
