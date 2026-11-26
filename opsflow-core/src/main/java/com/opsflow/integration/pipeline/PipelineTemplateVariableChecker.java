package com.opsflow.integration.pipeline;

import com.opsflow.api.dto.TemplateVariableCheckDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查模版（Dockerfile / Deployment / Service）里写的 ${变量} 能否被渲染。
 * <p>
 * 渲染时只做 ${变量名} 的原样替换（见 {@link PipelineTemplateRenderer#render(String, Map)}），
 * 变量名写错、多了空格、换了别的写法都不会报错，而是原样留在最终文件里。
 * 这里在保存模版前把这些写法找出来。
 * </p>
 * <p>
 * 变量清单必须和 {@link PipelineTemplateRenderer#buildVariables} 以及
 * PipelineJobRunner#buildContext 写入的参数保持一致，新增变量时两边一起改。
 * </p>
 */
public final class PipelineTemplateVariableChecker {

    public static final String TYPE_DOCKERFILE = "dockerfile";
    public static final String TYPE_DEPLOYMENT = "deployment";
    public static final String TYPE_SERVICE = "service";

    private static final String MSG_UNKNOWN = "不是平台支持的变量，渲染时不会被替换";

    /** ${...}，不跨行 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}\\r\\n]*)\\}");
    /** 行尾没有闭合的 ${... */
    private static final Pattern UNCLOSED = Pattern.compile("\\$\\{[^}\\r\\n]*$");
    /** {{name}}、$(name)、{name}、$name 这几种容易和 ${name} 混淆的写法 */
    private static final Pattern OTHER_SYNTAX = Pattern.compile(
            "\\{\\{\\s*(\\w+)\\s*\\}\\}|\\$\\((\\w+)\\)|(?<![$\\w{])\\{(\\w+)\\}(?!\\})|\\$(\\w+)");
    private static final Pattern IDENTIFIER_HEAD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UPPER_CASE_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]*$");
    private static final Pattern DOCKER_DECLARATION = Pattern.compile("^\\s*(ARG|ENV)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    /** 常见的习惯叫法 → 平台变量名（键为去掉分隔符后的小写） */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        alias("serviceName", "name", "appname", "app", "svcname", "application");
        alias("namespace", "ns", "namespaces", "k8sns");
        alias("servicePort", "containerport", "targetport", "svcport", "serverport", "appport", "ports");
        alias("image", "imagename", "imageurl", "img", "images");
        alias("imageTag", "tag", "version", "imageversion");
        alias("env", "environment", "envs");
        alias("commitId", "commit", "commitsha", "gitcommit", "sha");
        alias("deployment", "deploymentname", "deploy", "deployname");
        alias("gitRepo", "repo", "giturl", "repository");
        alias("serviceType", "svctype", "type");
    }

    private PipelineTemplateVariableChecker() {
    }

    private static void alias(String variable, String... names) {
        for (String name : names) {
            ALIASES.put(name, variable);
        }
    }

    /**
     * 某类模版支持的全部变量，顺序即展示顺序。
     *
     * @param type dockerfile / deployment / service
     */
    public static List<TemplateVariableCheckDTO.Variable> supportedVariables(String type) {
        boolean dockerfile = TYPE_DOCKERFILE.equals(type);
        String imageCondition = dockerfile ? null : "需要前面有镜像构建步骤，或由上线任务带入镜像";
        String namespaceCondition = "需要环境配置了 K8s 命名空间";

        List<TemplateVariableCheckDTO.Variable> list = new ArrayList<>();
        add(list, "serviceName", "服务标识（服务的 code），适合用作镜像名和 K8s 资源名", null);
        add(list, "serviceCode", "同 serviceName，兼容旧模版", null);
        add(list, "service", "服务的显示名称，可能含中文，不适合用作 K8s 资源名", null);
        add(list, "servicePort", "服务端口，取自服务管理的「对外端口」，未配置时为 8080", null);
        add(list, "port", "同 servicePort", null);
        add(list, "namespace", "K8s 命名空间", namespaceCondition);
        add(list, "k8sNamespace", "同 namespace", namespaceCondition);
        add(list, "env", "环境名称", null);
        add(list, "envName", "同 env", null);
        add(list, "deployment", "K8s Deployment 名称，服务未配置时取服务标识", null);
        add(list, "image", dockerfile ? "本次构建的完整镜像地址" : "要部署的完整镜像地址", imageCondition);
        add(list, "imageFullName", "同 image", imageCondition);
        add(list, "imageTag", "镜像 tag", imageCondition);
        add(list, "serviceType", "Service 类型，取自 Service 模版的「服务类型」，默认 ClusterIP", null);
        add(list, "branch", "本次构建使用的分支或 tag", null);
        add(list, "commitId", "本次构建的 Git 提交 ID", "需要前面有代码检出步骤");
        add(list, "gitRepo", "服务的 Git 仓库地址", null);
        add(list, "gitType", "Git 引用类型：branch 或 tag", null);
        return list;
    }

    private static void add(List<TemplateVariableCheckDTO.Variable> list, String name, String description, String condition) {
        TemplateVariableCheckDTO.Variable variable = new TemplateVariableCheckDTO.Variable();
        variable.setName(name);
        variable.setDescription(description);
        variable.setCondition(condition);
        list.add(variable);
    }

    /**
     * 检查模版内容里的变量写法。
     *
     * @param type    dockerfile / deployment / service
     * @param content 模版内容
     * @return 检查结果；content 为空时视为通过
     */
    public static TemplateVariableCheckDTO check(String type, String content) {
        String normalizedType = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        Scan scan = new Scan(normalizedType);

        String[] lines = content == null ? new String[0] : content.replace("\r", "").split("\n", -1);
        if (TYPE_DOCKERFILE.equals(normalizedType)) {
            for (String line : lines) {
                collectDockerDeclarations(line, scan.declared);
            }
        }
        for (int i = 0; i < lines.length; i++) {
            scan.inspectLine(lines[i], i + 1);
        }

        TemplateVariableCheckDTO result = new TemplateVariableCheckDTO();
        result.setType(normalizedType);
        result.setIssues(new ArrayList<>(scan.issues.values()));
        result.setUsed(new ArrayList<>(scan.used.values()));
        result.setPassthrough(new ArrayList<>(scan.passthrough));
        result.setSupported(new ArrayList<>(scan.catalog.values()));
        result.setPassed(scan.issues.isEmpty());
        return result;
    }

    /**
     * 收集 Dockerfile 自己用 ARG / ENV 声明的变量名，这些由 Docker 在构建时解析，不算写错。
     */
    private static void collectDockerDeclarations(String line, Set<String> declared) {
        Matcher matcher = DOCKER_DECLARATION.matcher(line);
        if (!matcher.find()) {
            return;
        }
        String[] tokens = matcher.group(2).trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return;
        }
        if (!tokens[0].contains("=")) {
            // ARG NAME 或 ENV NAME value
            declared.add(tokens[0]);
            return;
        }
        // ARG NAME=default 或 ENV A=1 B=2
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                declared.add(token.substring(0, eq));
            }
        }
    }

    /** 一次检查过程中的累积状态 */
    private static final class Scan {
        private final String type;
        private final Map<String, TemplateVariableCheckDTO.Variable> catalog = new LinkedHashMap<>();
        private final Set<String> declared = new LinkedHashSet<>();
        private final Map<String, TemplateVariableCheckDTO.Issue> issues = new LinkedHashMap<>();
        private final Map<String, TemplateVariableCheckDTO.UsedVariable> used = new LinkedHashMap<>();
        private final Set<String> passthrough = new LinkedHashSet<>();

        private Scan(String type) {
            this.type = type;
            for (TemplateVariableCheckDTO.Variable variable : supportedVariables(type)) {
                catalog.put(variable.getName(), variable);
            }
        }

        private void inspectLine(String line, int lineNumber) {
            Matcher placeholder = PLACEHOLDER.matcher(line);
            while (placeholder.find()) {
                inspectPlaceholder(placeholder.group(), placeholder.group(1), lineNumber);
            }

            Matcher unclosed = UNCLOSED.matcher(line);
            if (unclosed.find()) {
                addIssue(unclosed.group().trim(), lineNumber, "缺少右花括号 }，不会被替换", null);
            }

            Matcher other = OTHER_SYNTAX.matcher(line);
            while (other.find()) {
                String name = firstGroup(other);
                if (name == null || !catalog.containsKey(name) || declared.contains(name)) {
                    continue;
                }
                addIssue(other.group(), lineNumber, "这种写法平台不会替换", name);
            }
        }

        private void inspectPlaceholder(String placeholder, String inner, int lineNumber) {
            if (catalog.containsKey(inner)) {
                addUsed(inner, lineNumber);
                return;
            }
            String trimmed = inner.trim();
            if (trimmed.isEmpty()) {
                addIssue(placeholder, lineNumber, "占位符是空的，渲染时会原样保留", null);
                return;
            }
            if (catalog.containsKey(trimmed)) {
                addIssue(placeholder, lineNumber, "占位符里有空格，不会被替换", trimmed);
                return;
            }
            Matcher head = IDENTIFIER_HEAD.matcher(trimmed);
            if (!head.find()) {
                // ${1}、${#arr[@]} 之类的 shell 写法
                passthrough.add(placeholder);
                return;
            }
            String name = head.group();
            boolean plainName = name.length() == trimmed.length();
            if (plainName && declared.contains(name)) {
                passthrough.add(placeholder);
                return;
            }
            String sameIgnoringStyle = matchIgnoringStyle(trimmed);

            if (!plainName) {
                if (sameIgnoringStyle != null) {
                    // ${service-name}、${service.port}
                    addIssue(placeholder, lineNumber, MSG_UNKNOWN, sameIgnoringStyle);
                } else if (catalog.containsKey(name)) {
                    // ${port:-8080}
                    addIssue(placeholder, lineNumber, "带默认值或其他修饰的写法平台不会替换", name);
                } else {
                    // ${JAVA_OPTS:-} 之类的 shell 写法
                    passthrough.add(placeholder);
                }
                return;
            }

            String hint = specialHint(name);
            if (hint != null) {
                addIssue(placeholder, lineNumber, hint, specialSuggestion(name));
                return;
            }
            if (sameIgnoringStyle != null) {
                // ${ServiceName}、${service_name}、${SERVICE_NAME}
                addIssue(placeholder, lineNumber, MSG_UNKNOWN, sameIgnoringStyle);
                return;
            }
            if (UPPER_CASE_NAME.matcher(name).matches()) {
                // ${JAVA_OPTS}、${PATH}：全大写且和平台变量对不上，按环境变量处理
                passthrough.add(placeholder);
                return;
            }
            addIssue(placeholder, lineNumber, MSG_UNKNOWN, suggest(name));
        }

        /**
         * 只在特定位置才有值的变量，给出比「不支持」更具体的说明。
         */
        private String specialHint(String name) {
            if ("baseImage".equals(name)) {
                return "「基础镜像」不会作为变量注入，请在 FROM 后直接写镜像名";
            }
            if (!TYPE_DOCKERFILE.equals(type)) {
                return null;
            }
            if ("tag".equals(name) || "harbor".equals(name) || "workspace".equals(name) || "dockerfilePath".equals(name)) {
                return "只在步骤的「Docker 构建命令」里可用，Dockerfile 内容里不会被替换";
            }
            return null;
        }

        private String specialSuggestion(String name) {
            return "tag".equals(name) ? "imageTag" : null;
        }

        /** 忽略大小写和分隔符后与某个平台变量同名 */
        private String matchIgnoringStyle(String text) {
            String key = normalize(text);
            if (key.isEmpty()) {
                return null;
            }
            for (String candidate : catalog.keySet()) {
                if (normalize(candidate).equals(key)) {
                    return candidate;
                }
            }
            return null;
        }

        /** 按习惯叫法、拼写相近程度猜测想写的变量 */
        private String suggest(String name) {
            String key = normalize(name);
            String aliased = ALIASES.get(key);
            if (aliased != null && catalog.containsKey(aliased)) {
                return aliased;
            }
            int allowed = key.length() >= 6 ? 2 : (key.length() >= 3 ? 1 : 0);
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String candidate : catalog.keySet()) {
                int distance = editDistance(key, normalize(candidate));
                if (distance <= allowed && distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            return best;
        }

        private void addUsed(String name, int lineNumber) {
            TemplateVariableCheckDTO.UsedVariable usedVariable = used.get(name);
            if (usedVariable == null) {
                TemplateVariableCheckDTO.Variable definition = catalog.get(name);
                usedVariable = new TemplateVariableCheckDTO.UsedVariable();
                usedVariable.setName(name);
                usedVariable.setDescription(definition.getDescription());
                usedVariable.setCondition(definition.getCondition());
                used.put(name, usedVariable);
            }
            addLine(usedVariable.getLines(), lineNumber);
        }

        private void addIssue(String placeholder, int lineNumber, String message, String suggestion) {
            TemplateVariableCheckDTO.Issue issue = issues.get(placeholder);
            if (issue == null) {
                issue = new TemplateVariableCheckDTO.Issue();
                issue.setPlaceholder(placeholder);
                issue.setMessage(message);
                issue.setSuggestion(suggestion);
                issues.put(placeholder, issue);
            }
            addLine(issue.getLines(), lineNumber);
        }
    }

    private static void addLine(List<Integer> lines, int lineNumber) {
        if (!lines.contains(lineNumber)) {
            lines.add(lineNumber);
        }
    }

    private static String firstGroup(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (matcher.group(i) != null) {
                return matcher.group(i);
            }
        }
        return null;
    }

    /** 去掉分隔符并转小写：service_name / Service-Name → servicename */
    private static String normalize(String text) {
        return text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 编辑距离，相邻两个字符写反算一次（serviceNmae → serviceName 为 1）。
     */
    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }
}
