package com.opsflow.spi;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.model.Env;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.opsflow.spi.ProdDeployTestSupport.communityPolicy;
import static com.opsflow.spi.ProdDeployTestSupport.env;
import static com.opsflow.spi.ProdDeployTestSupport.loginWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区版生产部署策略：普通流水线可以直接部署生产，但要有 pipeline:run:prod；非生产环境不受影响。
 */
class DefaultProdDeployPolicyTest {

    private final DefaultProdDeployPolicy policy = communityPolicy();
    private final Env prod = env(2L, "prod", "prod");
    private final Env test = env(1L, "test", "nonprod");

    @AfterEach
    void tearDown() {
        ProdDeployTestSupport.logout();
    }

    @Test
    void nonProdIsAlwaysAllowed() {
        for (ProdDeployPolicy.Trigger trigger : ProdDeployPolicy.Trigger.values()) {
            assertDoesNotThrow(() -> policy.checkDirectDeploy(test, trigger));
        }
        assertDoesNotThrow(() -> policy.checkDirectDeploy(null, ProdDeployPolicy.Trigger.MANUAL));
        assertTrue(policy.canDirectDeploy(test), "没有登录上下文时非生产也可列出");
    }

    @Test
    void prodNeedsTheDedicatedPermission() {
        loginWith("pipeline:run", "pipeline:edit");
        for (ProdDeployPolicy.Trigger trigger : new ProdDeployPolicy.Trigger[]{
                ProdDeployPolicy.Trigger.MANUAL, ProdDeployPolicy.Trigger.EDIT,
                ProdDeployPolicy.Trigger.REBUILD, ProdDeployPolicy.Trigger.WEBHOOK_ENABLE}) {
            BusinessException e = assertThrows(BusinessException.class, () -> policy.checkDirectDeploy(prod, trigger));
            assertEquals(DefaultProdDeployPolicy.NO_PERMISSION_MESSAGE, e.getMessage());
        }
        assertFalse(policy.canDirectDeploy(prod), "只有 pipeline:run 不能在环境列表里看到生产环境");

        loginWith("pipeline:run", ProdDeployPolicy.PROD_DEPLOY_PERMISSION);
        assertDoesNotThrow(() -> policy.checkDirectDeploy(prod, ProdDeployPolicy.Trigger.MANUAL));
        assertTrue(policy.canDirectDeploy(prod));

        loginWith("*");
        assertDoesNotThrow(() -> policy.checkDirectDeploy(prod, ProdDeployPolicy.Trigger.EDIT), "管理员（*）自动拥有");
    }

    @Test
    void webhookPushHasNoSessionAndIsAllowed() {
        assertDoesNotThrow(() -> policy.checkDirectDeploy(prod, ProdDeployPolicy.Trigger.WEBHOOK_PUSH));
    }

    @Test
    void prodWithoutRequestContextIsRejected() {
        assertThrows(BusinessException.class, () -> policy.checkDirectDeploy(prod, ProdDeployPolicy.Trigger.MANUAL));
    }

    @Test
    void envTypeDecidesProdNotTheName() {
        loginWith("pipeline:run");
        Env namedProdButNonProd = env(3L, "prod-like", "nonprod");
        assertDoesNotThrow(() -> policy.checkDirectDeploy(namedProdButNonProd, ProdDeployPolicy.Trigger.MANUAL));
    }
}
