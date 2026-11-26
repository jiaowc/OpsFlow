package com.opsflow.spi;

import com.opsflow.dao.model.Env;

/**
 * 普通流水线（不经上线任务）能否直接部署某个环境。
 * <p>核心默认：非生产环境放行；生产环境要求当前用户有 {@link #PROD_DEPLOY_PERMISSION}，
 * Webhook 推送触发没有用户会话，直接放行（开启推送时已校验过开启人的权限）。
 * 商业版可替换为"生产发布必须走上线任务"。</p>
 */
public interface ProdDeployPolicy {

    /** 部署生产环境的权限码（V6 迁移新增，默认只有带 * 的管理员拥有） */
    String PROD_DEPLOY_PERMISSION = "pipeline:run:prod";

    /** 触发方式 */
    enum Trigger {
        /** 手动新建并构建 */
        MANUAL,
        /** 编辑流水线任务（改环境） */
        EDIT,
        /** 手动重新构建已有任务 */
        REBUILD,
        /** 开启 Git 推送自动构建 */
        WEBHOOK_ENABLE,
        /** Git 推送触发构建（无用户会话） */
        WEBHOOK_PUSH
    }

    /**
     * 不允许直接部署时抛出 {@link com.opsflow.common.exception.BusinessException}，消息直接展示给用户
     * （Webhook 推送触发时写进"跳过原因"）。{@code env} 为 null 时放行。
     */
    void checkDirectDeploy(Env env, Trigger trigger);

    /** 供"可构建环境"列表过滤：当前用户能否手动直接部署该环境 */
    boolean canDirectDeploy(Env env);
}
