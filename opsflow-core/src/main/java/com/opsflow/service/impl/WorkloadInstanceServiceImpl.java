package com.opsflow.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.PipelineJobViewDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.mapper.PipelineMapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Pipeline;
import com.opsflow.integration.k8s.K8sCredentialService;
import com.opsflow.integration.pipeline.NodeCommandHelper;
import com.opsflow.service.PipelineRunService;
import com.opsflow.service.WorkloadInstanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class WorkloadInstanceServiceImpl implements WorkloadInstanceService {

    private static final int COMMAND_TIMEOUT_MS = 30000;
    private static final int DEFAULT_TAIL_LINES = 300;
    private static final int MAX_TAIL_LINES = 2000;
    private static final int MAX_LOG_CHARS = 1024 * 1024;
    private static final String SPLIT = "__OPSFLOW_SPLIT__";
    private static final Pattern K8S_NAME = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");

    @Autowired
    private PipelineRunService pipelineRunService;

    @Autowired
    private ServiceMapper serviceMapper;

    @Autowired
    private EnvMapper envMapper;

    @Autowired
    private PipelineMapper pipelineMapper;

    @Autowired
    private BuildJobMapper buildJobMapper;

    @Autowired
    private K8sCredentialService k8sCredentialService;

    @Autowired
    private NodeCommandHelper nodeCommandHelper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final class Target {
        String namespace;
        String deployment;
        Long clusterId;
        Long nodeId;
        String envName;
    }

    @Override
    public Map<String, Object> listInstances(Long jobId) {
        Target t = resolveTarget(jobId);
        String ns = NodeCommandHelper.shellQuote(t.namespace);
        String deploy = NodeCommandHelper.shellQuote("deployment/" + t.deployment);
        String selectorTpl = NodeCommandHelper.shellQuote(
                "{{range $k, $v := .spec.selector.matchLabels}}{{$k}}={{$v}},{{end}}");
        String script = "D=$(kubectl -n " + ns + " get " + deploy + " -o json --request-timeout=15s 2>&1) || { echo \"$D\"; exit 1; }\n"
                + "SEL=$(kubectl -n " + ns + " get " + deploy + " -o go-template=" + selectorTpl + " --request-timeout=15s 2>&1) || { echo \"$SEL\"; exit 1; }\n"
                + "P=$(kubectl -n " + ns + " get pods -l \"${SEL%,}\" -o json --request-timeout=15s 2>&1) || { echo \"$P\"; exit 1; }\n"
                + "echo \"$D\"; echo '" + SPLIT + "'; echo \"$P\"\n";
        String output = runKubectl(t, script, "instances");

        int idx = output.indexOf(SPLIT);
        if (idx < 0) {
            throw new BusinessException("解析集群返回失败");
        }
        JsonNode deployment = readJson(output.substring(0, idx));
        JsonNode pods = readJson(output.substring(idx + SPLIT.length()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespace", t.namespace);
        result.put("deployment", t.deployment);
        result.put("envName", t.envName);
        result.put("summary", deploymentSummary(deployment));
        List<Map<String, Object>> podList = new ArrayList<>();
        for (JsonNode pod : pods.path("items")) {
            podList.add(podView(pod));
        }
        podList.sort(Comparator.comparing(p -> String.valueOf(p.get("name"))));
        result.put("pods", podList);
        return result;
    }

    @Override
    public String podLogs(Long jobId, String podName, String container, Integer tailLines, boolean previous) {
        Target t = resolveTarget(jobId);
        assertOwnPod(t, podName);
        int tail = tailLines == null || tailLines <= 0 ? DEFAULT_TAIL_LINES : Math.min(tailLines, MAX_TAIL_LINES);
        StringBuilder cmd = new StringBuilder("kubectl -n ").append(NodeCommandHelper.shellQuote(t.namespace))
                .append(" logs ").append(NodeCommandHelper.shellQuote(podName))
                .append(" --tail=").append(tail).append(" --timestamps --request-timeout=20s");
        if (container != null && !container.trim().isEmpty()) {
            if (!K8S_NAME.matcher(container.trim()).matches()) {
                throw new BusinessException("容器名称不合法");
            }
            cmd.append(" -c ").append(NodeCommandHelper.shellQuote(container.trim()));
        }
        if (previous) {
            cmd.append(" --previous");
        }
        String output = runKubectl(t, cmd.append(" 2>&1\n").toString(), "logs");
        if (output.length() > MAX_LOG_CHARS) {
            output = "...（仅显示最后 1MB）\n" + output.substring(output.length() - MAX_LOG_CHARS);
        }
        return output;
    }

    @Override
    public List<Map<String, Object>> podEvents(Long jobId, String podName) {
        Target t = resolveTarget(jobId);
        assertOwnPod(t, podName);
        String script = "kubectl -n " + NodeCommandHelper.shellQuote(t.namespace)
                + " get events --field-selector " + NodeCommandHelper.shellQuote("involvedObject.name=" + podName)
                + " -o json --request-timeout=15s 2>&1\n";
        JsonNode events = readJson(runKubectl(t, script, "events"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode e : events.path("items")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", e.path("type").asText(""));
            item.put("reason", e.path("reason").asText(""));
            item.put("message", e.path("message").asText(""));
            item.put("count", e.path("count").asInt(1));
            item.put("time", firstText(e, "lastTimestamp", "eventTime", "firstTimestamp"));
            list.add(item);
        }
        list.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return list;
    }

    private Target resolveTarget(Long jobId) {
        // getPipelineJob 内部已做环境/视图权限校验
        PipelineJobViewDTO job = pipelineRunService.getPipelineJob(jobId);
        com.opsflow.dao.model.Service service = job.getServiceId() != null ? serviceMapper.selectById(job.getServiceId()) : null;
        Env env = job.getEnvId() != null ? envMapper.selectById(job.getEnvId()) : null;
        if (service == null || env == null) {
            throw new BusinessException("任务未关联服务或环境");
        }
        BuildJob latest = job.getLatestBuildId() != null ? buildJobMapper.selectOne(new LambdaQueryWrapper<BuildJob>()
                .select(BuildJob::getPipelineTemplateId, BuildJob::getBuildParameters)
                .eq(BuildJob::getId, job.getLatestBuildId())) : null;
        JsonNode params = parseParams(latest != null ? latest.getBuildParameters() : null);

        // 与部署步骤（PipelineRunServiceImpl#buildContext）保持一致的取值规则
        Target t = new Target();
        t.deployment = !isBlank(service.getK8sDeployment()) ? service.getK8sDeployment().trim() : service.getCode();
        t.namespace = !isBlank(params.path("k8sNamespace").asText(null))
                ? params.path("k8sNamespace").asText().trim() : env.getK8sNamespace();
        t.clusterId = env.getClusterId();
        String overrideCluster = params.path("clusterId").asText(null);
        if (!isBlank(overrideCluster)) {
            try {
                t.clusterId = Long.parseLong(overrideCluster.trim());
            } catch (NumberFormatException ignore) {
                // 非法值沿用环境默认集群
            }
        }
        if (isBlank(t.deployment)) {
            throw new BusinessException("服务未配置 K8s Deployment 名称");
        }
        if (isBlank(t.namespace)) {
            throw new BusinessException("环境未配置 K8s 命名空间");
        }
        t.namespace = t.namespace.trim();
        t.envName = env.getName();
        Long templateId = latest != null && latest.getPipelineTemplateId() != null
                ? latest.getPipelineTemplateId() : job.getPipelineTemplateId();
        Pipeline pipeline = templateId != null ? pipelineMapper.selectById(templateId) : null;
        t.nodeId = pipeline != null ? pipeline.getDeployNodeId() : null;
        return t;
    }

    private JsonNode parseParams(String json) {
        if (isBlank(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * 只允许查看本服务 Deployment 创建的 Pod（{deployment}-{rs hash}-{pod hash}），避免越权读取同命名空间其它服务
     */
    private void assertOwnPod(Target t, String podName) {
        if (podName == null || !K8S_NAME.matcher(podName).matches() || !podName.startsWith(t.deployment + "-")) {
            throw new BusinessException("Pod 不属于当前服务");
        }
    }

    private String runKubectl(Target t, String script, String purpose) {
        String sessionKey = "instance-" + purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
        String kubeSetup = k8sCredentialService.buildRemoteKubeconfigSetup(sessionKey, t.clusterId, null);
        if (kubeSetup.isEmpty()) {
            throw new BusinessException("环境所属集群未关联有效的 Kubeconfig");
        }
        // kubeSetup 已自带 umask 077 与 trap 清理
        String command = kubeSetup + script;
        NodeCommandHelper.CommandResult result = nodeCommandHelper.runOnNode(t.nodeId, command, COMMAND_TIMEOUT_MS);
        String output = result.getOutput() != null ? result.getOutput().trim() : "";
        if (!result.isSuccess()) {
            String message = output.isEmpty() ? result.getErrorMessage() : output;
            if (message != null && message.length() > 500) {
                message = message.substring(0, 500) + "...";
            }
            throw new BusinessException("查询集群失败: " + (message != null ? message : "命令执行失败"));
        }
        return output;
    }

    private JsonNode readJson(String text) {
        try {
            return objectMapper.readTree(text.trim());
        } catch (Exception e) {
            String preview = text.length() > 300 ? text.substring(0, 300) + "..." : text;
            throw new BusinessException("解析集群返回失败: " + preview.trim());
        }
    }

    private Map<String, Object> deploymentSummary(JsonNode d) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("replicas", d.path("spec").path("replicas").asInt(0));
        s.put("readyReplicas", d.path("status").path("readyReplicas").asInt(0));
        s.put("updatedReplicas", d.path("status").path("updatedReplicas").asInt(0));
        s.put("availableReplicas", d.path("status").path("availableReplicas").asInt(0));
        List<String> images = new ArrayList<>();
        for (JsonNode c : d.path("spec").path("template").path("spec").path("containers")) {
            images.add(c.path("image").asText(""));
        }
        s.put("images", images);
        return s;
    }

    private Map<String, Object> podView(JsonNode pod) {
        Map<String, Object> p = new LinkedHashMap<>();
        JsonNode status = pod.path("status");
        p.put("name", pod.path("metadata").path("name").asText(""));
        p.put("node", pod.path("spec").path("nodeName").asText(""));
        p.put("podIp", status.path("podIP").asText(""));
        p.put("startTime", status.path("startTime").asText(""));

        int ready = 0;
        int total = 0;
        int restarts = 0;
        String waitingReason = null;
        String lastTermination = null;
        List<String> containers = new ArrayList<>();
        for (JsonNode c : pod.path("spec").path("containers")) {
            containers.add(c.path("name").asText(""));
        }
        for (JsonNode cs : status.path("containerStatuses")) {
            total++;
            if (cs.path("ready").asBoolean(false)) {
                ready++;
            }
            restarts += cs.path("restartCount").asInt(0);
            String reason = cs.path("state").path("waiting").path("reason").asText(null);
            if (reason == null) {
                reason = cs.path("state").path("terminated").path("reason").asText(null);
            }
            if (reason != null && waitingReason == null) {
                waitingReason = reason;
            }
            JsonNode last = cs.path("lastState").path("terminated");
            if (!last.isMissingNode() && lastTermination == null) {
                lastTermination = last.path("reason").asText("") + " (exit " + last.path("exitCode").asInt() + ", "
                        + last.path("finishedAt").asText("") + ")";
            }
        }
        if (total == 0) {
            total = containers.size();
        }
        String display = pod.path("metadata").has("deletionTimestamp") ? "Terminating"
                : waitingReason != null ? waitingReason : status.path("phase").asText("Unknown");
        p.put("status", display);
        p.put("ready", ready + "/" + total);
        p.put("allReady", total > 0 && ready == total);
        p.put("restarts", restarts);
        p.put("lastTermination", lastTermination);
        p.put("containers", containers);
        return p;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
