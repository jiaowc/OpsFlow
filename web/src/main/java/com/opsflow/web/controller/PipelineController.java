package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.model.Pipeline;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pipeline管理控制器
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    @Autowired
    private PipelineMapper pipelineMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建Pipeline
     */
    @PostMapping("/create")
    public PipelineDTO createPipeline(@RequestBody PipelineDTO request) {
        Pipeline pipeline = new Pipeline();
        BeanUtils.copyProperties(request, pipeline, "steps", "parameterDefinitions");
        
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
        
        return dto;
    }
}

