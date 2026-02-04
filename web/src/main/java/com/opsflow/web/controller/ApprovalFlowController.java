package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.ApprovalFlowDTO;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 审批流管理控制器
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalFlowController {

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建审批流
     */
    @PostMapping("/create")
    public ApprovalFlowDTO createFlow(@RequestBody ApprovalFlowDTO request) {
        ApprovalFlow flow = new ApprovalFlow();
        BeanUtils.copyProperties(request, flow, "steps");
        
        // 将steps转换为JSON
        try {
            flow.setSteps(objectMapper.writeValueAsString(request.getSteps()));
        } catch (Exception e) {
            throw new RuntimeException("审批步骤格式错误", e);
        }
        
        flow.setStatus(1);
        flow.setCreateTime(LocalDateTime.now());
        flow.setUpdateTime(LocalDateTime.now());
        
        approvalFlowMapper.insert(flow);
        
        return getFlowDTO(flow);
    }

    /**
     * 查询审批流列表
     */
    @GetMapping("/list")
    public List<ApprovalFlowDTO> listFlows() {
        List<ApprovalFlow> flows = approvalFlowMapper.selectList(new QueryWrapper<>());
        return flows.stream().map(this::getFlowDTO).collect(Collectors.toList());
    }

    /**
     * 查询审批流详情
     */
    @GetMapping("/{id}")
    public ApprovalFlowDTO getFlow(@PathVariable Long id) {
        ApprovalFlow flow = approvalFlowMapper.selectById(id);
        if (flow == null) {
            return null;
        }
        return getFlowDTO(flow);
    }

    /**
     * 更新审批流
     */
    @PutMapping("/{id}")
    public ApprovalFlowDTO updateFlow(@PathVariable Long id, @RequestBody ApprovalFlowDTO request) {
        ApprovalFlow flow = approvalFlowMapper.selectById(id);
        if (flow == null) {
            return null;
        }
        
        BeanUtils.copyProperties(request, flow, "id", "createTime");
        
        // 更新steps
        try {
            flow.setSteps(objectMapper.writeValueAsString(request.getSteps()));
        } catch (Exception e) {
            throw new RuntimeException("审批步骤格式错误", e);
        }
        
        flow.setUpdateTime(LocalDateTime.now());
        approvalFlowMapper.updateById(flow);
        
        return getFlowDTO(flow);
    }

    /**
     * 删除审批流
     */
    @DeleteMapping("/{id}")
    public boolean deleteFlow(@PathVariable Long id) {
        return approvalFlowMapper.deleteById(id) > 0;
    }

    private ApprovalFlowDTO getFlowDTO(ApprovalFlow flow) {
        ApprovalFlowDTO dto = new ApprovalFlowDTO();
        BeanUtils.copyProperties(flow, dto, "steps");
        
        // 解析steps JSON
        try {
            if (flow.getSteps() != null && !flow.getSteps().isEmpty()) {
                List<ApprovalFlowDTO.ApprovalStep> steps = objectMapper.readValue(
                    flow.getSteps(), 
                    new TypeReference<List<ApprovalFlowDTO.ApprovalStep>>() {}
                );
                dto.setSteps(steps);
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        
        return dto;
    }
}


