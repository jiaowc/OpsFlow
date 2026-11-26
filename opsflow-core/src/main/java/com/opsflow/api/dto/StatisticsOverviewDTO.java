package com.opsflow.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页统计概览
 */
@Data
public class StatisticsOverviewDTO {

    /** 运行中、最近失败列表的条数上限 */
    public static final int RECENT_LIMIT = 8;

    /** 首屏行动指标 */
    private ActionSummary action = new ActionSummary();

    /** 近 7 日构建趋势 */
    private List<DayTrend> buildTrend = new ArrayList<>();

    /** 发布漏斗；只有扩展填写时才有值，为 null 时页面不显示 */
    private DeployFunnel deployFunnel;

    /** 运行中的流水线 */
    private List<RecentItem> runningJobs = new ArrayList<>();

    /** 最近失败（流水线，以及扩展追加的条目） */
    private List<RecentItem> recentFailures = new ArrayList<>();

    /** 可访问的流水线视图快捷入口 */
    private List<ViewShortcut> views = new ArrayList<>();

    @Data
    public static class ActionSummary {
        /** 待审批数；只有扩展填写时才有值，为 null 时页面不显示 */
        private Long pendingApprovals;
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
        private String type; // job，或扩展自定义的类型
        private Long id;
        private String title;
        private String meta;
        private String status;
        private String timeText;
        private String linkSection; // 点击后跳转的页面 section id
    }

    @Data
    public static class ViewShortcut {
        private Long id;
        private String name;
        private Long envId;
        private String envName;
    }
}
