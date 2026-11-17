package com.opsflow.common.constant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployModesTest {

    @Test
    void unknownOrMissingModeFallsBackToSerial() {
        assertEquals(DeployModes.SERIAL, DeployModes.normalize(null));
        assertEquals(DeployModes.SERIAL, DeployModes.normalize("  "));
        assertEquals(DeployModes.SERIAL, DeployModes.normalize("canary"));
        assertEquals(DeployModes.PARALLEL, DeployModes.normalize(" Parallel "));
        assertTrue(DeployModes.isParallel("PARALLEL"));
        assertFalse(DeployModes.isParallel("serial"));
    }

    @Test
    void serialAlwaysRunsOneAtATime() {
        assertEquals(1, DeployModes.resolveParallelism(DeployModes.SERIAL, 10));
        assertEquals(1, DeployModes.resolveParallelism(null, null));
    }

    @Test
    void parallelismIsDefaultedAndCapped() {
        assertEquals(DeployModes.DEFAULT_PARALLELISM, DeployModes.resolveParallelism(DeployModes.PARALLEL, null));
        assertEquals(DeployModes.DEFAULT_PARALLELISM, DeployModes.resolveParallelism(DeployModes.PARALLEL, 0));
        assertEquals(DeployModes.DEFAULT_PARALLELISM, DeployModes.resolveParallelism(DeployModes.PARALLEL, -3));
        assertEquals(5, DeployModes.resolveParallelism(DeployModes.PARALLEL, 5));
        assertEquals(DeployModes.MAX_PARALLELISM, DeployModes.resolveParallelism(DeployModes.PARALLEL, 500));
    }

    @Test
    void pipelineTypeAliasesAreNormalized() {
        assertEquals(PipelineTypes.CICD, PipelineTypes.normalize("CI/CD"));
        assertEquals(PipelineTypes.CICD, PipelineTypes.normalize(" ci-cd "));
        assertEquals(PipelineTypes.CICD, PipelineTypes.normalize("ci_cd"));
        assertEquals(PipelineTypes.CD, PipelineTypes.normalize(" CD "));
        assertNull(PipelineTypes.normalize(null));
        assertEquals("deploy", PipelineTypes.normalize("Deploy"), "不认识的类型只做小写，不猜测");
    }

    @Test
    void validityIsStrictAboutCanonicalNames() {
        assertTrue(PipelineTypes.isValid("ci"));
        assertTrue(PipelineTypes.isValid("cd"));
        assertTrue(PipelineTypes.isValid("cicd"));
        assertFalse(PipelineTypes.isValid("CI"), "isValid 不做归一化，调用方需先 normalize");
        assertFalse(PipelineTypes.isValid(null));
    }

    @Test
    void onlyPipelinesThatDeployImagesSupportRollback() {
        assertTrue(PipelineTypes.supportsImageRollback("cd"));
        assertTrue(PipelineTypes.supportsImageRollback("CI/CD"));
        assertFalse(PipelineTypes.supportsImageRollback("ci"));
        assertFalse(PipelineTypes.supportsImageRollback(null));
    }

    @Test
    void displayNames() {
        assertEquals("CI", PipelineTypes.displayName("ci"));
        assertEquals("CD", PipelineTypes.displayName("cd"));
        assertEquals("CI/CD", PipelineTypes.displayName("ci-cd"));
        assertEquals("custom", PipelineTypes.displayName("custom"));
        assertEquals("-", PipelineTypes.displayName(null));
    }
}
