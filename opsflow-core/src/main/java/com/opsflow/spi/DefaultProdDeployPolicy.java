package com.opsflow.spi;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.model.Env;
import com.opsflow.service.PermissionService;
import com.opsflow.web.security.SessionPermissionResolver;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;

/**
 * 社区版：普通流水线可以直接部署生产环境，但当前用户要有 {@link #PROD_DEPLOY_PERMISSION}。
 * <p>Webhook 推送触发没有用户会话，直接放行；拿不到会话的其他调用（如后台线程）对生产环境一律拒绝。</p>
 */
public class DefaultProdDeployPolicy implements ProdDeployPolicy {

    static final String NO_PERMISSION_MESSAGE = "部署生产环境需要「部署生产环境」权限（" + PROD_DEPLOY_PERMISSION + "）";

    private final SessionPermissionResolver sessionPermissionResolver;
    private final PermissionService permissionService;

    public DefaultProdDeployPolicy(SessionPermissionResolver sessionPermissionResolver,
                                   PermissionService permissionService) {
        this.sessionPermissionResolver = sessionPermissionResolver;
        this.permissionService = permissionService;
    }

    @Override
    public void checkDirectDeploy(Env env, Trigger trigger) {
        if (env == null || !env.isProdEnv() || trigger == Trigger.WEBHOOK_PUSH) {
            return;
        }
        if (!currentUserCanDeployProd()) {
            throw new BusinessException(NO_PERMISSION_MESSAGE);
        }
    }

    @Override
    public boolean canDirectDeploy(Env env) {
        try {
            checkDirectDeploy(env, Trigger.MANUAL);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /** 当前请求的用户是否有生产部署权限；没有请求上下文时视为没有 */
    protected boolean currentUserCanDeployProd() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes)) {
            return false;
        }
        HttpSession session = ((ServletRequestAttributes) attrs).getRequest().getSession(false);
        List<String> permissions = session != null
                ? sessionPermissionResolver.resolvePermissions(session) : Collections.emptyList();
        return permissionService.hasAnyPermission(permissions, PROD_DEPLOY_PERMISSION);
    }
}
