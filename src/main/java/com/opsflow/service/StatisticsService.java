package com.opsflow.service;

import com.opsflow.api.dto.StatisticsOverviewDTO;

public interface StatisticsService {

    /**
     * 首页概览：行动指标 + 趋势 + 漏斗 + 最近失败
     */
    StatisticsOverviewDTO getOverview();
}
