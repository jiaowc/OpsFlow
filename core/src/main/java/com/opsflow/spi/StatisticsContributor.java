package com.opsflow.spi;

import com.opsflow.api.dto.StatisticsOverviewDTO;

import java.util.Set;

/**
 * 往工作台统计里追加数据。可以有多个实现，核心在自己的统计都填好之后逐个调用，单个实现失败只记日志。
 */
public interface StatisticsContributor {

    /**
     * @param overview      核心已填好的统计，实现可以修改其中的字段或列表
     * @param username      当前用户名，可能为空
     * @param allowedEnvIds 当前用户可见的环境；null 表示不限
     */
    void contribute(StatisticsOverviewDTO overview, String username, Set<Long> allowedEnvIds);
}
