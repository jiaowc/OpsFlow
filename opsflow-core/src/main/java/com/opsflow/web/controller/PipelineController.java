package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.common.constant.PipelineTypes;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.RequiredFields;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.dao.model.BuildNode;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 流水线模版 REST 接口。
 * <p>
 * 管理 CI / CD / CI/CD 三类流水线模版（步骤配置、参数定义、构建/部署节点）。
 * CD 模版也可以被扩展（如企业版上线任务）引用，由扩展实例化为 BuildJob 执行。
 * </p>
 */
@RestController
@RequestMapping("/api/pipeline")
public class PipelineController {

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private com.opsflow.dao.mapper.BuildJobMapper buildJobMapper;

    @Autowired(required = false)
    private List<com.opsflow.spi.ResourceReferenceChecker> referenceCheckers = new java.util.ArrayList<>();

    @Autowired
    private BuildNodeMapper buildNodeMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建流水线模版。
     *
     * @param request 模版名称、类型、步骤、参数定义、节点配置等
     * @return 创建后的模版详情
     */
    @RequiresPermission("pipeline_config:manage")
    @PostMapping("/create")
    public PipelineDTO createPipeline(@RequestBody PipelineDTO request) {
        String name = RequiredFields.requireText(request.getName(), "流水线名称");
        String pipelineType = validatePipelineType(request.getPipelineType());
        validateSteps(request.getSteps());
        Pipeline pipeline = new Pipeline();
        BeanUtils.copyProperties(request, pipeline, "steps", "parameterDefinitions");
        pipeline.setName(name);
        pipeline.setPipelineType(pipelineType);
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
        
        pipeline.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        pipeline.setCreateTime(LocalDateTime.now());
        pipeline.setUpdateTime(LocalDateTime.now());
        
        pipelineMapper.insert(pipeline);
        
        return getPipelineDTO(pipeline);
    }

    /**
     * 查询流水线模版列表。
     *
     * @param type 可选，流水线类型：ci / cd / cicd
     * @param status 可选，1-启用 0-禁用
     * @return 模版列表，按创建时间倒序
     */
    @RequiresPermission({"pipeline_config:manage", "pipeline:view", "deploy:create", "deploy:view"})
    @GetMapping("/list")
    public List<PipelineDTO> listPipelines(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer status) {
        QueryWrapper<Pipeline> wrapper = new QueryWrapper<>();
        String normalizedType = PipelineTypes.normalize(type);
        if (PipelineTypes.isValid(normalizedType)) {
            wrapper.eq("pipeline_type", normalizedType);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        
        List<Pipeline> pipelines = pipelineMapper.selectList(wrapper);
        return pipelines.stream().map(this::getPipelineDTO).collect(Collectors.toList());
    }

    /**
     * 查询单个流水线模版详情。
     *
     * @param id 模版 ID
     * @return 模版详情；不存在时返回 {@code null}
     */
    @RequiresPermission({"pipeline_config:manage", "pipeline:view", "deploy:create"})
    @GetMapping("/{id}")
    public PipelineDTO getPipeline(@PathVariable Long id) {
        Pipeline pipeline = pipelineMapper.selectById(id);
        if (pipeline == null) {
            return null;
        }
        return getPipelineDTO(pipeline);
    }

    /**
     * 更新流水线模版。
     *
     * @param id 模版 ID
     * @param request 待更新字段
     * @return 更新后的模版详情；不存在时返回 {@code null}
     */
    @RequiresPermission("pipeline_config:manage")
    @PutMapping("/{id}")
    public PipelineDTO updatePipeline(@PathVariable Long id, @RequestBody PipelineDTO request) {
        Pipeline pipeline = pipelineMapper.selectById(id);
        if (pipeline == null) {
            return null;
        }

        String pipelineType = request.getPipelineType() != null
                ? validatePipelineType(request.getPipelineType())
                : (StringUtils.hasText(pipeline.getPipelineType())
                    ? pipeline.getPipelineType()
                    : PipelineTypes.CICD);
        
        // 名称没带表示不改，带了空值拒绝
        String name = RequiredFields.optionalText(request.getName(), "流水线名称");
        validateSteps(request.getSteps());
        BeanUtils.copyProperties(request, pipeline, "id", "createTime", "steps", "parameterDefinitions", "name");
        if (name != null) {
            pipeline.setName(name);
        }
        pipeline.setPipelineType(pipelineType);
        applyBuildNodeSelections(pipeline, request);
        pipeline.setDeployNodeId(request.getDeployNodeId());
        
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
     * 删除流水线模版。
     *
     * @param id 模版 ID
     * @return 是否删除成功
     */
    @RequiresPermission("pipeline_config:manage")
    @DeleteMapping("/{id}")
    public boolean deletePipeline(@PathVariable Long id) {
        long jobs = buildJobMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.opsflow.dao.model.BuildJob>()
                .eq("pipeline_template_id", id));
        if (jobs > 0) {
            throw new com.opsflow.common.exception.BusinessException(
                    "该模版仍被流水线任务使用（共 " + jobs + " 条构建记录），请先把相关任务改用其他模版或删除任务；不再使用可改为禁用");
        }
        for (com.opsflow.spi.ResourceReferenceChecker checker : referenceCheckers) {
            checker.assertDeletable(com.opsflow.spi.ResourceReferenceChecker.PIPELINE_TEMPLATE, id);
        }
        return pipelineMapper.deleteById(id) > 0;
    }

    /**
     * 克隆流水线模版：复制类型、步骤、节点、参数等，生成新模版。
     *
     * @param id 源模版 ID
     * @param request 可选；仅使用 {@code name}，为空时自动命名为「原名 副本」
     * @return 新建的模版详情
     */
    @RequiresPermission("pipeline_config:manage")
    @PostMapping("/{id}/clone")
    public PipelineDTO clonePipeline(@PathVariable Long id,
                                     @RequestBody(required = false) PipelineDTO request) {
        Pipeline source = pipelineMapper.selectById(id);
        if (source == null) {
            throw new BusinessException("流水线模版不存在");
        }

        String newName = request != null && StringUtils.hasText(request.getName())
                ? request.getName().trim()
                : buildCloneName(source.getName());

        Pipeline clone = new Pipeline();
        clone.setName(newName);
        clone.setPipelineType(StringUtils.hasText(source.getPipelineType())
                ? source.getPipelineType()
                : PipelineTypes.CICD);
        clone.setDescription(source.getDescription());
        clone.setStepsConfig(source.getStepsConfig());
        clone.setParameterDefinitions(source.getParameterDefinitions());
        clone.setBuildNodeIds(source.getBuildNodeIds());
        clone.setDeployNodeId(source.getDeployNodeId());
        clone.setStatus(source.getStatus() != null ? source.getStatus() : 1);
        clone.setCreateTime(LocalDateTime.now());
        clone.setUpdateTime(LocalDateTime.now());

        pipelineMapper.insert(clone);
        return getPipelineDTO(clone);
    }

    private String buildCloneName(String sourceName) {
        String base = StringUtils.hasText(sourceName) ? sourceName.trim() : "未命名模版";
        String candidate = base + " 副本";
        int suffix = 2;
        while (existsPipelineName(candidate)) {
            candidate = base + " 副本" + suffix;
            suffix++;
        }
        return candidate;
    }

    private boolean existsPipelineName(String name) {
        QueryWrapper<Pipeline> wrapper = new QueryWrapper<>();
        wrapper.eq("name", name);
        return pipelineMapper.selectCount(wrapper) > 0;
    }

    /**
     * 实体转 DTO，解析 stepsConfig / parameterDefinitions JSON 并补全节点名称。
     *
     * @param pipeline 数据库实体
     * @return 前端展示用 DTO
     */
    private PipelineDTO getPipelineDTO(Pipeline pipeline) {
        PipelineDTO dto = new PipelineDTO();
        BeanUtils.copyProperties(pipeline, dto, "stepsConfig", "parameterDefinitions");
        if (!StringUtils.hasText(dto.getPipelineType())) {
            dto.setPipelineType(PipelineTypes.CICD);
        }
        
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

        List<Long> buildNodeIds = parseBuildNodeIds(pipeline.getBuildNodeIds());
        dto.setBuildNodeIds(buildNodeIds);
        if (!buildNodeIds.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Long nodeId : buildNodeIds) {
                if (nodeId == null) {
                    continue;
                }
                BuildNode buildNode = buildNodeMapper.selectById(nodeId);
                if (buildNode != null && StringUtils.hasText(buildNode.getName())) {
                    names.add(buildNode.getName());
                }
            }
            if (!names.isEmpty()) {
                // 列表展示全部候选 CI 节点（多选时不再只显示首项）
                dto.setBuildNodeName(String.join(", ", names));
            }
        }
        if (pipeline.getDeployNodeId() != null) {
            BuildNode deployNode = buildNodeMapper.selectById(pipeline.getDeployNodeId());
            if (deployNode != null) {
                dto.setDeployNodeName(deployNode.getName());
            }
        }
        
        return dto;
    }

    /**
     * 校验并规范化流水线类型。
     *
     * @param rawType 原始类型字符串（支持 ci/cd、cicd、ci/cd 等别名）
     * @return 规范化后的类型：ci / cd / cicd
     * @throws BusinessException 类型无效时
     */
    /**
     * 每个步骤都要选步骤定义（页面上的必填项）。直接写 stepType 的内联步骤是接口的老用法，仍然允许；
     * 两者都没有的空步骤拒绝。
     */
    static void validateSteps(List<PipelineStepDTO> steps) {
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            PipelineStepDTO step = steps.get(i);
            if (step == null || (step.getStepTemplateId() == null && !StringUtils.hasText(step.getStepType()))) {
                throw new BusinessException("第 " + (i + 1) + " 个步骤未选择步骤定义");
            }
        }
    }

    private String validatePipelineType(String rawType) {
        String type = PipelineTypes.normalize(rawType);
        if (!PipelineTypes.isValid(type)) {
            throw new BusinessException("流水线类型无效，请选择 CI / CD / CI/CD");
        }
        return type;
    }

    /**
     * 将请求中的构建节点选择写入实体。
     *
     * @param pipeline 待写入的实体
     * @param request 请求 DTO
     */
    private void applyBuildNodeSelections(Pipeline pipeline, PipelineDTO request) {
        List<Long> buildNodeIds = new ArrayList<>();
        if (request.getBuildNodeIds() != null) {
            request.getBuildNodeIds().stream().filter(Objects::nonNull).distinct().forEach(buildNodeIds::add);
        }
        pipeline.setBuildNodeIds(buildNodeIds.isEmpty()
            ? null
            : buildNodeIds.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    /**
     * 解析实体中逗号分隔的构建节点 ID 字符串。
     *
     * @param buildNodeIds 逗号分隔的 ID 字符串
     * @return 解析后的 ID 列表
     */
    private List<Long> parseBuildNodeIds(String buildNodeIds) {
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
        return ids;
    }
}

