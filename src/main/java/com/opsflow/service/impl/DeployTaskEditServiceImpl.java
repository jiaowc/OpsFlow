package com.opsflow.service.impl;

import com.opsflow.api.dto.DeployModuleItemDTO;
import com.opsflow.api.dto.DeployTaskDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.common.util.DeployModuleJson;
import com.opsflow.dao.mapper.DeployTaskMapper;
import com.opsflow.dao.model.DeployTask;
import com.opsflow.service.ApprovalService;
import com.opsflow.service.DeployTaskCdService;
import com.opsflow.service.DeployTaskEditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DeployTaskEditServiceImpl implements DeployTaskEditService {

    @Autowired
    private DeployTaskMapper deployTaskMapper;

    @Autowired
    @Lazy
    private ApprovalService approvalService;

    @Autowired
    @Lazy
    private DeployTaskCdService deployTaskCdService;

    @Override
    public boolean isLocked(DeployTask task) {
        return task != null && task.getLocked() != null && task.getLocked() == 1;
    }

    @Override
    public boolean isCreator(DeployTask task, String username, Long userId) {
        if (task == null) {
            return false;
        }
        if (userId != null && task.getCreatorId() != null && userId.equals(task.getCreatorId())) {
            return true;
        }
        return StringUtils.hasText(username)
                && StringUtils.hasText(task.getCreatorName())
                && username.trim().equalsIgnoreCase(task.getCreatorName().trim());
    }

    @Override
    public boolean canUnlock(DeployTask task, String username, Long userId, boolean hasUnlockPerm) {
        return isCreator(task, username, userId) || hasUnlockPerm;
    }

    @Override
    public void assertEditable(DeployTask task) {
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        if (isLocked(task)) {
            throw new BusinessException("任务已锁定，请先解锁后再编辑");
        }
        String approval = norm(task.getApprovalStatus());
        if ("pending".equals(approval) || "approved".equals(approval)) {
            throw new BusinessException("任务已进入审批或发布流程，无法编辑");
        }
        String status = norm(task.getTaskStatus());
        if ("deploying".equals(status) || "building".equals(status)
                || "success".equals(status)) {
            throw new BusinessException("任务已在部署中或已结束，无法编辑");
        }
    }

    @Override
    public List<DeployModuleItemDTO> mergeModulesByOwnership(
            DeployTask task,
            List<DeployModuleItemDTO> requested,
            List<String> requestedImages,
            String username,
            Long userId) {
        List<DeployModuleItemDTO> existing = DeployModuleJson.parseItems(task.getDeployModules());
        for (DeployModuleItemDTO item : existing) {
            if (!StringUtils.hasText(item.getOwnerName())) {
                item.setOwnerName(task.getCreatorName());
                item.setOwnerId(task.getCreatorId());
            }
        }

        List<DeployModuleItemDTO> incoming;
        if (requested != null && !requested.isEmpty()) {
            incoming = requested;
        } else if (requestedImages != null) {
            incoming = DeployModuleJson.fromImages(requestedImages, username, userId);
        } else {
            throw new BusinessException("模块列表不能为空");
        }

        Map<String, DeployModuleItemDTO> existingByImage = new HashMap<>();
        for (DeployModuleItemDTO item : existing) {
            existingByImage.put(normImage(item.getImage()), item);
        }

        Set<String> otherImages = existing.stream()
                .filter(i -> !isOwner(i, username, userId))
                .map(i -> normImage(i.getImage()))
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> incomingImages = new HashSet<>();
        List<DeployModuleItemDTO> result = new ArrayList<>();
        for (DeployModuleItemDTO req : incoming) {
            if (req == null || !StringUtils.hasText(req.getImage())) {
                continue;
            }
            String image = req.getImage().trim();
            String key = normImage(image);
            if (incomingImages.contains(key)) {
                throw new BusinessException("模块重复: " + image);
            }
            incomingImages.add(key);

            DeployModuleItemDTO old = existingByImage.get(key);
            if (old != null) {
                if (!isOwner(old, username, userId)) {
                    DeployModuleItemDTO keep = new DeployModuleItemDTO();
                    keep.setImage(old.getImage());
                    keep.setOwnerName(old.getOwnerName());
                    keep.setOwnerId(old.getOwnerId());
                    result.add(keep);
                } else {
                    DeployModuleItemDTO mine = new DeployModuleItemDTO();
                    mine.setImage(image);
                    mine.setOwnerName(username);
                    mine.setOwnerId(userId);
                    result.add(mine);
                }
            } else {
                DeployModuleItemDTO neu = new DeployModuleItemDTO();
                neu.setImage(image);
                neu.setOwnerName(username);
                neu.setOwnerId(userId);
                result.add(neu);
            }
        }

        for (String other : otherImages) {
            if (!incomingImages.contains(other)) {
                throw new BusinessException("不能删除他人添加的模块: " + other);
            }
        }
        for (DeployModuleItemDTO old : existing) {
            if (isOwner(old, username, userId)) {
                continue;
            }
            String oldKey = normImage(old.getImage());
            if (!incomingImages.contains(oldKey)) {
                throw new BusinessException("不能修改他人添加的模块: " + old.getImage());
            }
        }

        if (result.isEmpty()) {
            throw new BusinessException("请至少保留一个上线模块");
        }
        return result;
    }

    @Override
    @Transactional
    public DeployTask lock(Long taskId, String username, Long userId, boolean hasUnlockPerm) {
        DeployTask task = requireTask(taskId);
        if (!isCreator(task, username, userId) && !hasUnlockPerm) {
            throw new BusinessException("仅创建人或有解锁权限的人可锁定任务");
        }
        if (isLocked(task)) {
            throw new BusinessException("任务已锁定");
        }
        String approval = norm(task.getApprovalStatus());
        if ("pending".equals(approval) || "approved".equals(approval)) {
            throw new BusinessException("任务已进入审批或发布，无需再次锁定");
        }
        String status = norm(task.getTaskStatus());
        if ("deploying".equals(status) || "building".equals(status)
                || "success".equals(status)) {
            throw new BusinessException("任务已在部署中或已结束，无法锁定");
        }
        if (DeployModuleJson.parseImages(task.getDeployModules()).isEmpty()) {
            throw new BusinessException("请先添加上线模块再锁定");
        }
        task.setLocked(1);
        task.setLockedBy(username);
        task.setLockedAt(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);
        return task;
    }

    @Override
    @Transactional
    public DeployTask unlock(Long taskId, String username, Long userId, boolean hasUnlockPerm) {
        DeployTask task = requireTask(taskId);
        if (!canUnlock(task, username, userId, hasUnlockPerm)) {
            throw new BusinessException("无权解锁：仅创建人或拥有 deploy:unlock 权限的人可解除锁定");
        }
        if (!isLocked(task)) {
            throw new BusinessException("任务未锁定");
        }
        String status = norm(task.getTaskStatus());
        if ("deploying".equals(status) || "building".equals(status) || "success".equals(status)) {
            throw new BusinessException("任务已在部署中或已发布成功，无法解锁");
        }
        String approval = norm(task.getApprovalStatus());
        if ("approved".equals(approval)
                && !"pending".equals(status)
                && !"failed".equals(status)
                && !"cancelled".equals(status)) {
            throw new BusinessException("任务已审批通过且正在/已经部署，无法解锁");
        }
        if ("pending".equals(approval) || "rejected".equals(approval)
                || "approved".equals(approval)) {
            approvalService.cancelApprovalForEdit(task);
            task = requireTask(taskId);
        }
        task.setLocked(0);
        task.setLockedBy(null);
        task.setLockedAt(null);
        // 失败任务解锁后回到待执行，便于修改后重新发布
        if ("failed".equals(norm(task.getTaskStatus())) || "cancelled".equals(norm(task.getTaskStatus()))) {
            task.setTaskStatus("pending");
        }
        task.setUpdateTime(LocalDateTime.now());
        deployTaskMapper.updateById(task);
        return task;
    }

    @Override
    @Transactional
    public DeployTask submitApproval(Long taskId, String username, Long userId, boolean hasUnlockPerm) {
        DeployTask task = requireTask(taskId);
        if (!isCreator(task, username, userId) && !hasUnlockPerm) {
            throw new BusinessException("仅创建人或有权限的人可提交审批");
        }
        if (!isLocked(task)) {
            throw new BusinessException("请先锁定任务后再提交审批");
        }
        String approval = norm(task.getApprovalStatus());
        if ("pending".equals(approval)) {
            throw new BusinessException("任务已在审批中");
        }
        if ("approved".equals(approval)) {
            throw new BusinessException("任务已审批通过，请点击「发布」开始部署");
        }
        String status = norm(task.getTaskStatus());
        if ("deploying".equals(status) || "building".equals(status)
                || "success".equals(status)) {
            throw new BusinessException("任务已在部署中或已结束");
        }
        if (task.getApprovalFlowId() == null) {
            throw new BusinessException("请先配置审批流");
        }
        if (DeployModuleJson.parseImages(task.getDeployModules()).isEmpty()) {
            throw new BusinessException("请至少添加一个上线模块");
        }
        approvalService.startApproval(task);
        return requireTask(taskId);
    }

    @Override
    @Transactional
    public DeployTask publish(Long taskId, String username, Long userId, boolean hasUnlockPerm) {
        DeployTask task = requireTask(taskId);
        if (!isCreator(task, username, userId) && !hasUnlockPerm) {
            throw new BusinessException("仅创建人或有权限的人可发布任务");
        }
        if (!isLocked(task)) {
            throw new BusinessException("任务未锁定，无法发布");
        }
        String approval = norm(task.getApprovalStatus());
        if (!"approved".equals(approval)) {
            throw new BusinessException("审批通过后才能发布");
        }
        String status = norm(task.getTaskStatus());
        if ("deploying".equals(status) || "building".equals(status)) {
            throw new BusinessException("任务已在发布中");
        }
        if ("success".equals(status)) {
            throw new BusinessException("任务已发布成功");
        }
        if (DeployModuleJson.parseImages(task.getDeployModules()).isEmpty()) {
            throw new BusinessException("请至少添加一个上线模块");
        }
        // 失败重发：允许再次触发 CD
        deployTaskCdService.startCdAfterApproved(task);
        return requireTask(taskId);
    }

    @Override
    public void fillActionFlags(DeployTaskDTO dto, DeployTask task, String username, Long userId,
                                boolean hasCreate, boolean hasUnlock) {
        boolean locked = isLocked(task);
        String approval = norm(task.getApprovalStatus());
        String status = norm(task.getTaskStatus());
        boolean terminalDeploy = "deploying".equals(status) || "building".equals(status)
                || "success".equals(status);
        boolean creator = isCreator(task, username, userId);
        boolean hasModules = !DeployModuleJson.parseImages(task.getDeployModules()).isEmpty();
        boolean privileged = creator || hasUnlock;
        // failed 可解锁后重编；审批中/已通过不可编
        boolean blockedByApproval = "pending".equals(approval) || "approved".equals(approval);

        dto.setCanEdit(hasCreate && !locked && !terminalDeploy && !blockedByApproval);
        dto.setCanLock(hasCreate && !locked && !terminalDeploy && !blockedByApproval
                && privileged && hasModules);
        dto.setCanUnlock(locked && !terminalDeploy
                && (!"approved".equals(approval)
                    || "pending".equals(status)
                    || "failed".equals(status)
                    || "cancelled".equals(status))
                && canUnlock(task, username, userId, hasUnlock));
        dto.setCanSubmitApproval(locked && !terminalDeploy && privileged && hasModules
                && ("none".equals(approval) || "rejected".equals(approval) || !StringUtils.hasText(approval)));
        // 审批通过后才能发布；已在部署中/成功则不可再点
        dto.setCanPublish(locked && privileged && hasModules
                && "approved".equals(approval)
                && ("pending".equals(status) || "failed".equals(status) || !StringUtils.hasText(status)));
        dto.setShowPublish(hasCreate || privileged);
        dto.setLocked(locked ? 1 : 0);
        dto.setLockedBy(task.getLockedBy());
        dto.setLockedAt(task.getLockedAt());
    }

    private DeployTask requireTask(Long taskId) {
        DeployTask task = deployTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("任务不存在");
        }
        return task;
    }

    private static boolean isOwner(DeployModuleItemDTO item, String username, Long userId) {
        if (item == null) {
            return false;
        }
        if (userId != null && item.getOwnerId() != null && userId.equals(item.getOwnerId())) {
            return true;
        }
        return StringUtils.hasText(username)
                && StringUtils.hasText(item.getOwnerName())
                && username.trim().equalsIgnoreCase(item.getOwnerName().trim());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String normImage(String image) {
        return image == null ? "" : image.trim().toLowerCase(Locale.ROOT);
    }
}
