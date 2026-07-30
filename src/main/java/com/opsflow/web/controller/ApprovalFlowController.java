package com.opsflow.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.ApprovalActionRequest;
import com.opsflow.api.dto.ApprovalFlowDTO;
import com.opsflow.api.dto.ApprovalRecordDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalService;
import com.opsflow.license.LicenseFeatures;
import com.opsflow.service.LicenseService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.opsflow.web.security.RequiresPermission;

/**
 * 审批流管理与审批动作
 */
@RestController
@RequestMapping("/api/approval")
public class ApprovalFlowController {

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private LicenseService licenseService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private void requireDeployApprovalLicense() {
        licenseService.requireFeature(LicenseFeatures.DEPLOY_APPROVAL);
    }

    /**
     * 创建审批流，步骤列表序列化为 JSON 存入 steps 字段。
     *
     * @param request 名称、描述、状态及审批步骤
     */
    @RequiresPermission("approval:manage")
    @PostMapping("/create")
    public ApprovalFlowDTO createFlow(@RequestBody ApprovalFlowDTO request) {
        requireDeployApprovalLicense();
        validateFlow(request);
        ApprovalFlow flow = new ApprovalFlow();
        BeanUtils.copyProperties(request, flow, "steps");
        flow.setSteps(serializeSteps(normalizeSteps(request.getSteps())));
        flow.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        flow.setCreateTime(LocalDateTime.now());
        flow.setUpdateTime(LocalDateTime.now());
        approvalFlowMapper.insert(flow);
        return getFlowDTO(flow);
    }

    /**
     * 审批流列表，可按状态筛选。
     *
     * @param status 1 启用 / 0 禁用，null 表示全部
     */
    @RequiresPermission({"approval:view", "approval:manage", "deploy:create"})
    @GetMapping("/list")
    public List<ApprovalFlowDTO> listFlows(@RequestParam(required = false) Integer status) {
        requireDeployApprovalLicense();
        QueryWrapper<ApprovalFlow> wrapper = new QueryWrapper<>();
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("create_time");
        return approvalFlowMapper.selectList(wrapper).stream()
                .map(this::getFlowDTO)
                .collect(Collectors.toList());
    }

    /** 按 ID 获取审批流详情，不存在时返回 null */
    @RequiresPermission({"approval:view", "approval:manage"})
    @GetMapping("/{id}")
    public ApprovalFlowDTO getFlow(@PathVariable Long id) {
        requireDeployApprovalLicense();
        ApprovalFlow flow = approvalFlowMapper.selectById(id);
        if (flow == null) {
            return null;
        }
        return getFlowDTO(flow);
    }

    /**
     * 更新审批流配置（不修改 id、createTime）。
     *
     * @param id      审批流 ID
     * @param request 更新后的名称、步骤等
     */
    @RequiresPermission("approval:manage")
    @PutMapping("/{id}")
    public ApprovalFlowDTO updateFlow(@PathVariable Long id, @RequestBody ApprovalFlowDTO request) {
        requireDeployApprovalLicense();
        ApprovalFlow flow = approvalFlowMapper.selectById(id);
        if (flow == null) {
            throw new BusinessException("审批流不存在");
        }
        validateFlow(request);
        BeanUtils.copyProperties(request, flow, "id", "createTime", "steps");
        flow.setSteps(serializeSteps(normalizeSteps(request.getSteps())));
        if (request.getStatus() != null) {
            flow.setStatus(request.getStatus());
        }
        flow.setUpdateTime(LocalDateTime.now());
        approvalFlowMapper.updateById(flow);
        return getFlowDTO(flow);
    }

    /**
     * 删除审批流；若已被上线任务引用则拒绝删除。
     *
     * @param id 审批流 ID
     */
    @RequiresPermission("approval:manage")
    @DeleteMapping("/{id}")
    public boolean deleteFlow(@PathVariable Long id) {
        requireDeployApprovalLicense();
        Long inUse = deployTaskMapper.selectCount(new QueryWrapper<DeployTask>().eq("approval_flow_id", id));
        if (inUse != null && inUse > 0) {
            throw new BusinessException("该审批流已被上线任务引用，无法删除");
        }
        return approvalFlowMapper.deleteById(id) > 0;
    }

    /** 当前登录用户的待审批记录列表 */
    @RequiresPermission({"deploy:view", "deploy:approve"})
    @GetMapping("/records/pending")
    public List<ApprovalRecordDTO> myPending(HttpSession session) {
        requireDeployApprovalLicense();
        return approvalService.listMyPending(currentUser(session));
    }

    /**
     * 指定上线任务的全部审批记录。
     *
     * @param taskId 上线任务 ID
     */
    @RequiresPermission("deploy:view")
    @GetMapping("/records/task/{taskId}")
    public List<ApprovalRecordDTO> listByTask(@PathVariable Long taskId, HttpSession session) {
        requireDeployApprovalLicense();
        return approvalService.listByTask(taskId, currentUser(session));
    }

    /**
     * 通过审批记录。
     *
     * @param id      审批记录 ID
     * @param request 审批意见，可为 null
     */
    @RequiresPermission("deploy:approve")
    @PostMapping("/records/{id}/approve")
    public ApprovalRecordDTO approve(@PathVariable Long id,
                                     @RequestBody(required = false) ApprovalActionRequest request,
                                     HttpSession session) {
        requireDeployApprovalLicense();
        return approvalService.approve(id, request, currentUser(session));
    }

    /**
     * 拒绝审批记录。
     *
     * @param id      审批记录 ID
     * @param request 拒绝原因，可为 null
     */
    @RequiresPermission("deploy:approve")
    @PostMapping("/records/{id}/reject")
    public ApprovalRecordDTO reject(@PathVariable Long id,
                                    @RequestBody(required = false) ApprovalActionRequest request,
                                    HttpSession session) {
        requireDeployApprovalLicense();
        return approvalService.reject(id, request, currentUser(session));
    }

    /** 校验名称非空且至少包含一个审批步骤 */
    private void validateFlow(ApprovalFlowDTO request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BusinessException("审批流名称不能为空");
        }
        if (request.getSteps() == null || request.getSteps().isEmpty()) {
            throw new BusinessException("请至少配置一个审批步骤");
        }
    }

    /** 规范化步骤序号与必审标志，过滤 null 步骤 */
    private List<ApprovalFlowDTO.ApprovalStep> normalizeSteps(List<ApprovalFlowDTO.ApprovalStep> steps) {
        List<ApprovalFlowDTO.ApprovalStep> normalized = new ArrayList<>();
        if (steps == null) {
            return normalized;
        }
        int index = 1;
        for (ApprovalFlowDTO.ApprovalStep step : steps) {
            if (step == null) {
                continue;
            }
            ApprovalFlowDTO.ApprovalStep copy = new ApprovalFlowDTO.ApprovalStep();
            copy.setStep(step.getStep() != null ? step.getStep() : index);
            copy.setApprover(step.getApprover());
            copy.setApproverName(step.getApproverName());
            copy.setRequired(step.getRequired() == null || step.getRequired());
            normalized.add(copy);
            index++;
        }
        return normalized;
    }

    /** 将步骤列表序列化为 JSON 字符串 */
    private String serializeSteps(List<ApprovalFlowDTO.ApprovalStep> steps) {
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception e) {
            throw new BusinessException("审批步骤格式错误");
        }
    }

    /** 实体转 DTO，反序列化 steps JSON；解析失败时返回空步骤列表 */
    private ApprovalFlowDTO getFlowDTO(ApprovalFlow flow) {
        ApprovalFlowDTO dto = new ApprovalFlowDTO();
        BeanUtils.copyProperties(flow, dto, "steps");
        try {
            if (flow.getSteps() != null && !flow.getSteps().isEmpty()) {
                List<ApprovalFlowDTO.ApprovalStep> steps = objectMapper.readValue(
                        flow.getSteps(),
                        new TypeReference<List<ApprovalFlowDTO.ApprovalStep>>() {}
                );
                dto.setSteps(steps);
            }
        } catch (Exception ignored) {
            dto.setSteps(new ArrayList<>());
        }
        return dto;
    }

    /** 从 HttpSession 读取当前登录用户名 */
    private String currentUser(HttpSession session) {
        Object user = session.getAttribute("user");
        return user != null ? String.valueOf(user) : null;
    }
}
