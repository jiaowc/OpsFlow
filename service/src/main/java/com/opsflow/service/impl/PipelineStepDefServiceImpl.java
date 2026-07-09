package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineStepDefDTO;
import com.opsflow.common.exception.BusinessException;
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
        entity.setUpdateTime(LocalDateTime.now());
        pipelineStepDefMapper.updateById(entity);
        return toDto(entity);
    }

    @Override
    public boolean delete(Long id) {
        return pipelineStepDefMapper.deleteById(id) > 0;
    }

    private void validate(PipelineStepDefDTO request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("步骤名称不能为空");
        }
        if (request.getStepType() == null || request.getStepType().trim().isEmpty()) {
            throw new BusinessException("步骤类型不能为空");
        }
        if (request.getPhase() == null || request.getPhase().trim().isEmpty()) {
            throw new BusinessException("步骤阶段不能为空");
        }
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
