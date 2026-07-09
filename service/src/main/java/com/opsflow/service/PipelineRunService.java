package com.opsflow.service;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.BuildRequest;

import java.util.List;
import java.util.Map;

/**
 * 平台原生流水线运行服务
 */
public interface PipelineRunService {

    /**
     * 异步执行构建任务的 Pipeline
     */
    void executeBuildJobAsync(Long buildJobId);

    /**
     * 同步准备运行：立即标记 BUILDING 并创建 PENDING 阶段，便于前端即时展示；
     * 然后异步真正执行 Pipeline。
     */
    void prepareAndExecuteAsync(Long buildJobId);

    /**
     * 查询流水线任务列表（兼容旧接口，默认第一页）
     */
    List<PipelineJobViewDTO> listPipelineJobs(Long envId);

    /**
     * 分页查询流水线任务历史
     */
    PipelineJobPageResult listPipelineJobsPage(Long envId, int page, int pageSize);

    /**
     * 查询构建任务阶段
     */
    List<PipelineStageDTO> getBuildStages(Long buildJobId);

    /**
     * 查询阶段日志
     */
    String getStageLog(Long buildJobId, Long stageId);

    /**
     * 查询构建状态（含阶段）
     */
    Map<String, Object> getBuildRunStatus(Long buildJobId);

    /**
     * 查询单个流水线任务详情
     */
    PipelineJobViewDTO getPipelineJob(Long buildJobId);

    /**
     * 更新流水线任务（运行中不可编辑）
     */
    PipelineJobViewDTO updatePipelineJob(Long buildJobId, BuildRequest request);

    /**
     * 删除流水线任务（运行中不可删除）
     */
    boolean deletePipelineJob(Long buildJobId);

    /**
     * 阶段视图：同一任务配置下的历史构建（含各阶段）
     */
    PipelineViewDTO getStageView(Long anchorJobId, int page, int pageSize);

    /**
     * 对已有任务触发一次新构建（不新增任务行）
     */
    BuildResponse rebuildPipelineJob(Long jobId);
}
