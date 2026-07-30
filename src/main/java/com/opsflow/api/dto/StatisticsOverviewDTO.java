package com.opsflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页统计概览
 */
@Data
public class StatisticsOverviewDTO {

    /** 首屏行动指标 */
    private ActionSummary action = new ActionSummary();

    /** 近 7 日构建趋势 */
    private List<DayTrend> buildTrend = new ArrayList<>();

    /** 上线任务漏斗 */
    private DeployFunnel deployFunnel = new DeployFunnel();

    /** 运行中的流水线 */
    private List<RecentItem> runningJobs = new ArrayList<>();

    /** 最近失败（流水线 + 上线任务） */
    private List<RecentItem> recentFailures = new ArrayList<>();

    /** 可访问的流水线视图快捷入口 */
    private List<ViewShortcut> views = new ArrayList<>();

    @Data
    public static class ActionSummary {
        private long pendingApprovals;
        private long unreadInbox;
        private long runningJobs;
        private long todayBuilds;
        private long todaySuccess;
        private long todayFailed;
        /** 0-100，今日无构建时为 null */
        private Double todaySuccessRate;
        private long offlineNodes;
        private long totalNodes;
    }

    @Data
    public static class DayTrend {
        private String date;
        private long success;
        private long failed;
        private long total;
    }

    @Data
    public static class DeployFunnel {
        private long pendingApproval;
        private long approvedPending;
        private long deploying;
        private long success;
        private long failed;
    }

    @Data
    public static class RecentItem {
        private String type; // job | deploy_task
        private Long id;
        private String title;
        private String meta;
        private String status;
        private String timeText;
        private String linkSection; // pipeline | tasks
    }

    @Data
    public static class ViewShortcut {
        private Long id;
        private String name;
        private Long envId;
        private String envName;
    }
}
