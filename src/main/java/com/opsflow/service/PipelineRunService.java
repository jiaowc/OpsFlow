package com.opsflow.service;

import com.opsflow.api.dto.BuildResponse;
import com.opsflow.api.dto.PipelineJobPageResult;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.api.dto.PipelineStageDTO;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.RollbackCandidateDTO;
import com.opsflow.api.dto.RollbackRequest;

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
     * 唯一可靠的 job 启动入口：同步准备 BUILDING + 阶段记录，事务提交后派发执行线程。
     * <p>普通构建、CD 首次启动、CD 重试、CD 串行推进均应调用此方法。</p>
     */
    void launchJob(Long buildJobId);

    /**
     * 同步准备运行：立即标记 BUILDING 并创建 PENDING 阶段，便于前端即时展示；
     * 然后异步真正执行 Pipeline。
     * @deprecated 请使用 {@link #launchJob(Long)}
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
     * 查询阶段日志详情（文件增量读取，供前端实时轮询）
     *
     * @param offset 字节偏移，从 0 开始；文件被重写变短时会返回 reset=true
     */
    Map<String, Object> getStageLogDetail(Long buildJobId, Long stageId, long offset);

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

    /**
     * 查询可回滚的历史成功版本（同服务、同环境，含镜像地址）
     */
    List<RollbackCandidateDTO> listRollbackCandidates(Long jobId);

    /**
     * 回滚：在原 Job 上更新目标镜像，仅重跑 CD 步骤（不新建 Job）
     */
    BuildResponse rollbackPipelineJob(Long jobId, RollbackRequest request);

    /**
     * 服务启动时回收因重启中断的运行中任务，避免流水线按钮长期禁用。
     */
    void recoverInterruptedRunningJobs();
}
