package com.opsflow.service;

import com.opsflow.api.dto.PipelineBoardViewDTO;

import java.util.List;
import java.util.Set;

public interface PipelineViewService {

    PipelineBoardViewDTO create(PipelineBoardViewDTO request);

    /**
     * 当前用户可见的启用视图（管理员 / pipeline_config:manage / * 可见全部）
     */
    List<PipelineBoardViewDTO> list();

    PipelineBoardViewDTO getById(Long id);

    PipelineBoardViewDTO update(Long id, PipelineBoardViewDTO request);

    boolean delete(Long id);

    /**
     * 按给定 ID 顺序批量更新视图排序（越小越靠前）。
     */
    void reorder(List<Long> ids);

    /** 是否可管理全部视图（不受角色授权限制） */
    boolean canAccessAllViews();

    /** 当前用户可访问的环境 ID 集合（来自已授权视图） */
    Set<Long> listAccessibleEnvIds();

    /** 校验是否可访问指定环境；envId 为空表示「全部」，仅全量权限用户允许 */
    void assertAccessibleEnv(Long envId);
}
