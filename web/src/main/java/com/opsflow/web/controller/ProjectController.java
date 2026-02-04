package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ProjectDTO;
import com.opsflow.dao.mapper.ProjectMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.Project;
import com.opsflow.dao.model.Env;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 项目管理控制器
 */
@RestController
@RequestMapping("/api/project")
public class ProjectController {

    @Autowired
    private ProjectMapper projectMapper;
    
    @Autowired
    private EnvMapper envMapper;

    /**
     * 创建项目
     */
    @PostMapping("/create")
    public ProjectDTO createProject(@RequestBody ProjectDTO request) {
        Project project = new Project();
        BeanUtils.copyProperties(request, project, "envIds");
        
        // 将envIds列表转换为逗号分隔的字符串
        if (request.getEnvIds() != null && !request.getEnvIds().isEmpty()) {
            project.setEnvIds(request.getEnvIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        }
        
        project.setStatus(1);
        project.setCreateTime(LocalDateTime.now());
        project.setUpdateTime(LocalDateTime.now());
        
        projectMapper.insert(project);
        
        return getProjectDTO(project);
    }

    /**
     * 查询项目列表
     */
    @GetMapping("/list")
    public List<ProjectDTO> listProjects() {
        List<Project> projects = projectMapper.selectList(new QueryWrapper<>());
        return projects.stream().map(this::getProjectDTO).collect(Collectors.toList());
    }

    /**
     * 查询项目详情
     */
    @GetMapping("/{id}")
    public ProjectDTO getProject(@PathVariable Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            return null;
        }
        return getProjectDTO(project);
    }

    /**
     * 更新项目
     */
    @PutMapping("/{id}")
    public ProjectDTO updateProject(@PathVariable Long id, @RequestBody ProjectDTO request) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, project, "id", "createTime", "envIds");
        
        // 更新envIds
        if (request.getEnvIds() != null) {
            project.setEnvIds(request.getEnvIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        }
        
        project.setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(project);
        
        return getProjectDTO(project);
    }

    /**
     * 删除项目
     */
    @DeleteMapping("/{id}")
    public boolean deleteProject(@PathVariable Long id) {
        return projectMapper.deleteById(id) > 0;
    }

    private ProjectDTO getProjectDTO(Project project) {
        ProjectDTO dto = new ProjectDTO();
        BeanUtils.copyProperties(project, dto, "envIds");
        
        // 解析envIds
        if (project.getEnvIds() != null && !project.getEnvIds().isEmpty()) {
            List<Long> envIds = Arrays.stream(project.getEnvIds().split(","))
                .map(Long::parseLong)
                .collect(Collectors.toList());
            dto.setEnvIds(envIds);
            
            // 填充环境名称
            List<String> envNames = envIds.stream()
                .map(envId -> {
                    Env env = envMapper.selectById(envId);
                    return env != null ? env.getName() : null;
                })
                .filter(name -> name != null)
                .collect(Collectors.toList());
            dto.setEnvNames(envNames);
        }
        
        return dto;
    }
}


