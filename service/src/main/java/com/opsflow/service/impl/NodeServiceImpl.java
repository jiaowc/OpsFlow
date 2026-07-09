package com.opsflow.service.impl;

import com.opsflow.api.dto.NodeEnvCheckItemDTO;
import com.opsflow.api.dto.NodeEnvCheckResultDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import com.opsflow.service.NodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class NodeServiceImpl implements NodeService {

    private static final Pattern COMMAND_ERROR_PATTERN = Pattern.compile(
            "(?i)(command not found|not found|no such file|cannot execute|which: no |: not found)",
            Pattern.CASE_INSENSITIVE
    );

    @Autowired
    private BuildNodeMapper buildNodeMapper;

    @Autowired
    private SshClient sshClient;

    @Override
    public NodeEnvCheckResultDTO checkEnvironment(Long nodeId) {
        BuildNode node = buildNodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException("节点不存在");
        }
        if ("build".equals(node.getNodeType())) {
            return checkBuildEnvironment(node);
        }
        if ("deploy".equals(node.getNodeType())) {
            return checkDeployEnvironment(node);
        }
        throw new BusinessException("不支持的节点类型: " + node.getNodeType());
    }

    private NodeEnvCheckResultDTO checkBuildEnvironment(BuildNode node) {
        List<NodeEnvCheckItemDTO> items = new ArrayList<>();

        SshCommandResult sshResult = normalizeCheckResult(sshClient.testConnection(node));
        items.add(toItem("ssh", "SSH 连接", true, sshResult));

        if (!sshResult.isSuccess()) {
            return buildResult(node, items, false, "SSH 连接失败，无法继续检测构建环境");
        }

        items.add(runCheck(node, "java", "JDK", "java -version 2>&1 | head -n 1", true));
        items.add(runCheck(node, "maven", "Maven", "mvn -version 2>&1 | head -n 1", true));
        items.add(runCheck(node, "git", "Git", "git --version 2>&1", true));
        items.add(runCheck(node, "docker", "Docker", "docker --version 2>&1", false));

        boolean passed = allRequiredPassed(items);
        String summary = passed
                ? "构建环境检测通过，满足 CI 构建条件"
                : "构建环境检测未通过，请安装或配置缺失的必检组件";

        return buildResult(node, items, passed, summary);
    }

    private NodeEnvCheckResultDTO checkDeployEnvironment(BuildNode node) {
        List<NodeEnvCheckItemDTO> items = new ArrayList<>();

        SshCommandResult sshResult = normalizeCheckResult(sshClient.testConnection(node));
        items.add(toItem("ssh", "SSH 连接", true, sshResult));

        if (!sshResult.isSuccess()) {
            return buildResult(node, items, false, "SSH 连接失败，无法继续检测部署环境");
        }

        items.add(runCheck(node, "kubectl", "kubectl", "kubectl version --client 2>&1 | head -n 1", true));
        items.add(runCheck(node, "kubectl_cluster", "K8s 集群连通", "kubectl cluster-info 2>&1 | head -n 1", false));

        boolean passed = allRequiredPassed(items);
        String summary = passed
                ? "部署环境检测通过，满足 CD 部署条件"
                : "部署环境检测未通过，请安装或配置 kubectl";

        return buildResult(node, items, passed, summary);
    }

    private boolean allRequiredPassed(List<NodeEnvCheckItemDTO> items) {
        return items.stream()
                .filter(item -> Boolean.TRUE.equals(item.getRequired()))
                .allMatch(item -> Boolean.TRUE.equals(item.getPassed()));
    }

    private NodeEnvCheckItemDTO runCheck(BuildNode node, String key, String name, String command, boolean required) {
        SshCommandResult result = normalizeCheckResult(sshClient.executeCheck(node, command));
        return toItem(key, name, required, result);
    }

    /**
     * 根据退出码与输出内容综合判定检测结果
     */
    private SshCommandResult normalizeCheckResult(SshCommandResult result) {
        if (!result.isSuccess()) {
            return result;
        }
        String output = result.getOutput();
        if (output == null || output.trim().isEmpty()) {
            return SshCommandResult.fail("命令无有效输出");
        }
        if (looksLikeCommandFailure(output)) {
            return SshCommandResult.fail(output.trim());
        }
        return result;
    }

    private boolean looksLikeCommandFailure(String output) {
        String text = output.trim();
        if (text.startsWith("bash:")) {
            return true;
        }
        return COMMAND_ERROR_PATTERN.matcher(text).find();
    }

    private NodeEnvCheckItemDTO toItem(String key, String name, boolean required, SshCommandResult result) {
        NodeEnvCheckItemDTO item = new NodeEnvCheckItemDTO();
        item.setKey(key);
        item.setName(name);
        item.setRequired(required);
        item.setPassed(result.isSuccess());
        if (result.isSuccess()) {
            item.setVersion(trimOutput(result.getOutput()));
            item.setMessage("检测通过");
        } else {
            String detail = result.getErrorMessage() != null ? result.getErrorMessage() : result.getOutput();
            item.setVersion(trimOutput(detail));
            item.setMessage("检测失败");
        }
        return item;
    }

    private String trimOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return "-";
        }
        String line = output.split("\\r?\\n")[0].trim();
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    private NodeEnvCheckResultDTO buildResult(BuildNode node, List<NodeEnvCheckItemDTO> items,
                                              boolean passed, String summary) {
        NodeEnvCheckResultDTO dto = new NodeEnvCheckResultDTO();
        dto.setNodeId(node.getId());
        dto.setNodeName(node.getName());
        dto.setNodeType(node.getNodeType());
        dto.setPassed(passed);
        dto.setSummary(summary);
        dto.setItems(items);
        return dto;
    }
}
