package com.opsflow.spi;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open Core 边界：社区版（opsflow-core、opsflow-app）的源码里不能有商业代码。
 * <p>商业功能都在 opsflow-ee，核心只通过 {@code com.opsflow.spi} 的扩展点接入。这里扫描两个模块 src/main 下的全部文本文件
 * （java、html、js、css、json、yml、sql 等，含注释），不允许出现 {@link #FORBIDDEN} 里的写法；
 * 唯一例外是 BuildJob 的 {@code deployTaskId} 字段及其 getter / setter（{@code DeployTaskId}）。</p>
 * <p>匹配区分大小写：表名、配置键、用户来源这类小写写法（如 {@code deploy_task}、{@code feishu}）按拆分计划保留。</p>
 */
class CoreDoesNotDependOnCommercialCodeTest {

    /** 社区版的两个模块（测试在 opsflow-core 目录下运行） */
    private static final List<Path> COMMUNITY_SOURCES = Arrays.asList(
            Paths.get("src/main"), Paths.get("../opsflow-app/src/main"));

    private static final Set<String> TEXT_EXTENSIONS = new LinkedHashSet<>(Arrays.asList(
            "java", "html", "js", "css", "json", "yml", "yaml", "sql", "properties", "xml", "md", "txt", "factories"));

    /** 社区版源码里不能出现的写法 */
    static final List<String> FORBIDDEN = Arrays.asList(
            "com.opsflow.ee", "LicenseService", "requireFeature", "Feishu", "ApprovalFlow", "DeployTask");

    /** 允许的例外：BuildJob.deployTaskId 及其 getter / setter */
    private static final Pattern ALLOWED = Pattern.compile("deployTaskId|DeployTaskId");

    private static List<Path> communityTextFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : COMMUNITY_SOURCES) {
            assertTrue(Files.isDirectory(root), "找不到源码目录: " + root.toAbsolutePath().normalize());
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> TEXT_EXTENSIONS.contains(extension(p)))
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    @Test
    void communitySourcesContainNoCommercialCode() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : communityTextFiles()) {
            String text = ALLOWED.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).replaceAll("");
            for (String word : FORBIDDEN) {
                Matcher m = Pattern.compile(Pattern.quote(word)).matcher(text);
                if (m.find()) {
                    int line = text.substring(0, m.start()).split("\n", -1).length;
                    violations.add(file.normalize() + ":" + line + " -> " + word);
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "社区版源码里出现了商业代码，请移到 opsflow-ee 并通过 com.opsflow.spi 的扩展点接入：\n"
                        + String.join("\n", violations));
    }

    @Test
    void scanCoversBothModules() throws IOException {
        List<Path> files = communityTextFiles();
        assertTrue(files.stream().anyMatch(p -> p.toString().replace('\\', '/').contains("opsflow-app/src/main")),
                "没有扫到 opsflow-app 的源码");
        assertTrue(files.stream().anyMatch(p -> p.toString().endsWith("dashboard.html")), "没有扫到静态页面");
        assertTrue(files.stream().anyMatch(p -> p.toString().endsWith(".sql")), "没有扫到迁移脚本");
    }

    /** 阶段 4 已搬进 opsflow-ee 的类：核心里不能再出现同名类 */
    private static final Set<String> MOVED_TO_EE = new LinkedHashSet<>(Arrays.asList(
            "LicenseServiceImpl", "LicenseController", "LicenseStatusDTO", "LicenseUiExtension",
            "LicenseCrypto", "LicenseDocument", "LicensePayload", "LicenseEditionInfo",
            "BrandingController", "BrandingService", "BrandingServiceImpl", "BrandingUiExtension",
            "BrandingWebSecurityExtension",
            "AuditLogController", "AuditUiExtension",
            "NotifyChannelController", "NotifyChannelMapper", "NotifyChannel", "NotifyChannelDTO",
            "FeishuBotSender", "NotifySender", "ChannelJobNotifyExtension", "IntegrationUiExtension",
            // 审批和上线任务
            "ApprovalFlowController", "ApprovalService", "ApprovalServiceImpl",
            "ApprovalNotifyDispatchService", "ApprovalNotifyDispatchServiceImpl",
            "ApprovalFlowMapper", "ApprovalRecordMapper", "ApprovalFlow", "ApprovalRecord",
            "ApprovalFlowDTO", "ApprovalRecordDTO", "ApprovalActionRequest",
            "DeployTaskController", "DeployTaskService", "DeployTaskServiceImpl",
            "DeployTaskEditService", "DeployTaskEditServiceImpl", "DeployTaskCdService", "DeployTaskCdServiceImpl",
            "DeployTaskOperator", "DeployTaskMapper", "DeployTask", "DeployTaskDTO",
            "DeployModuleDetailDTO", "DeployModuleItemDTO", "DeployModuleJson",
            "FeishuApprovalNotifyService", "FeishuApprovalNotifyServiceImpl",
            "ApprovalProdDeployPolicy", "CdJobFactory", "CdJobRetryService", "CdJobScheduler",
            "DeployModuleImageResolver", "DeployTaskAccessGuard", "DeployTaskCommandService",
            "DeployTaskJobListener", "DeployTaskQueryService", "DeployTaskReferenceChecker",
            "DeployTaskStatisticsContributor", "DeployTaskViewAssembler", "DeployUiExtension",
            // 飞书
            "FeishuCallbackController", "FeishuAuthController",
            "FeishuUserResolveService", "FeishuUserResolveServiceImpl", "FeishuUserAccountExtension",
            "UserFeishuMapper", "UserFeishu", "FeishuCallbackSecurity", "FeishuClient", "FeishuConfig",
            "FeishuJson", "FeishuSSO", "FeishuSsoConfig", "FeishuSystemConfigExtension",
            "FeishuWebSecurityExtension", "FeishuUiExtension",
            // 授权接口、功能码，以及过渡期的社区版默认实现（已删除）
            "LicenseService", "LicenseFeatures", "CommunityLicenseService", "CommunityLicenseConfiguration"));

    @Test
    void classesMovedToEeAreGoneFromCore() throws IOException {
        List<String> back = communityTextFiles().stream()
                .filter(p -> p.toString().endsWith(".java"))
                .map(p -> p.getFileName().toString().replace(".java", ""))
                .filter(MOVED_TO_EE::contains)
                .collect(Collectors.toList());
        assertTrue(back.isEmpty(), "这些类已搬到 opsflow-ee，社区版里不应再有: " + back);
    }

    /** 核心静态页面和脚本（相对 src/main/resources/static） */
    private static final List<String> CORE_STATIC_FILES = Arrays.asList(
            "dashboard.html", "js/core.js", "js/ui-extensions.js", "js/i18n/i18n.js", "css/style.css");

    /** 审计页面已改为 AuditUiExtension 动态加载，核心页面里不能再写死它的菜单、区块、路由、脚本、样式和文案 */
    private static final List<Pattern> AUDIT_PAGE_MARKERS = Arrays.asList(
            Pattern.compile("data-section=\\?\"audit\\?\""),
            Pattern.compile("audit-section"),
            Pattern.compile("['\"]/audit['\"]"),
            Pattern.compile("audit\\.js"),
            Pattern.compile("loadAudit"),
            Pattern.compile("menu\\.audit"),
            Pattern.compile("license\\.feature\\.audit"),
            Pattern.compile("\\.audit-"),
            Pattern.compile("auditEnabled"),
            Pattern.compile("\\baudit\\s*:"));

    /** License 页面已改为 ee 的 LicenseUiExtension 提供；核心也不再读 /api/license/status */
    private static final List<Pattern> LICENSE_PAGE_MARKERS = Arrays.asList(
            Pattern.compile("license-config"),
            Pattern.compile("menu\\.license"),
            Pattern.compile("license\\.js"),
            Pattern.compile("/api/license"),
            Pattern.compile("enforcementEnabled"),
            Pattern.compile("['\"]/license['\"]"));

    @Test
    void corePagesDoNotHardcodeTheLicensePage() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> violations = new ArrayList<>();
        for (String file : CORE_STATIC_FILES) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (Pattern marker : LICENSE_PAGE_MARKERS) {
                if (marker.matcher(text).find()) {
                    violations.add(file + " -> " + marker.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了 License 页面的内容：\n" + String.join("\n", violations));
    }

    /** 集成与通知页已改为 ee 的 IntegrationUiExtension 提供 */
    private static final List<Pattern> INTEGRATION_PAGE_MARKERS = Arrays.asList(
            Pattern.compile("integration-config"),
            Pattern.compile("notify-channel"),
            Pattern.compile("NotifyChannel"),
            Pattern.compile("/integrations"),
            Pattern.compile("menu\\.integrations"),
            Pattern.compile("showSSOTab"),
            Pattern.compile("sso-tab"),
            Pattern.compile("notify-section"));

    @Test
    void corePagesDoNotHardcodeTheIntegrationPage() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> files = new ArrayList<>(CORE_STATIC_FILES);
        files.add("js/dashboard.js");
        files.add("js/modules/system.js");
        List<String> violations = new ArrayList<>();
        for (String file : files) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (Pattern marker : INTEGRATION_PAGE_MARKERS) {
                if (marker.matcher(text).find()) {
                    violations.add(file + " -> " + marker.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了集成与通知页的内容：\n" + String.join("\n", violations));
    }

    /** 企业品牌已改为 ee 的 BrandingUiExtension 提供；核心只保留 data-brand-logo / data-brand-text 这两个通用位置 */
    private static final List<Pattern> BRANDING_PAGE_MARKERS = Arrays.asList(
            Pattern.compile("branding"),
            Pattern.compile("Branding"));

    @Test
    void corePagesDoNotHardcodeBranding() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> files = new ArrayList<>(CORE_STATIC_FILES);
        files.add("index.html");
        files.add("js/login.js");
        List<String> violations = new ArrayList<>();
        for (String file : files) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (Pattern marker : BRANDING_PAGE_MARKERS) {
                if (marker.matcher(text).find()) {
                    violations.add(file + " -> " + marker.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了企业品牌的内容：\n" + String.join("\n", violations));
    }

    @Test
    void corePagesDoNotHardcodeTheAuditPage() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> violations = new ArrayList<>();
        for (String file : CORE_STATIC_FILES) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (Pattern marker : AUDIT_PAGE_MARKERS) {
                if (marker.matcher(text).find()) {
                    violations.add(file + " -> " + marker.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了审计页面的内容，应放到 static/ext/audit/ 由 AuditUiExtension 声明：\n"
                + String.join("\n", violations));
    }

    /** 上线任务和审批流程页已改为 ee 的 DeployUiExtension 提供 */
    private static final List<Pattern> DEPLOY_PAGE_MARKERS = Arrays.asList(
            Pattern.compile("tasks-section"),
            Pattern.compile("approval-section"),
            Pattern.compile("loadTasks"),
            Pattern.compile("loadApprovalFlows"),
            Pattern.compile("menu\\.tasks"),
            Pattern.compile("menu\\.approval"),
            Pattern.compile("deploy_approval"),
            Pattern.compile("modules/tasks\\.js"),
            Pattern.compile("modules/approval\\.js"));

    @Test
    void corePagesDoNotHardcodeTheDeployPages() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> files = new ArrayList<>(CORE_STATIC_FILES);
        files.add("js/dashboard.js");
        files.add("js/modules/statistics.js");
        files.add("js/modules/pipeline.js");
        files.add("js/modules/inbox.js");
        List<String> violations = new ArrayList<>();
        for (String file : files) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            for (Pattern marker : DEPLOY_PAGE_MARKERS) {
                if (marker.matcher(text).find()) {
                    violations.add(file + " -> " + marker.pattern());
                }
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了上线任务或审批流程页的内容：\n" + String.join("\n", violations));
        assertTrue(!Files.exists(root.resolve("js/modules/tasks.js")) && !Files.exists(root.resolve("js/modules/approval.js")),
                "tasks.js / approval.js 已搬到 opsflow-ee 的 static/ext/deploy/");
    }

    /** 飞书登录按钮和用户表单的飞书字段已改为 ee 的 FeishuUiExtension 提供 */
    @Test
    void corePagesDoNotHardcodeFeishu() throws IOException {
        Path root = Paths.get("src/main/resources/static");
        List<String> files = new ArrayList<>(CORE_STATIC_FILES);
        files.add("index.html");
        files.add("js/login.js");
        files.add("js/dashboard.js");
        files.add("js/modules/users.js");
        files.add("js/modules/system.js");
        Pattern marker = Pattern.compile("(?i)feishu|飞书");
        List<String> violations = new ArrayList<>();
        for (String file : files) {
            String text = new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
            if (marker.matcher(text).find()) {
                violations.add(file);
            }
        }
        assertTrue(violations.isEmpty(), "核心页面写死了飞书相关内容：\n" + String.join("\n", violations));
    }
}
