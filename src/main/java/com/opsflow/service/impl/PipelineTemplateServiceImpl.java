package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.PipelineTemplateDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.PipelineTemplateMapper;
import com.opsflow.dao.model.PipelineTemplate;
import com.opsflow.service.PipelineTemplateService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PipelineTemplateServiceImpl implements PipelineTemplateService {

    private static final Set<String> SUPPORTED_TYPES = new HashSet<>(
            Arrays.asList("dockerfile", "deployment", "service"));

    @Autowired
    private PipelineTemplateMapper pipelineTemplateMapper;

    @Override
    public PipelineTemplateDTO create(PipelineTemplateDTO request) {
        validate(request);
        PipelineTemplate entity = toEntity(request);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        pipelineTemplateMapper.insert(entity);
        return toDto(entity);
    }

    @Override
    public List<PipelineTemplateDTO> list(String type) {
        QueryWrapper<PipelineTemplate> wrapper = new QueryWrapper<>();
        if (type != null && !type.trim().isEmpty()) {
            wrapper.eq("type", type.trim().toLowerCase());
        }
        wrapper.orderByDesc("create_time");
        return pipelineTemplateMapper.selectList(wrapper).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public PipelineTemplateDTO getById(Long id) {
        PipelineTemplate entity = pipelineTemplateMapper.selectById(id);
        if (entity == null) {
            return null;
        }
        return toDto(entity);
    }

    @Override
    public PipelineTemplateDTO update(Long id, PipelineTemplateDTO request) {
        PipelineTemplate entity = pipelineTemplateMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("模版不存在");
        }
        validate(request);
        BeanUtils.copyProperties(request, entity, "id", "createTime", "type");
        entity.setUpdateTime(LocalDateTime.now());
        pipelineTemplateMapper.updateById(entity);
        return toDto(entity);
    }

    @Override
    public boolean delete(Long id) {
        return pipelineTemplateMapper.deleteById(id) > 0;
    }

    private void validate(PipelineTemplateDTO request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("模版名称不能为空");
        }
        if (request.getType() == null || !SUPPORTED_TYPES.contains(request.getType().trim().toLowerCase())) {
            throw new BusinessException("模版类型无效");
        }
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new BusinessException("模版内容不能为空");
        }
        String type = request.getType().trim().toLowerCase();
        if ("dockerfile".equals(type) && (request.getBaseImage() == null || request.getBaseImage().trim().isEmpty())) {
            throw new BusinessException("Dockerfile 模版需填写基础镜像");
        }
        if ("service".equals(type) && (request.getServiceType() == null || request.getServiceType().trim().isEmpty())) {
            throw new BusinessException("Service 模版需选择服务类型");
        }
    }

    private PipelineTemplate toEntity(PipelineTemplateDTO request) {
        PipelineTemplate entity = new PipelineTemplate();
        BeanUtils.copyProperties(request, entity);
        entity.setType(request.getType().trim().toLowerCase());
        entity.setName(request.getName().trim());
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription().trim());
        }
        return entity;
    }

    private PipelineTemplateDTO toDto(PipelineTemplate entity) {
        PipelineTemplateDTO dto = new PipelineTemplateDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
