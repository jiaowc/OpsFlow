package com.opsflow.integration.pipeline.step.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckDeployStepExecutorTest {

    @Test
    void rolloutTimeoutIsCappedBelowStepTimeout() {
        // 之前的默认配置：步骤 120 秒，rollout 300 秒 —— rollout 必须先结束，留出诊断时间
        assertEquals(90, CheckDeployStepExecutor.effectiveRolloutTimeout(300, 120));
    }

    @Test
    void rolloutTimeoutIsKeptWhenStepTimeoutIsLargeEnough() {
        assertEquals(300, CheckDeployStepExecutor.effectiveRolloutTimeout(300, 330));
        assertEquals(300, CheckDeployStepExecutor.effectiveRolloutTimeout(300, 600));
    }

    @Test
    void rolloutTimeoutIsUnchangedWhenStepTimeoutUnknown() {
        assertEquals(300, CheckDeployStepExecutor.effectiveRolloutTimeout(300, null));
        assertEquals(300, CheckDeployStepExecutor.effectiveRolloutTimeout(300, 0));
    }

    @Test
    void rolloutTimeoutNeverDropsBelowMinimum() {
        assertEquals(10, CheckDeployStepExecutor.effectiveRolloutTimeout(300, 20));
        assertEquals(10, CheckDeployStepExecutor.effectiveRolloutTimeout(0, 600));
    }

    @Test
    void diagnosticsScriptQuotesNamesAndOnlyReads() {
        String script = CheckDeployStepExecutor.buildDiagnosticsScript("dev", "ruoyi-vue");
        assertTrue(script.contains("-n 'dev'"));
        assertTrue(script.contains("get deployment 'ruoyi-vue'"));
        assertTrue(script.contains("--request-timeout=5s"));
        for (String verb : new String[]{" apply ", " delete ", " patch ", " scale ", " edit ", " rollout undo", " exec "}) {
            assertFalse(script.contains(verb), "诊断脚本不应包含变更命令:" + verb);
        }
    }

    @Test
    void diagnosticsScriptEscapesSingleQuotes() {
        String script = CheckDeployStepExecutor.buildDiagnosticsScript("de'v", "a'; rm -rf ~; '");
        assertTrue(script.contains("-n 'de'\\''v'"));
        assertTrue(script.contains("get deployment 'a'\\''; rm -rf ~; '\\'''"));
    }
}
