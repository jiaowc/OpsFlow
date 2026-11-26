package com.opsflow.web.controller;

import com.opsflow.api.dto.BuildNodeDTO;
import com.opsflow.api.dto.ClusterDTO;
import com.opsflow.api.dto.ComponentDTO;
import com.opsflow.api.dto.EnvDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.mapper.ClusterMapper;
import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.mapper.EnvMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.dao.model.Cluster;
import com.opsflow.dao.model.Component;
import com.opsflow.dao.model.Env;
import com.opsflow.support.MockInjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 集群、环境、节点、组件表单的必填项（以前端星号为准） */
class InfraControllersRequiredTest {

    private static String error(Runnable call) {
        return assertThrows(BusinessException.class, call::run).getMessage();
    }

    // ---------- 集群 ----------

    @Test
    void clusterCreateRejectsBlankNameAndSavesTrimmed() {
        ClusterController controller = MockInjector.inject(new ClusterController());
        ClusterMapper mapper = MockInjector.field(controller, "clusterMapper");
        ClusterDTO blank = new ClusterDTO();
        blank.setName(" ");
        assertEquals("集群名称不能为空", error(() -> controller.createCluster(blank)));

        ClusterDTO ok = new ClusterDTO();
        ok.setName(" k8s-prod ");
        controller.createCluster(ok);
        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(mapper).insert(captor.capture());
        assertEquals("k8s-prod", captor.getValue().getName());
    }

    @Test
    void clusterUpdateRejectsBlankNameButKeepsNameWhenAbsent() {
        ClusterController controller = MockInjector.inject(new ClusterController());
        ClusterMapper mapper = MockInjector.field(controller, "clusterMapper");
        Cluster stored = new Cluster();
        stored.setId(3L);
        stored.setName("k8s-prod");
        when(mapper.selectById(3L)).thenReturn(stored);

        ClusterDTO blank = new ClusterDTO();
        blank.setName("");
        assertEquals("集群名称不能为空", error(() -> controller.updateCluster(3L, blank)));
        verify(mapper, never()).updateById(any(Cluster.class));

        ClusterDTO partial = new ClusterDTO();
        partial.setDescription("新描述");
        ClusterDTO result = controller.updateCluster(3L, partial);
        assertEquals("k8s-prod", result.getName(), "没带名称时保持原值（以前返回值里名称是 null）");

        ClusterDTO renamed = new ClusterDTO();
        renamed.setName(" k8s-b ");
        assertEquals("k8s-b", controller.updateCluster(3L, renamed).getName());
    }

    // ---------- 环境 ----------

    @Test
    void envCreateRejectsBlankTypeAndTrimsName() {
        EnvController controller = MockInjector.inject(new EnvController());
        EnvMapper mapper = MockInjector.field(controller, "envMapper");
        EnvDTO blankType = new EnvDTO();
        blankType.setName("prod");
        blankType.setEnvType("  ");
        assertEquals("环境类型不能为空", error(() -> controller.createEnv(blankType)));
        EnvDTO blankName = new EnvDTO();
        blankName.setName(" ");
        blankName.setEnvType("prod");
        assertEquals("环境名称不能为空", error(() -> controller.createEnv(blankName)));
        verify(mapper, never()).insert(any(Env.class));

        EnvDTO ok = new EnvDTO();
        ok.setName(" staging ");
        ok.setEnvType("nonprod");
        controller.createEnv(ok);
        ArgumentCaptor<Env> captor = ArgumentCaptor.forClass(Env.class);
        verify(mapper).insert(captor.capture());
        assertEquals("staging", captor.getValue().getName());
        assertEquals(Env.TYPE_NON_PROD, captor.getValue().getEnvType());
    }

    @Test
    void envCreateWithoutTypeInfersFromName() {
        EnvController controller = MockInjector.inject(new EnvController());
        EnvMapper mapper = MockInjector.field(controller, "envMapper");
        EnvDTO prod = new EnvDTO();
        prod.setName("prod");
        EnvDTO dev = new EnvDTO();
        dev.setName("dev");
        controller.createEnv(prod);
        controller.createEnv(dev);
        ArgumentCaptor<Env> captor = ArgumentCaptor.forClass(Env.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals(Env.TYPE_PROD, captor.getAllValues().get(0).getEnvType(), "没带类型按名称推断（与以前一致）");
        assertEquals(Env.TYPE_NON_PROD, captor.getAllValues().get(1).getEnvType());
    }

    @Test
    void envUpdateKeepsTypeWhenAbsentAndRejectsBlankType() {
        EnvController controller = MockInjector.inject(new EnvController());
        EnvMapper mapper = MockInjector.field(controller, "envMapper");
        Env stored = new Env();
        stored.setId(9L);
        stored.setName("online");
        stored.setEnvType(Env.TYPE_PROD);
        when(mapper.selectById(9L)).thenReturn(stored);

        EnvDTO blankType = new EnvDTO();
        blankType.setName("online");
        blankType.setEnvType(" ");
        assertEquals("环境类型不能为空", error(() -> controller.updateEnv(9L, blankType)));

        // 以前不带类型会被清空后按名称重新推断：名称不是 prod 的生产环境会被改成非生产
        EnvDTO noType = new EnvDTO();
        noType.setName("online");
        controller.updateEnv(9L, noType);
        assertEquals(Env.TYPE_PROD, stored.getEnvType());
    }

    // ---------- 节点 ----------

    private static BuildNodeDTO validNode() {
        BuildNodeDTO r = new BuildNodeDTO();
        r.setName(" build-1 ");
        r.setNodeType("build");
        r.setHost(" 10.0.0.5 ");
        r.setAuthType("password");
        r.setUsername(" ops ");
        r.setPassword(" secret ");
        return r;
    }

    @Test
    void nodeCreateRejectsEmptyRequiredFields() {
        NodeController controller = MockInjector.inject(new NodeController());
        BuildNodeMapper mapper = MockInjector.field(controller, "buildNodeMapper");

        BuildNodeDTO r = validNode();
        r.setNodeType(null);
        assertEquals("节点类型不能为空", error(() -> controller.createNode(r)));
        BuildNodeDTO r2 = validNode();
        r2.setHost("  ");
        assertEquals("主机地址不能为空", error(() -> controller.createNode(r2)));
        BuildNodeDTO r3 = validNode();
        r3.setAuthType("");
        assertEquals("认证方式不能为空", error(() -> controller.createNode(r3)));
        BuildNodeDTO r4 = validNode();
        r4.setUsername(null);
        assertEquals("用户名不能为空", error(() -> controller.createNode(r4)));
        verify(mapper, never()).insert(any(BuildNode.class));
    }

    @Test
    void nodeCreateRequiresCredentialMatchingAuthType() {
        NodeController controller = MockInjector.inject(new NodeController());
        BuildNodeMapper mapper = MockInjector.field(controller, "buildNodeMapper");

        BuildNodeDTO noPassword = validNode();
        noPassword.setPassword("   ");
        assertEquals("密码不能为空", error(() -> controller.createNode(noPassword)));

        BuildNodeDTO keyWithoutKey = validNode();
        keyWithoutKey.setAuthType("private_key");
        keyWithoutKey.setPassword(null);
        assertEquals("私钥不能为空", error(() -> controller.createNode(keyWithoutKey)));

        BuildNodeDTO keyOk = validNode();
        keyOk.setAuthType("private_key");
        keyOk.setPassword(null);
        keyOk.setPrivateKey("-----BEGIN TEST KEY-----");
        controller.createNode(keyOk);

        controller.createNode(validNode());
        ArgumentCaptor<BuildNode> captor = ArgumentCaptor.forClass(BuildNode.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        BuildNode saved = captor.getAllValues().get(1);
        assertEquals("build-1", saved.getName());
        assertEquals("10.0.0.5", saved.getHost());
        assertEquals("ops", saved.getUsername());
        assertEquals(" secret ", saved.getPassword(), "密码不 trim");
    }

    private static BuildNode storedNode() {
        BuildNode n = new BuildNode();
        n.setId(7L);
        n.setName("build-1");
        n.setNodeType("build");
        n.setHost("10.0.0.5");
        n.setPort(22);
        n.setAuthType("password");
        n.setUsername("ops");
        n.setPassword("old-secret");
        n.setWorkDir("~/opsflow");
        return n;
    }

    @Test
    void nodeUpdateKeepsPasswordWhenLeftEmptyAndKeepsAbsentFields() {
        NodeController controller = MockInjector.inject(new NodeController());
        BuildNodeMapper mapper = MockInjector.field(controller, "buildNodeMapper");
        BuildNode stored = storedNode();
        when(mapper.selectById(7L)).thenReturn(stored);

        BuildNodeDTO r = new BuildNodeDTO();
        r.setDescription("只改描述");
        r.setPassword("");
        controller.updateNode(7L, r);

        assertEquals("old-secret", stored.getPassword());
        assertEquals("10.0.0.5", stored.getHost());
        assertEquals("build-1", stored.getName());
        // 没带 host 不算换机器，不清主机指纹
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void nodeUpdateRejectsBlankFieldsAndMissingCredentialAfterSwitch() {
        NodeController controller = MockInjector.inject(new NodeController());
        BuildNodeMapper mapper = MockInjector.field(controller, "buildNodeMapper");
        when(mapper.selectById(7L)).thenAnswer(inv -> storedNode());

        BuildNodeDTO blankHost = new BuildNodeDTO();
        blankHost.setHost(" ");
        assertEquals("主机地址不能为空", error(() -> controller.updateNode(7L, blankHost)));

        BuildNodeDTO blankName = new BuildNodeDTO();
        blankName.setName("");
        assertEquals("节点名称不能为空", error(() -> controller.updateNode(7L, blankName)));

        // 从密码改成私钥但没给私钥：旧节点没有私钥可沿用
        BuildNodeDTO switchToKey = new BuildNodeDTO();
        switchToKey.setAuthType("private_key");
        assertEquals("私钥不能为空", error(() -> controller.updateNode(7L, switchToKey)));
        verify(mapper, never()).updateById(any(BuildNode.class));
    }

    // ---------- 组件 ----------

    private static ComponentDTO validComponent() {
        ComponentDTO r = new ComponentDTO();
        r.setName(" gitlab ");
        r.setType("gitlab");
        r.setUrl(" https://git.example.com ");
        r.setAuthType("username_password");
        Map<String, String> auth = new HashMap<>();
        auth.put("username", " bot ");
        auth.put("password", " pw ");
        r.setAuthConfig(auth);
        return r;
    }

    @Test
    void componentCreateRequiresManualAuthFieldsByType() {
        ComponentController controller = MockInjector.inject(new ComponentController());
        ComponentMapper mapper = MockInjector.field(controller, "componentMapper");

        ComponentDTO noPassword = validComponent();
        noPassword.getAuthConfig().put("password", " ");
        assertEquals("密码不能为空", error(() -> controller.createComponent(noPassword)));

        ComponentDTO token = validComponent();
        token.setAuthType("token");
        token.setAuthConfig(new HashMap<>());
        assertEquals("Token不能为空", error(() -> controller.createComponent(token)));

        ComponentDTO oauth = validComponent();
        oauth.setAuthType("oauth");
        Map<String, String> o = new HashMap<>();
        o.put("clientId", "id");
        oauth.setAuthConfig(o);
        assertEquals("Client Secret不能为空", error(() -> controller.createComponent(oauth)));
        verify(mapper, never()).insert(any(Component.class));

        controller.createComponent(validComponent());
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(mapper).insert(captor.capture());
        assertEquals("gitlab", captor.getValue().getName());
        assertEquals("https://git.example.com", captor.getValue().getUrl());
        assertTrue(captor.getValue().getAuthConfig().contains("\"username\":\"bot\""));
        assertTrue(captor.getValue().getAuthConfig().contains("\"password\":\" pw \""), "密码不 trim");
    }

    private static Component storedComponent() {
        Component c = new Component();
        c.setId(2L);
        c.setName("harbor");
        c.setType("harbor");
        c.setUrl("https://harbor.example.com");
        c.setAuthType("username_password");
        c.setAuthConfig("{\"username\":\"admin\",\"password\":\"old\"}");
        return c;
    }

    @Test
    void componentUpdateKeepsSecretWhenLeftEmptyAndRejectsBlankBasics() {
        ComponentController controller = MockInjector.inject(new ComponentController());
        ComponentMapper mapper = MockInjector.field(controller, "componentMapper");
        Component stored = storedComponent();
        when(mapper.selectById(2L)).thenReturn(stored);

        ComponentDTO blankUrl = new ComponentDTO();
        blankUrl.setUrl(" ");
        blankUrl.setAuthType("username_password");
        assertEquals("访问地址不能为空", error(() -> controller.updateComponent(2L, blankUrl)));
        verify(mapper, never()).updateById(any(Component.class));

        ComponentDTO keep = new ComponentDTO();
        keep.setAuthType("username_password");
        Map<String, String> auth = new HashMap<>();
        auth.put("username", "admin");
        auth.put("password", "");
        keep.setAuthConfig(auth);
        controller.updateComponent(2L, keep);
        assertEquals("harbor", stored.getName(), "没带名称保持原值");
        assertTrue(stored.getAuthConfig().contains("\"password\":\"old\""), "密码留空沿用旧值");
    }

    @Test
    void componentUpdateRejectsSwitchingAuthTypeWithoutNewSecret() {
        ComponentController controller = MockInjector.inject(new ComponentController());
        ComponentMapper mapper = MockInjector.field(controller, "componentMapper");
        when(mapper.selectById(2L)).thenReturn(storedComponent());

        ComponentDTO toToken = new ComponentDTO();
        toToken.setAuthType("token");
        Map<String, String> auth = new HashMap<>();
        auth.put("token", "");
        toToken.setAuthConfig(auth);
        assertEquals("Token不能为空", error(() -> controller.updateComponent(2L, toToken)));
    }
}
