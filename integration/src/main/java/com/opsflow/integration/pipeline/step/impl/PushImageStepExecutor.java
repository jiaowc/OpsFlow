package com.opsflow.integration.pipeline.step.impl;

import com.opsflow.integration.pipeline.PipelineExecutionContext;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.integration.pipeline.step.StepExecutor;
import com.opsflow.integration.pipeline.step.StepExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 上传镜像到 Harbor
 */
@Slf4j
@Component
public class PushImageStepExecutor implements StepExecutor {

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    @Override
    public StepExecutionResult execute(String stepType, Map<String, String> stepParams, PipelineExecutionContext context) {
        StepExecutionResult result = new StepExecutionResult();
        result.setSuccess(false);
        result.setOutputData(new HashMap<>());

        try {
            String workspace = context.getWorkspace();
            String imageFullName = stepParams.get("imageFullName");
            String nodeDesc = nodeCommandHelper.describeBuildNode(context);
            if (imageFullName == null || imageFullName.isEmpty()) {
                result.setErrorMessage("镜像名称不能为空，请先执行镜像制作步骤");
                return result;
            }

            log.info("上传镜像: {}", imageFullName);
            NodeCommandHelper.CommandResult cmdResult = nodeCommandHelper.runOnBuildNode(
                context,
                "docker push " + NodeCommandHelper.shellQuote(imageFullName),
                workspace
            );
            result.setLog("执行节点: " + nodeDesc + "\n工作目录: " + workspace + "\n" + (cmdResult.getOutput() == null ? "" : cmdResult.getOutput()));

            if (!cmdResult.isSuccess()) {
                result.setErrorMessage(cmdResult.getErrorMessage() != null
                    ? cmdResult.getErrorMessage()
                    : "镜像上传失败，退出码: " + cmdResult.getExitCode());
                return result;
            }

            result.getOutputData().put("imageFullName", imageFullName);
            result.getOutputData().put("imageTag", stepParams.get("imageTag"));
            result.setSuccess(true);
            log.info("镜像上传成功: {}", imageFullName);
        } catch (Exception e) {
            log.error("镜像上传失败", e);
            result.setErrorMessage("镜像上传失败: " + e.getMessage());
        }

        return result;
    }

    @Override
    public boolean supports(String stepType) {
        return "push_image".equalsIgnoreCase(stepType);
    }
}
