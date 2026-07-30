package com.opsflow.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 控制台各模块页面路由（统一转发到 dashboard.html，由前端按路径展示对应模块）
 */
@Controller
public class DashboardController {

    /** 统一转发目标：单页 dashboard，由前端路由切换模块 */
    private static final String DASHBOARD = "forward:/dashboard.html";

    /** 统计概览模块 */
    @GetMapping("/statistics")
    public String statistics() {
        return DASHBOARD;
    }

    /** 流水线运行记录模块 */
    @GetMapping("/pipeline")
    public String pipeline() {
        return DASHBOARD;
    }

    /** 上线任务模块 */
    @GetMapping("/tasks")
    public String tasks() {
        return DASHBOARD;
    }

    /** 服务管理模块 */
    @GetMapping("/services")
    public String services() {
        return DASHBOARD;
    }

    /** 环境管理模块 */
    @GetMapping("/environments")
    public String environments() {
        return DASHBOARD;
    }

    /** 集群管理模块 */
    @GetMapping("/clusters")
    public String clusters() {
        return DASHBOARD;
    }

    /** 审批流配置模块 */
    @GetMapping("/approval")
    public String approval() {
        return DASHBOARD;
    }

    /** 构建/部署节点模块 */
    @GetMapping("/nodes")
    public String nodes() {
        return DASHBOARD;
    }

    /** 用户与权限模块 */
    @GetMapping("/users")
    public String users() {
        return DASHBOARD;
    }

    /** 流水线模版与步骤定义配置 */
    @GetMapping("/pipeline-config")
    public String pipelineConfig() {
        return DASHBOARD;
    }

    /** 凭证管理模块 */
    @GetMapping("/credentials")
    public String credentials() {
        return DASHBOARD;
    }

    /** 第三方集成（飞书等）配置模块 */
    @GetMapping("/integrations")
    public String integrations() {
        return DASHBOARD;
    }

    /** License 导入与状态 */
    @GetMapping("/license")
    public String license() {
        return DASHBOARD;
    }

    /** 兼容旧入口，转发到流水线配置 */
    @GetMapping("/system")
    public String system() {
        return "redirect:/pipeline-config";
    }
}
