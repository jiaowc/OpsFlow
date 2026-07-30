package com.opsflow.common.constant;

/**
 * 上线任务多模块部署策略。
 */
public final class DeployModes {

    /** 串行：上一模块成功后再启下一模块（默认） */
    public static final String SERIAL = "serial";

    /** 有限并行：同时最多 deploy_parallelism 个模块 */
    public static final String PARALLEL = "parallel";

    public static final int DEFAULT_PARALLELISM = 3;
    public static final int MAX_PARALLELISM = 20;

    private DeployModes() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return SERIAL;
        }
        String m = mode.trim().toLowerCase();
        if (PARALLEL.equals(m)) {
            return PARALLEL;
        }
        return SERIAL;
    }

    public static boolean isParallel(String mode) {
        return PARALLEL.equals(normalize(mode));
    }

    /**
     * 解析并发数：串行恒为 1；并行落在 [1, MAX]；非法时用默认 3。
     */
    public static int resolveParallelism(String mode, Integer parallelism) {
        if (!isParallel(mode)) {
            return 1;
        }
        if (parallelism == null || parallelism < 1) {
            return DEFAULT_PARALLELISM;
        }
        return Math.min(parallelism, MAX_PARALLELISM);
    }
}
