package com.opsflow.integration.pipeline;

import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;

/**
 * 在构建节点（SSH）或本地执行命令
 */
@Slf4j
@Component
public class NodeCommandHelper {

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private SshClient sshClient;

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command) {
        return runOnBuildNode(context, command, null);
    }

    public CommandResult runOnBuildNode(PipelineExecutionContext context, String command, String workDir) {
        Long nodeId = resolveBuildNodeId(context);
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在构建节点 {}@{} 执行命令", node.getUsername(), node.getHost());
                SshCommandResult result = sshClient.execute(node, wrapCommandWithWorkDir(command, workDir, true));
                return CommandResult.fromSsh(result);
            }
            log.warn("构建节点不存在: {}", nodeId);
        }

        log.info("未配置构建节点，在本地执行命令");
        return runLocal(command, WorkspacePathHelper.toLocalPath(workDir));
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command) {
        return runOnDeployNode(context, command, null);
    }

    public CommandResult runOnDeployNode(PipelineExecutionContext context, String command, String workDir) {
        Long nodeId = resolveDeployNodeId(context);
        if (nodeId != null) {
            BuildNode node = buildNodeMapper.selectById(nodeId);
            if (node != null) {
                log.info("在部署节点 {}@{} 执行命令", node.getUsername(), node.getHost());
                SshCommandResult result = sshClient.execute(node, wrapCommandWithWorkDir(command, workDir, true));
                return CommandResult.fromSsh(result);
            }
            log.warn("部署节点不存在: {}", nodeId);
        }

        log.info("未配置部署节点，在本地执行命令");
        return runLocal(command, WorkspacePathHelper.toLocalPath(workDir));
    }

    public Long resolveBuildNodeId(PipelineExecutionContext context) {
        if (context.getOptions() == null) {
            return null;
        }
        if (context.getOptions().getNodeId() != null) {
            return context.getOptions().getNodeId();
        }
        if (context.getOptions().getBuildNodeId() != null) {
            return context.getOptions().getBuildNodeId();
        }
        return null;
    }

    public Long resolveDeployNodeId(PipelineExecutionContext context) {
        if (context.getOptions() == null) {
            return null;
        }
        if (context.getOptions().getNodeId() != null) {
            return context.getOptions().getNodeId();
        }
        return context.getOptions().getDeployNodeId();
    }

    public String describeBuildNode(PipelineExecutionContext context) {
        return describeNode(resolveBuildNodeId(context), "本机");
    }

    public String describeDeployNode(PipelineExecutionContext context) {
        return describeNode(resolveDeployNodeId(context), "本机");
    }

    private String describeNode(Long nodeId, String fallback) {
        if (nodeId == null) {
            return fallback;
        }
        BuildNode node = buildNodeMapper.selectById(nodeId);
        if (node == null) {
            return fallback;
        }
        String name = node.getName() != null && !node.getName().trim().isEmpty() ? node.getName().trim() : "node-" + nodeId;
        String host = node.getHost() != null && !node.getHost().trim().isEmpty() ? node.getHost().trim() : "";
        return host.isEmpty() ? name : name + " (" + host + ")";
    }

    private String wrapCommandWithWorkDir(String command, String workDir, boolean remote) {
        if (workDir == null || workDir.trim().isEmpty()) {
            return command;
        }
        String shellPath = remote
            ? WorkspacePathHelper.toRemoteShellPath(workDir)
            : shellQuote(WorkspacePathHelper.toLocalPath(workDir));
        return "mkdir -p " + shellPath + " && cd " + shellPath + " && " + command;
    }

    public CommandResult runLocal(String command, String workDir) {
        CommandResult result = new CommandResult();
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
            if (workDir != null && !workDir.isEmpty()) {
                processBuilder.directory(new File(workDir));
            }
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            result.setExitCode(exitCode);
            result.setOutput(output.toString());
            result.setSuccess(exitCode == 0);
            if (exitCode != 0) {
                result.setErrorMessage("命令执行失败，退出码: " + exitCode);
            }
        } catch (Exception e) {
            log.error("本地命令执行失败", e);
            result.setSuccess(false);
            result.setErrorMessage("本地命令执行失败: " + e.getMessage());
        }
        return result;
    }

    public static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    @Data
    public static class CommandResult {
        private boolean success;
        private int exitCode;
        private String output;
        private String errorMessage;

        public static CommandResult fromSsh(SshCommandResult sshResult) {
            CommandResult result = new CommandResult();
            result.setSuccess(sshResult.isSuccess());
            result.setExitCode(sshResult.getExitCode());
            result.setOutput(sshResult.getOutput());
            result.setErrorMessage(sshResult.getErrorMessage());
            return result;
        }
    }
}
