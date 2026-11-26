package com.opsflow.spi;

import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.Env;
import com.opsflow.dao.model.Service;

/**
 * 一次流水线通知涉及的对象，由核心查好后交给 {@link JobNotifyExtension}，避免各实现重复查库。
 */
public final class JobNotifyContext {

    private final BuildJob job;
    private final Service service;
    private final Env env;
    private final Long projectId;
    /** 成功 true、失败 false；{@code notify} 步骤的自定义消息为 null */
    private final Boolean success;

    public JobNotifyContext(BuildJob job, Service service, Env env, Long projectId, Boolean success) {
        this.job = job;
        this.service = service;
        this.env = env;
        this.projectId = projectId;
        this.success = success;
    }

    public BuildJob getJob() {
        return job;
    }

    public Service getService() {
        return service;
    }

    public Env getEnv() {
        return env;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Boolean getSuccess() {
        return success;
    }

    /** 环境类型（prod / nonprod），环境不存在时为 null */
    public String getEnvType() {
        return env != null ? env.getEnvType() : null;
    }
}
