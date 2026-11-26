package com.opsflow.config;

import com.opsflow.service.PipelineRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动完成后回收因服务重启而中断的流水线任务。
 */
@Slf4j
@Component
public class PipelineStartupRecovery implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private PipelineRunService pipelineRunService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            pipelineRunService.recoverInterruptedRunningJobs();
        } catch (Exception e) {
            log.error("启动时回收中断流水线任务失败", e);
        }
    }
}
