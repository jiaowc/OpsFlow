package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.JenkinsNodeMapper;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.model.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上线任务管理控制器
 */
@RestController
@RequestMapping("/api/task")
public class DeployTaskController {

    @Autowired
    private DeployTaskMapper deployTaskMapper;
    
    @Autowired
    @SuppressWarnings("unused")
    private ServiceMapper serviceMapper;
    
    @Autowired
    private EnvMapper envMapper;
    
    @Autowired
    @SuppressWarnings("unused")
    private JenkinsNodeMapper jenkinsNodeMapper;
    
    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建上线任务
     */
    @PostMapping("/create")
    public DeployTaskDTO createTask(@RequestBody DeployTaskDTO request, HttpSession session) {
        String username = (String) session.getAttribute("user");
        
        DeployTask task = new DeployTask();
        BeanUtils.copyProperties(request, task, "deployModules", "deployEnvIds");
        
        // 将上线模块列表转换为JSON字符串
        try {
            if (request.getDeployModules() != null && !request.getDeployModules().isEmpty()) {
                task.setDeployModules(objectMapper.writeValueAsString(request.getDeployModules()));
            }
        } catch (Exception e) {
            throw new RuntimeException("上线模块列表格式错误", e);
        }
        
        // 将部署环境列表转换为JSON字符串
        try {
            if (request.getDeployEnvIds() != null && !request.getDeployEnvIds().isEmpty()) {
                task.setDeployEnvs(objectMapper.writeValueAsString(request.getDeployEnvIds()));
            }
        } catch (Exception e) {
            throw new RuntimeException("部署环境列表格式错误", e);
        }
        
        // 生成任务编号
        String taskNumber = "TASK-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        task.setTaskNumber(taskNumber);
        task.setTaskStatus("pending");
        task.setApprovalStatus("pending");
        task.setCreatorName(username);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        
        deployTaskMapper.insert(task);
        
        return getTaskDTO(task);
    }

    /**
     * 查询任务列表
     */
    @GetMapping("/list")
    public List<DeployTaskDTO> listTasks(@RequestParam(required = false) String status) {
        QueryWrapper<DeployTask> wrapper = new QueryWrapper<>();
        if (status != null && !status.isEmpty()) {
            wrapper.eq("task_status", status);
        }
        wrapper.orderByDesc("create_time");
        
        List<DeployTask> tasks = deployTaskMapper.selectList(wrapper);
        return tasks.stream().map(this::getTaskDTO).collect(Collectors.toList());
    }

    /**
     * 查询任务详情
     */
    @GetMapping("/{id}")
    public DeployTaskDTO getTask(@PathVariable Long id) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        return getTaskDTO(task);
    }

    /**
     * 更新任务
     */
    @PutMapping("/{id}")
    public DeployTaskDTO updateTask(@PathVariable Long id, @RequestBody DeployTaskDTO request) {
        DeployTask task = deployTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, task, "id", "taskNumber", "createTime", "deployModules", "deployEnvIds");
        
        // 更新上线模块列表
        try {
            if (request.getDeployModules() != null) {
                task.setDeployModules(objectMapper.writeValueAsString(request.getDeployModules()));
            }
        } catch (Exception e) {
            throw new RuntimeException("上线模块列表格式错误", e);
        }
        
        // 更新部署环境列表
        try {
            if (request.getDeployEnvIds() != null) {
                task.setDeployEnvs(objectMapper.writeValueAsString(request.getDeployEnvIds()));
            }
        } catch (Exception e) {
            throw new RuntimeException("部署环境列表格式错误", e);
        }
        
        task.setUpdateTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);
        
        return getTaskDTO(task);
    }

    /**
     * 删除任务
     */
    @DeleteMapping("/{id}")
    public boolean deleteTask(@PathVariable Long id) {
        return deployTaskMapper.deleteById(id) > 0;
    }

    private DeployTaskDTO getTaskDTO(DeployTask task) {
        DeployTaskDTO dto = new DeployTaskDTO();
        BeanUtils.copyProperties(task, dto, "deployModules", "deployEnvs");
        
        // 解析上线模块列表
        try {
            if (task.getDeployModules() != null && !task.getDeployModules().isEmpty()) {
                List<String> modules = objectMapper.readValue(
                    task.getDeployModules(),
                    new TypeReference<List<String>>() {}
                );
                dto.setDeployModules(modules);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        // 解析部署环境列表
        try {
            if (task.getDeployEnvs() != null && !task.getDeployEnvs().isEmpty()) {
                List<Long> envIds = objectMapper.readValue(
                    task.getDeployEnvs(),
                    new TypeReference<List<Long>>() {}
                );
                dto.setDeployEnvIds(envIds);
                
                // 填充环境名称列表
                List<String> envNames = new java.util.ArrayList<>();
                for (Long envId : envIds) {
                    Env env = envMapper.selectById(envId);
                    if (env != null) {
                        envNames.add(env.getName());
                    }
                }
                dto.setDeployEnvNames(envNames);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        if (task.getApprovalFlowId() != null) {
            ApprovalFlow flow = approvalFlowMapper.selectById(task.getApprovalFlowId());
            if (flow != null) {
                dto.setApprovalFlowName(flow.getName());
            }
        }
        
        return dto;
    }
}

