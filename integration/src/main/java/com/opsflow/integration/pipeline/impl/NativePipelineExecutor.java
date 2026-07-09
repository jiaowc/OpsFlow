package com.opsflow.integration.pipeline.impl;

import com.opsflow.api.dto.PipelineDTO;
import com.opsflow.api.dto.PipelineStepDTO;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.pipeline.*;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 原生 Pipeline 执行器
 */
@Slf4j
@Component("nativePipelineExecutor")
public class NativePipelineExecutor implements PipelineExecutor {
    
    @Autowired
    private List<StepExecutor> stepExecutors;

    @Autowired
    private BuildNodeMapper buildNodeMapper;
    
    /**
     * 执行状态存储（实际应该使用Redis或数据库）
     */
    private Map<String, PipelineExecutionStatus> executionStatusMap = new ConcurrentHashMap<>();
    
    @Override
    public PipelineExecutionResult execute(PipelineDTO pipeline, PipelineExecutionContext context) {
        String executionId = context.getExecutionId();
        if (executionId == null || executionId.isEmpty()) {
            executionId = UUID.randomUUID().toString();
            context.setExecutionId(executionId);
        }
        
        // 初始化执行状态
        PipelineExecutionStatus status = new PipelineExecutionStatus();
        status.setExecutionId(executionId);
        status.setStatus("RUNNING");
        status.setStartTime(System.currentTimeMillis());
        status.setProgress(0);
        executionStatusMap.put(executionId, status);
        
        PipelineExecutionResult result = new PipelineExecutionResult();
        result.setExecutionId(executionId);
        
        try {
            // 创建工作目录（按服务/环境/分支固定路径，便于清理空间步骤复用）
            String workspace = context.getWorkspace();
            if (workspace == null || workspace.isEmpty()) {
                workspace = PipelineWorkspaceResolver.resolve(context, context.getParameters());
            }
            context.setWorkspace(workspace);
            File workspaceDir = new File(WorkspacePathHelper.toLocalPath(workspace));
            if (!workspaceDir.exists()) {
                workspaceDir.mkdirs();
            }
            
            // 解析Pipeline步骤
            List<PipelineStepDTO> steps = pipeline.getSteps();
            // 如果DTO中没有steps，说明需要从外部传入，这里先跳过
            // 实际使用时，应该在调用execute之前将Pipeline模型转换为包含steps的DTO
            
            if (steps == null || steps.isEmpty()) {
                throw new RuntimeException("Pipeline步骤配置为空");
            }
            
            // 按顺序排序，跳过未启用步骤
            steps = steps.stream()
                .filter(step -> step.getEnabled() == null || Boolean.TRUE.equals(step.getEnabled()))
                .sorted(Comparator.comparing(PipelineStepDTO::getOrder))
                .collect(Collectors.toList());
            
            // 执行各个步骤
            Map<String, String> stepOutputs = new HashMap<>();
            int totalSteps = steps.size();
            int currentStepIndex = 0;
            String defaultWorkspaceBase = context.getParameters() != null
                ? context.getParameters().get("workspaceBase")
                : null;
            
            for (PipelineStepDTO step : steps) {
                currentStepIndex++;
                status.setCurrentStep(step.getStepName());
                status.setProgress((int) (currentStepIndex * 100.0 / totalSteps));

                int stepOrder = step.getOrder() != null ? step.getOrder() : currentStepIndex;
                PipelineStageListener stageListener = context.getStageListener();
                long stageStart = System.currentTimeMillis();
                if (stageListener != null) {
                    stageListener.onStageStart(stepOrder, step.getStepType(), step.getStepName());
                }

                log.info("执行步骤 [{}]: {}", step.getStepName(), step.getStepType());
                
                // 查找步骤执行器
                StepExecutor executor = findExecutor(step.getStepType());
                if (executor == null) {
                    throw new RuntimeException("不支持步骤类型: " + step.getStepType());
                }
                
                // 合并步骤参数和上下文参数
                Map<String, String> stepParams = new HashMap<>();
                if (step.getParameters() != null) {
                    stepParams.putAll(step.getParameters());
                }
                // 添加上下文参数
                if (context.getParameters() != null) {
                    stepParams.putAll(context.getParameters());
                }
                // 添加步骤输出数据
                stepParams.putAll(stepOutputs);

                applyStepExecutionContext(step, context, stepParams, defaultWorkspaceBase);
                
                // 执行步骤
                StepExecutionResult stepResult = executor.execute(step.getStepType(), stepParams, context);
                long stageDuration = System.currentTimeMillis() - stageStart;

                if (stageListener != null) {
                    stageListener.onStageComplete(
                        stepOrder,
                        Boolean.TRUE.equals(stepResult.getSuccess()),
                        stepResult.getLog(),
                        stepResult.getErrorMessage(),
                        stageDuration
                    );
                }

                if (!stepResult.getSuccess()) {
                    // 步骤失败
                    result.setSuccess(false);
                    result.setErrorMessage(stepResult.getErrorMessage());
                    status.setStatus("FAILED");
                    status.setEndTime(System.currentTimeMillis());
                    return result;
                }
                
                // 保存步骤输出
                if (stepResult.getOutputData() != null) {
                    stepOutputs.putAll(stepResult.getOutputData());
                }
                
                log.info("步骤 [{}] 执行成功", step.getStepName());
            }
            
            // 所有步骤执行成功
            result.setSuccess(true);
            status.setStatus("SUCCESS");
            status.setProgress(100);
            
            // 从输出中提取镜像信息
            if (stepOutputs.containsKey("imageTag")) {
                result.setImageTag(stepOutputs.get("imageTag"));
            }
            if (stepOutputs.containsKey("imageFullName")) {
                result.setImageFullName(stepOutputs.get("imageFullName"));
            }
            
        } catch (Exception e) {
            log.error("Pipeline执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            status.setStatus("FAILED");
        } finally {
            status.setEndTime(System.currentTimeMillis());
            executionStatusMap.put(executionId, status);
        }
        
        return result;
    }

    private void applyStepExecutionContext(PipelineStepDTO step, PipelineExecutionContext context,
            Map<String, String> stepParams, String defaultWorkspaceBase) {
        if (context.getOptions() == null) {
            context.setOptions(new PipelineExecutionContext.ExecutionOptions());
        }

        Long overrideNodeId = null;
        if ("specific".equalsIgnoreCase(step.getNodeSelection()) && step.getNodeId() != null) {
            overrideNodeId = step.getNodeId();
        }
        context.getOptions().setNodeId(overrideNodeId);

        String workspaceBase = null;
        if (stepParams != null && stepParams.get("workspaceBase") != null && !stepParams.get("workspaceBase").trim().isEmpty()) {
            workspaceBase = stepParams.get("workspaceBase").trim();
        } else if (overrideNodeId != null) {
            BuildNode node = buildNodeMapper.selectById(overrideNodeId);
            if (node != null && node.getWorkDir() != null && !node.getWorkDir().trim().isEmpty()) {
                workspaceBase = node.getWorkDir().trim();
            }
        }
        if (workspaceBase == null || workspaceBase.isEmpty()) {
            workspaceBase = defaultWorkspaceBase;
        }

        if (context.getParameters() != null) {
            if (workspaceBase != null && !workspaceBase.isEmpty()) {
                context.getParameters().put("workspaceBase", workspaceBase);
            } else {
                context.getParameters().remove("workspaceBase");
            }
        }
        context.setWorkspace(PipelineWorkspaceResolver.resolve(context, stepParams));
    }
    
    @Override
    public void cancel(String executionId) {
        PipelineExecutionStatus status = executionStatusMap.get(executionId);
        if (status != null && "RUNNING".equals(status.getStatus())) {
            status.setStatus("CANCELLED");
            status.setEndTime(System.currentTimeMillis());
            log.info("Pipeline执行已取消: {}", executionId);
        }
    }
    
    @Override
    public PipelineExecutionStatus getStatus(String executionId) {
        return executionStatusMap.get(executionId);
    }
    
    /**
     * 查找步骤执行器
     */
    private StepExecutor findExecutor(String stepType) {
        if (stepExecutors == null) {
            return null;
        }
        return stepExecutors.stream()
            .filter(executor -> executor.supports(stepType))
            .findFirst()
            .orElse(null);
    }
}

