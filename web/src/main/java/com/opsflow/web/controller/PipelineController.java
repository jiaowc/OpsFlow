package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.BuildNode;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pipeline管理控制器
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildNodeMapper buildNodeMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建Pipeline
     */
    @PostMapping("/create")
    public PipelineDTO createPipeline(@RequestBody PipelineDTO request) {
        Pipeline pipeline = new Pipeline();
        BeanUtils.copyProperties(request, pipeline, "steps", "parameterDefinitions");
        applyBuildNodeSelections(pipeline, request);
        
        // 将steps转换为JSON字符串
        try {
            if (request.getSteps() != null && !request.getSteps().isEmpty()) {
                pipeline.setStepsConfig(objectMapper.writeValueAsString(request.getSteps()));
            }
        } catch (Exception e) {
            throw new RuntimeException("步骤配置格式错误", e);
        }
        
        // 将parameterDefinitions转换为JSON字符串
        try {
            if (request.getParameterDefinitions() != null && !request.getParameterDefinitions().isEmpty()) {
                pipeline.setParameterDefinitions(objectMapper.writeValueAsString(request.getParameterDefinitions()));
            }
        } catch (Exception e) {
            throw new RuntimeException("参数定义格式错误", e);
        }
        
        pipeline.setStatus(1);
        pipeline.setCreateTime(LocalDateTime.now());
        pipeline.setUpdateTime(LocalDateTime.now());
        
        pipelineMapper.insert(pipeline);
        
        return getPipelineDTO(pipeline);
    }

    /**
     * 查询Pipeline列表
     */
    @GetMapping("/list")
    public List<PipelineDTO> listPipelines() {
        QueryWrapper<Pipeline> wrapper = new QueryWrapper<>();
        // 显示所有pipeline，不限制status
        wrapper.orderByDesc("create_time");
        
        List<Pipeline> pipelines = pipelineMapper.selectList(wrapper);
        return pipelines.stream().map(this::getPipelineDTO).collect(Collectors.toList());
    }

    /**
     * 查询Pipeline详情
     */
    @GetMapping("/{id}")
    public PipelineDTO getPipeline(@PathVariable Long id) {
        Pipeline pipeline = pipelineMapper.selectById(id);
        if (pipeline == null) {
            return null;
        }
        return getPipelineDTO(pipeline);
    }

    /**
     * 更新Pipeline
     */
    @PutMapping("/{id}")
    public PipelineDTO updatePipeline(@PathVariable Long id, @RequestBody PipelineDTO request) {
        Pipeline pipeline = pipelineMapper.selectById(id);
        if (pipeline == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, pipeline, "id", "createTime", "steps", "parameterDefinitions");
        applyBuildNodeSelections(pipeline, request);
        
        // 更新steps配置
        try {
            if (request.getSteps() != null) {
                pipeline.setStepsConfig(objectMapper.writeValueAsString(request.getSteps()));
            }
        } catch (Exception e) {
            throw new RuntimeException("步骤配置格式错误", e);
        }
        
        // 更新parameterDefinitions
        try {
            if (request.getParameterDefinitions() != null) {
                pipeline.setParameterDefinitions(objectMapper.writeValueAsString(request.getParameterDefinitions()));
            }
        } catch (Exception e) {
            throw new RuntimeException("参数定义格式错误", e);
        }
        
        pipeline.setUpdateTime(LocalDateTime.now());
        pipelineMapper.updateById(pipeline);
        
        return getPipelineDTO(pipeline);
    }

    /**
     * 删除Pipeline
     */
    @DeleteMapping("/{id}")
    public boolean deletePipeline(@PathVariable Long id) {
        return pipelineMapper.deleteById(id) > 0;
    }

    private PipelineDTO getPipelineDTO(Pipeline pipeline) {
        PipelineDTO dto = new PipelineDTO();
        BeanUtils.copyProperties(pipeline, dto, "stepsConfig", "parameterDefinitions");
        
        // 解析stepsConfig JSON
        try {
            if (pipeline.getStepsConfig() != null && !pipeline.getStepsConfig().isEmpty()) {
                List<PipelineStepDTO> steps = objectMapper.readValue(
                    pipeline.getStepsConfig(),
                    new TypeReference<List<PipelineStepDTO>>() {}
                );
                dto.setSteps(steps);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        // 解析parameterDefinitions JSON
        try {
            if (pipeline.getParameterDefinitions() != null && !pipeline.getParameterDefinitions().isEmpty()) {
                Map<String, String> parameterDefinitions = objectMapper.readValue(
                    pipeline.getParameterDefinitions(),
                    new TypeReference<Map<String, String>>() {}
                );
                dto.setParameterDefinitions(parameterDefinitions);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }

        if (pipeline.getBuildNodeId() != null) {
            BuildNode buildNode = buildNodeMapper.selectById(pipeline.getBuildNodeId());
            if (buildNode != null) {
                dto.setBuildNodeName(buildNode.getName());
            }
        }
        dto.setBuildNodeIds(parseBuildNodeIds(pipeline.getBuildNodeIds(), pipeline.getBuildNodeId()));
        if (pipeline.getDeployNodeId() != null) {
            BuildNode deployNode = buildNodeMapper.selectById(pipeline.getDeployNodeId());
            if (deployNode != null) {
                dto.setDeployNodeName(deployNode.getName());
            }
        }
        
        return dto;
    }

    private void applyBuildNodeSelections(Pipeline pipeline, PipelineDTO request) {
        List<Long> buildNodeIds = normalizeBuildNodeIds(request.getBuildNodeIds(), request.getBuildNodeId());
        pipeline.setBuildNodeId(buildNodeIds.isEmpty() ? null : buildNodeIds.get(0));
        pipeline.setBuildNodeIds(buildNodeIds.isEmpty()
            ? null
            : buildNodeIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private List<Long> normalizeBuildNodeIds(List<Long> buildNodeIds, Long buildNodeId) {
        List<Long> ids = new ArrayList<>();
        if (buildNodeIds != null) {
            buildNodeIds.stream().filter(Objects::nonNull).distinct().forEach(ids::add);
        }
        if (ids.isEmpty() && buildNodeId != null) {
            ids.add(buildNodeId);
        }
        return ids;
    }

    private List<Long> parseBuildNodeIds(String buildNodeIds, Long fallbackBuildNodeId) {
        List<Long> ids = new ArrayList<>();
        if (buildNodeIds != null && !buildNodeIds.trim().isEmpty()) {
            for (String part : buildNodeIds.split(",")) {
                String text = part == null ? "" : part.trim();
                if (text.isEmpty()) continue;
                try {
                    Long id = Long.parseLong(text);
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        if (ids.isEmpty() && fallbackBuildNodeId != null) {
            ids.add(fallbackBuildNodeId);
        }
        return ids;
    }
}

