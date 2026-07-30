package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.ApprovalActionRequest;
import com.opsflow.api.dto.ApprovalFlowDTO;
import com.opsflow.api.dto.ApprovalRecordDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.ApprovalFlowMapper;
import com.opsflow.dao.mapper.ApprovalRecordMapper;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.ApprovalFlow;
import com.opsflow.dao.model.ApprovalRecord;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalNotifyDispatchService;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.FeishuUserResolveService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 上线任务审批服务实现。
 * <p>
 * 上游：{@link com.opsflow.web.controller.DeployTaskController} 创建任务后调用 {@link #startApproval}；
 * 下游：审批全部通过（或无需审批）后，事务提交后调用 {@link DeployTaskCdService#startCdAfterApproved}
 * 触发 CD 部署，并通过 {@link ApprovalNotifyDispatchService} 发送站内信/飞书通知。
 * </p>
 */
@Slf4j
@Service
public class ApprovalServiceImpl implements ApprovalService {

    /** 审批步骤状态：等待前序步骤完成 */
    public static final String STATUS_WAITING = "waiting";
    /** 审批步骤/任务状态：当前待审批 */
    public static final String STATUS_PENDING = "pending";
    /** 审批步骤/任务状态：已通过 */
    public static final String STATUS_APPROVED = "approved";
    /** 审批步骤/任务状态：已驳回 */
    public static final String STATUS_REJECTED = "rejected";
    /** 审批步骤状态：因驳回而被取消 */
    public static final String STATUS_CANCELLED = "cancelled";
    /** 任务审批状态：无需审批（未关联审批流） */
    public static final String STATUS_NONE = "none";

    @Autowired
    private ApprovalFlowMapper approvalFlowMapper;

    @Autowired
    private ApprovalRecordMapper approvalRecordMapper;

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    private ApprovalNotifyDispatchService approvalNotifyDispatchService;

    @Autowired
    private FeishuUserResolveService feishuUserResolveService;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 为新建上线任务初始化审批流程，并在「无需人工审批」时直接触发 CD。
     * <p>
     * <b>业务目的</b>：创建任务后统一入口，根据是否关联审批流决定走人工审批还是免审直发 CD。
     * 每次调用会先删除该任务下旧审批记录，支持重新发起（当前仅 createTask 调用一次）。
     * </p>
     * <p>
     * <b>三条路径</b>：
     * <ol>
     *   <li><b>无审批流</b>（approvalFlowId == null）：approvalStatus = {@code none}，
     *       表示业务上未配置审批，视同免审；立即 {@link #triggerCdAfterApproved}。</li>
     *   <li><b>有流但步骤为空</b>：approvalStatus = {@code approved}，表示流存在但无人工节点；
     *       同样立即触发 CD（与 none 区别仅在于状态枚举，便于审计区分）。</li>
     *   <li><b>有流且有步骤</b>：按 step 序号排序，为每步 insert ApprovalRecord；
     *       首步 {@code pending}，其余 {@code waiting}；任务 approvalStatus = {@code pending}；
     *       <b>不</b>在此触发 CD，等 {@link #approve} 终审通过后再启动。</li>
     * </ol>
     * </p>
     * <p>
     * <b>何时触发 CD</b>：仅路径 1、2 在方法内调用 {@link #triggerCdAfterApproved}；
     * 路径 3 通过 afterCommit 通知首位审批人（{@code notifyPending}），CD 留给终审后的 {@link #approve}。
     * </p>
     * <p>
     * <b>前置条件</b>：task 已 insert 且 id 非空；若指定了 approvalFlowId，对应流须存在且未禁用。
     * 步骤 JSON 非法时抛 {@link BusinessException}。
     * </p>
     * <p>
     * <b>上下游</b>：上游 {@link com.opsflow.web.controller.DeployTaskController#createTask}；
     * 下游免审路径 → {@link DeployTaskCdService#startCdAfterApproved}；
     * 有步骤路径 → {@link ApprovalNotifyDispatchService#notifyPending}。
     * </p>
     *
     * @param task 已持久化的上线任务
     * @throws BusinessException 任务无效、审批流不存在或已禁用、步骤配置无效
     */
    @Override
    @Transactional
    public void startApproval(DeployTask task) {
        if (task == null || task.getId() == null) {
            throw new BusinessException("任务无效");
        }
        approvalRecordMapper.delete(new QueryWrapper<ApprovalRecord>().eq("task_id", task.getId()));

        if (task.getApprovalFlowId() == null) {
            task.setApprovalStatus(STATUS_NONE);
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
            triggerCdAfterApproved(task);
            return;
        }

        ApprovalFlow flow = approvalFlowMapper.selectById(task.getApprovalFlowId());
        if (flow == null) {
            throw new BusinessException("审批流不存在");
        }
        if (flow.getStatus() != null && flow.getStatus() == 0) {
            throw new BusinessException("审批流已禁用");
        }

        List<ApprovalFlowDTO.ApprovalStep> steps = parseSteps(flow.getSteps());
        if (steps.isEmpty()) {
            task.setApprovalStatus(STATUS_APPROVED);
            task.setUpdateTime(LocalDateTime.now());
            deployTaskMapper.updateById(task);
            triggerCdAfterApproved(task);
            return;
        }

        steps.sort(Comparator.comparing(s -> s.getStep() != null ? s.getStep() : Integer.MAX_VALUE));
        LocalDateTime now = LocalDateTime.now();
        ApprovalRecord firstPending = null;
        for (int i = 0; i < steps.size(); i++) {
            ApprovalFlowDTO.ApprovalStep stepDef = steps.get(i);
            ApprovalRecord record = new ApprovalRecord();
            record.setTaskId(task.getId());
            record.setTaskNumber(task.getTaskNumber());
            record.setApprovalFlowId(flow.getId());
            record.setCurrentStep(stepDef.getStep() != null ? stepDef.getStep() : (i + 1));
            record.setApprover(trimToNull(stepDef.getApprover()));
            record.setApproverName(trimToNull(stepDef.getApproverName()));
            if (StringUtils.hasText(record.getApprover())) {
                record.setApproverFeishuId(feishuUserResolveService.resolveFeishuUserId(record.getApprover()));
            }
            record.setStatus(i == 0 ? STATUS_PENDING : STATUS_WAITING);
            record.setCreateTime(now);
            record.setUpdateTime(now);
            approvalRecordMapper.insert(record);
            if (i == 0) {
                firstPending = record;
            }
        }

        task.setApprovalStatus(STATUS_PENDING);
        task.setUpdateTime(now);
        deployTaskMapper.updateById(task);

        final ApprovalRecord notifyRecord = firstPending;
        final DeployTask notifyTask = task;
        runAfterCommit(() -> approvalNotifyDispatchService.notifyPending(notifyRecord, notifyTask));
    }

    /**
     * 查询指定任务的全部审批记录。
     *
     * @param taskId 上线任务 ID
     * @param currentUsername 当前用户，用于标记是否可审批
     * @return 按步骤升序排列的审批记录列表
     */
    @Override
    public List<ApprovalRecordDTO> listByTask(Long taskId, String currentUsername) {
        List<ApprovalRecord> records = approvalRecordMapper.selectList(
                new QueryWrapper<ApprovalRecord>().eq("task_id", taskId).orderByAsc("current_step"));
        return records.stream().map(r -> toDto(r, currentUsername, null)).collect(Collectors.toList());
    }

    /**
     * 查询当前用户待处理的审批记录（含任务摘要信息）。
     *
     * @param currentUsername 当前登录用户名（支持 {@code feishu:xxx} 形式）
     * @return 待审批记录列表，按创建时间倒序
     */
    @Override
    public List<ApprovalRecordDTO> listMyPending(String currentUsername) {
        if (currentUsername == null || currentUsername.trim().isEmpty()) {
            return new ArrayList<>();
        }
        QueryWrapper<ApprovalRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("status", STATUS_PENDING);
        wrapper.and(w -> w.eq("approver", currentUsername.trim()).or().isNull("approver").or().eq("approver", ""));
        wrapper.orderByDesc("create_time");
        List<ApprovalRecord> records = approvalRecordMapper.selectList(wrapper);
        return records.stream().map(r -> {
            DeployTask task = deployTaskMapper.selectById(r.getTaskId());
            return toDto(r, currentUsername, task);
        }).collect(Collectors.toList());
    }

    /**
     * 通过当前审批步骤：推进到下一步，或在终审通过时触发 CD。
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>{@link #loadActionableRecord} 校验记录存在、状态为 pending、当前用户有权审批</li>
     *   <li>将当前记录标为 {@code approved}，写入意见与时间</li>
     *   <li>查找同任务下第一条 {@code waiting} 记录作为 next</li>
     *   <li>若 next 存在：激活为 {@code pending}，补全飞书 ID，任务保持 approvalStatus=pending</li>
     *   <li>若 next 不存在：任务 approvalStatus=approved，表示终审通过</li>
     *   <li>afterCommit 中按固定顺序执行通知与 CD（见下）</li>
     * </ol>
     * </p>
     * <p>
     * <b>推进下一步 vs 终审</b>：有 waiting 步骤时只「放行」当前步并唤醒下一步，不启动 CD；
     * 无 waiting 时表示所有步骤已通过，此时才应部署。
     * </p>
     * <p>
     * <b>afterCommit 通知与 CD 顺序</b>（顺序有意为之）：
     * <ol>
     *   <li>{@code notifyApproved} — 告知本步已通过</li>
     *   <li>若有下一步 → {@code notifyPending} — 通知下一位审批人</li>
     *   <li>仅终审（next == null）→ {@link DeployTaskCdService#startCdAfterApproved}</li>
     * </ol>
     * 通知与 CD 均放在 commit 之后，避免审批事务回滚时已发出的通知或已启动的流水线无法撤回；
     * CD 排在通知之后，确保审批人先收到「已通过」再看到部署开始（终审场景）。
     * </p>
     * <p>
     * <b>边界</b>：关联任务不存在时抛异常；CD/通知失败由 runAfterCommit 捕获日志，不影响审批事务已提交的事实。
     * </p>
     *
     * @param recordId 待通过的审批记录 ID
     * @param request 审批意见（可选）
     * @param currentUsername 操作人登录名
     * @return 更新后的审批记录 DTO
     * @throws BusinessException 记录不可操作或任务不存在
     */
    @Override
    @Transactional
    public ApprovalRecordDTO approve(Long recordId, ApprovalActionRequest request, String currentUsername) {
        ApprovalRecord record = loadActionableRecord(recordId, currentUsername);
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(STATUS_APPROVED);
        record.setComment(request != null ? trimToNull(request.getComment()) : null);
        record.setApproveTime(now);
        record.setUpdateTime(now);
        approvalRecordMapper.updateById(record);

        List<ApprovalRecord> all = approvalRecordMapper.selectList(
                new QueryWrapper<ApprovalRecord>().eq("task_id", record.getTaskId()).orderByAsc("current_step"));
        ApprovalRecord next = null;
        for (ApprovalRecord item : all) {
            if (STATUS_WAITING.equals(item.getStatus())) {
                next = item;
                break;
            }
        }
        DeployTask task = deployTaskMapper.selectById(record.getTaskId());
        if (task == null) {
            throw new BusinessException("关联任务不存在");
        }
        if (next != null) {
            if (StringUtils.hasText(next.getApprover()) && !StringUtils.hasText(next.getApproverFeishuId())) {
                next.setApproverFeishuId(feishuUserResolveService.resolveFeishuUserId(next.getApprover()));
            }
            next.setStatus(STATUS_PENDING);
            next.setUpdateTime(now);
            approvalRecordMapper.updateById(next);
            task.setApprovalStatus(STATUS_PENDING);
        } else {
            task.setApprovalStatus(STATUS_APPROVED);
        }
        task.setUpdateTime(now);
        deployTaskMapper.updateById(task);

        final ApprovalRecord done = record;
        final ApprovalRecord nextPending = next;
        final DeployTask notifyTask = task;
        final String operator = currentUsername;
        final boolean shouldStartCd = next == null;
        runAfterCommit(() -> {
            approvalNotifyDispatchService.notifyApproved(done, notifyTask, operator);
            if (nextPending != null) {
                approvalNotifyDispatchService.notifyPending(nextPending, notifyTask);
            }
            if (shouldStartCd) {
                // CD 必须异步脱离当前 afterCommit：否则 launchJob 再注册 afterCommit 会丢失，Job 停在 BUILDING
                scheduleCdAsync(notifyTask.getId(), "审批终审通过");
            }
        });
        return toDto(record, currentUsername, task);
    }

    /**
     * 驳回当前审批步骤，并取消同任务下所有未完成的后续步骤。
     * <p>
     * <b>业务目的</b>：任一环节驳回即终止整条上线，不允许「跳过驳回步继续审」或「驳回后仍触发 CD」。
     * </p>
     * <p>
     * <b>主流程</b>：
     * <ol>
     *   <li>{@link #loadActionableRecord} 校验权限与 pending 状态</li>
     *   <li>当前记录标为 {@code rejected}</li>
     *   <li>同任务下其余 {@code waiting} / {@code pending} 记录（不含当前条）一律标为 {@code cancelled}</li>
     *   <li>任务 approvalStatus = {@code rejected}</li>
     *   <li>afterCommit 发送 {@code notifyRejected}，<b>不</b>调用 CD</li>
     * </ol>
     * </p>
     * <p>
     * <b>取消后续步骤</b>：waiting 表示尚未轮到的步骤，pending 理论上仅当前步一条；
     * 一并 cancelled 可防止并发或数据异常下出现多条 pending。已 approved 的历史步骤保留，便于审计。
     * </p>
     * <p>
     * <b>边界</b>：关联任务不存在时抛异常；驳回后任务停留在 rejected，需用户新建任务重新上线。
     * </p>
     *
     * @param recordId 待驳回的审批记录 ID
     * @param request 驳回意见（可选）
     * @param currentUsername 操作人登录名
     * @return 更新后的审批记录 DTO
     * @throws BusinessException 记录不可操作或任务不存在
     */
    @Override
    @Transactional
    public ApprovalRecordDTO reject(Long recordId, ApprovalActionRequest request, String currentUsername) {
        ApprovalRecord record = loadActionableRecord(recordId, currentUsername);
        LocalDateTime now = LocalDateTime.now();
        record.setStatus(STATUS_REJECTED);
        record.setComment(request != null ? trimToNull(request.getComment()) : null);
        record.setApproveTime(now);
        record.setUpdateTime(now);
        approvalRecordMapper.updateById(record);

        List<ApprovalRecord> rest = approvalRecordMapper.selectList(
                new QueryWrapper<ApprovalRecord>()
                        .eq("task_id", record.getTaskId())
                        .in("status", STATUS_WAITING, STATUS_PENDING)
                        .ne("id", record.getId()));
        for (ApprovalRecord item : rest) {
            item.setStatus(STATUS_CANCELLED);
            item.setUpdateTime(now);
            approvalRecordMapper.updateById(item);
        }

        DeployTask task = deployTaskMapper.selectById(record.getTaskId());
        if (task == null) {
            throw new BusinessException("关联任务不存在");
        }
        task.setApprovalStatus(STATUS_REJECTED);
        task.setUpdateTime(now);
        deployTaskMapper.updateById(task);

        final ApprovalRecord done = record;
        final DeployTask notifyTask = task;
        final String operator = currentUsername;
        runAfterCommit(() -> approvalNotifyDispatchService.notifyRejected(done, notifyTask, operator));
        return toDto(record, currentUsername, task);
    }

    /**
     * 事务提交后执行回调，通知或 CD 启动失败不影响主事务。
     *
     * @param action 提交后执行的逻辑
     */
    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        // 通知失败不影响主流程
                        org.slf4j.LoggerFactory.getLogger(ApprovalServiceImpl.class)
                                .error("审批后飞书通知失败", e);
                    }
                }
            });
        } else {
            try {
                action.run();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(ApprovalServiceImpl.class)
                        .error("审批飞书通知失败", e);
            }
        }
    }

    /**
     * 审批已达通过态时，事务提交后异步启动 CD。
     *
     * @param task 上线任务
     */
    private void triggerCdAfterApproved(DeployTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        final Long taskId = task.getId();
        runAfterCommit(() -> scheduleCdAsync(taskId, "免审上线任务"));
    }

    /**
     * 在独立线程中调度 CD，避免嵌套在审批事务 afterCommit 内导致执行派发丢失。
     */
    private void scheduleCdAsync(Long taskId, String reason) {
        log.info("{}，异步调度上线任务 CD: taskId={}", reason, taskId);
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                deployTaskCdService.scheduleApprovedTask(taskId);
            } catch (Exception e) {
                log.error("审批通过后启动 CD 失败, taskId={}", taskId, e);
            }
        });
    }

    /**
     * 加载当前用户可操作的待审批记录，作为 approve/reject 的统一门禁。
     * <p>
     * <b>校验链</b>（任一不满足即抛 {@link BusinessException}，不修改数据）：
     * <ol>
     *   <li>记录存在</li>
     *   <li>status 必须为 {@code pending}（waiting 须等前序通过后才可操作；approved/rejected/cancelled 不可重复操作）</li>
     *   <li>{@link #canApprove} 返回 true</li>
     * </ol>
     * </p>
     * <p>
     * <b>设计意图</b>：将「记录状态」与「操作人身份」校验集中在一处，避免 approve/reject 各写一套导致权限漏洞。
     * </p>
     *
     * @param recordId 审批记录 ID
     * @param currentUsername 当前登录用户
     * @return 可操作的 pending 记录
     * @throws BusinessException 记录不存在、非待审状态或无权审批
     */
    private ApprovalRecord loadActionableRecord(Long recordId, String currentUsername) {
        ApprovalRecord record = approvalRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException("审批记录不存在");
        }
        if (!STATUS_PENDING.equals(record.getStatus())) {
            throw new BusinessException("当前步骤不是待审批状态");
        }
        if (!canApprove(record, currentUsername)) {
            throw new BusinessException("您不是当前步骤的审批人");
        }
        return record;
    }

    /**
     * 判断当前用户是否为该审批步骤的合法审批人。
     * <p>
     * <b>规则</b>：
     * <ul>
     *   <li>currentUsername 为空 → 拒绝（未登录）</li>
     *   <li>record.approver 为空 → 允许任意已登录用户操作（开放审批，适用于未指定具体人的步骤）</li>
     *   <li>currentUsername 以 {@code feishu:} 开头 → 与 record.approverFeishuId 忽略大小写匹配
     *       （飞书 SSO 未绑定本地账号时的身份形态）</li>
     *   <li>否则 → 与 approver 字段忽略大小写匹配（本地用户名）</li>
     * </ul>
     * </p>
     * <p>
     * <b>用途</b>：{@link #loadActionableRecord} 写操作校验；
     * {@link #toDto} 计算 actionable 标志供前端展示「通过/驳回」按钮。
     * </p>
     *
     * @param record 审批记录（含 approver、approverFeishuId）
     * @param currentUsername 当前会话用户标识
     * @return 是否有权对该 pending 步骤执行 approve/reject
     */
    private boolean canApprove(ApprovalRecord record, String currentUsername) {
        if (currentUsername == null || currentUsername.trim().isEmpty()) {
            return false;
        }
        String approver = record.getApprover();
        if (approver == null || approver.trim().isEmpty()) {
            return true;
        }
        // 飞书未绑定用户场景：feishu:xxx
        if (currentUsername.startsWith("feishu:")) {
            String feishuId = currentUsername.substring("feishu:".length());
            return StringUtils.hasText(record.getApproverFeishuId())
                    && record.getApproverFeishuId().equalsIgnoreCase(feishuId);
        }
        return currentUsername.trim().equalsIgnoreCase(approver.trim());
    }

    /**
     * 解析审批流步骤 JSON 配置。
     *
     * @param stepsJson 步骤定义 JSON 数组
     * @return 步骤列表；空或无效时抛业务异常
     */
    private List<ApprovalFlowDTO.ApprovalStep> parseSteps(String stepsJson) {
        if (stepsJson == null || stepsJson.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<ApprovalFlowDTO.ApprovalStep> steps = objectMapper.readValue(
                    stepsJson, new TypeReference<List<ApprovalFlowDTO.ApprovalStep>>() {});
            return steps != null ? steps : new ArrayList<>();
        } catch (Exception e) {
            throw new BusinessException("审批步骤配置无效");
        }
    }

    /**
     * 审批记录实体转 DTO，附带任务名与当前用户是否可操作。
     *
     * @param record 审批记录
     * @param currentUsername 当前用户
     * @param task 关联任务（可为 null）
     * @return 前端展示用 DTO
     */
    private ApprovalRecordDTO toDto(ApprovalRecord record, String currentUsername, DeployTask task) {
        ApprovalRecordDTO dto = new ApprovalRecordDTO();
        BeanUtils.copyProperties(record, dto);
        dto.setActionable(STATUS_PENDING.equals(record.getStatus()) && canApprove(record, currentUsername));
        if (task != null) {
            dto.setTaskName(task.getTaskName());
            if (dto.getTaskNumber() == null) {
                dto.setTaskNumber(task.getTaskNumber());
            }
        }
        return dto;
    }

    /**
     * 去除首尾空白，空字符串转为 {@code null}。
     *
     * @param value 原始字符串
     * @return 去空白后的值或 null
     */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
