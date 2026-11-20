package com.opsflow.web.controller;

import com.opsflow.api.dto.StatisticsOverviewDTO;
import com.opsflow.service.StatisticsService;
import com.opsflow.web.security.RequiresPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页统计概览
 */
@RestController
@RequestMapping("/api/statistics")
public class StatisticsController {

    @Autowired
    private StatisticsService statisticsService;

    @RequiresPermission({"statistics:view", "pipeline:view"})
    @GetMapping("/overview")
    public StatisticsOverviewDTO overview() {
        return statisticsService.getOverview();
    }
}
