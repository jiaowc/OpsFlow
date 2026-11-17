package com.opsflow.web.controller;

import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.service.DeployImageVerifyService;
import com.opsflow.service.DeployTaskOperator;
import com.opsflow.service.DeployTaskService;
import com.opsflow.service.PermissionService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpSession;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 上线任务 REST 接口。
 * <p>
 * 上游：前端任务页提交创建/查询请求；下游：{@link com.opsflow.service.ApprovalService} 发起审批，
 * 审批全部通过后由 {@link com.opsflow.service.DeployTaskCdService} 按关联的 CD 流水线模版触发部署。
 * </p>
 * <p>
 * 本类只负责路由、权限声明和把登录会话转换为 {@link DeployTaskOperator}，
 * 业务规则全部在 {@link DeployTaskService}（实现见 {@code com.opsflow.service.impl.deploy} 包）。
 * </p>
 */
@RestController
@RequestMapping("/api/task")
public class DeployTaskController {

    @Autowired
    private DeployTaskService deployTaskService;

    @Autowired
    private DeployImageVerifyService deployImageVerifyService;

    @Autowired
    private PermissionService permissionService;

    /**
     * 创建上线任务：完成业务校验、模块镜像规范化并落库，进入协作编辑态（未锁定、未审批）。
     * 校验链见 {@code DeployTaskCommandService#create}。
     */
    @RequiresPermission("deploy:create")
    @PostMapping("/create")
    public DeployTaskDTO createTask(@RequestBody DeployTaskDTO request, HttpSession session) {
        return deployTaskService.createTask(request, operator(session));
    }

    /**
     * 查询上线任务列表。
     *
     * @param status 可选，按任务状态过滤：pending / deploying / success / failed 等
     * @param projectId 可选，指定项目
     * @return 任务列表，按创建时间倒序
     */
    @RequiresPermission("deploy:view")
    @GetMapping("/list")
    public List<DeployTaskDTO> listTasks(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) Long projectId,
                                         HttpSession session) {
        return deployTaskService.listTasks(status, projectId, operator(session));
    }

    /**
     * 某服务在 Harbor 项目下已在非生产环境部署成功的版本（tag → 环境名），供创建上线任务时标注和优先展示。
     */
    @RequiresPermission({"deploy:view", "deploy:create"})
    @GetMapping("/verified-versions")
    public Map<String, String> verifiedVersions(@RequestParam String service, @RequestParam String project) {
        return deployImageVerifyService.verifiedTags(service, project);
    }

    /**
     * 查询单个上线任务详情；不存在时返回 {@code null}。
     */
    @RequiresPermission("deploy:view")
    @GetMapping("/{id}")
    public DeployTaskDTO getTask(@PathVariable Long id, HttpSession session) {
        return deployTaskService.getTask(id, operator(session));
    }

    /**
     * 更新上线任务。未锁定时可协作编辑：每人只能增删改自己的模块；任务元信息仅创建人可改。
     */
    @RequiresPermission("deploy:create")
    @PutMapping("/{id}")
    public DeployTaskDTO updateTask(@PathVariable Long id, @RequestBody DeployTaskDTO request, HttpSession session) {
        return deployTaskService.updateTask(id, request, operator(session));
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/lock")
    public DeployTaskDTO lockTask(@PathVariable Long id, HttpSession session) {
        return deployTaskService.lockTask(id, operator(session));
    }

    @RequiresPermission({"deploy:create", "deploy:unlock"})
    @PostMapping("/{id}/unlock")
    public DeployTaskDTO unlockTask(@PathVariable Long id, HttpSession session) {
        return deployTaskService.unlockTask(id, operator(session));
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/submit-approval")
    public DeployTaskDTO submitApproval(@PathVariable Long id, HttpSession session) {
        return deployTaskService.submitApproval(id, operator(session));
    }

    @RequiresPermission("deploy:create")
    @PostMapping("/{id}/publish")
    public DeployTaskDTO publishTask(@PathVariable Long id, HttpSession session) {
        return deployTaskService.publishTask(id, operator(session));
    }

    /**
     * 删除上线任务及其关联的 CD Job、阶段和审批记录。
     *
     * @param id 任务 ID
     * @return 是否删除成功
     */
    @RequiresPermission("deploy:delete")
    @DeleteMapping("/{id}")
    public boolean deleteTask(@PathVariable Long id) {
        return deployTaskService.deleteTask(id);
    }

    /**
     * 从登录会话构造当前操作人（身份 + 上线任务相关权限）。
     */
    @SuppressWarnings("unchecked")
    private DeployTaskOperator operator(HttpSession session) {
        String username = session != null ? (String) session.getAttribute("user") : null;
        Long userId = session != null ? (Long) session.getAttribute("userId") : null;
        Collection<String> permissions = session != null
                ? (Collection<String>) session.getAttribute("permissions") : null;
        return new DeployTaskOperator(username, userId,
                permissionService.hasAnyPermission(permissions, "deploy:create"),
                permissionService.hasAnyPermission(permissions, "deploy:unlock"),
                permissionService.hasAnyPermission(permissions, "deploy:delete"),
                permissionService.hasAnyPermission(permissions, "deploy:approve"));
    }
}
