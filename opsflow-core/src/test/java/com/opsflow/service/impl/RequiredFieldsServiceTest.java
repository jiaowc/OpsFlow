package com.opsflow.service.impl;

import com.opsflow.api.dto.BuildRequest;
import com.opsflow.api.dto.CredentialDTO;
import com.opsflow.api.dto.PipelineStepDefDTO;
import com.opsflow.api.dto.PipelineTemplateDTO;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.CredentialMapper;
import com.opsflow.dao.mapper.PipelineStepDefMapper;
import com.opsflow.dao.mapper.PipelineTemplateMapper;
import com.opsflow.dao.model.Credential;
import com.opsflow.dao.model.PipelineStepDef;
import com.opsflow.dao.model.PipelineTemplate;
import com.opsflow.support.MockInjector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 钥匙串、步骤定义、模版、流水线任务分支的必填项 */
class RequiredFieldsServiceTest {

    private static String error(Runnable call) {
        return assertThrows(BusinessException.class, call::run).getMessage();
    }

    private static Map<String, String> config(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ---------- 钥匙串 ----------

    private static CredentialDTO credential(String type, Map<String, String> config) {
        CredentialDTO r = new CredentialDTO();
        r.setName("deploy-key");
        r.setCredentialType(type);
        r.setConfigData(config);
        return r;
    }

    @Test
    void credentialCreateRequiresConfigKeysByType() {
        CredentialServiceImpl service = MockInjector.inject(new CredentialServiceImpl());
        CredentialMapper mapper = MockInjector.field(service, "credentialMapper");

        assertEquals("密码不能为空", error(() -> service.create(
                credential("username_password", config("username", "ops", "password", " ")))));
        assertEquals("用户名不能为空", error(() -> service.create(
                credential("ssh_password", config("password", "pw")))));
        assertEquals("Token不能为空", error(() -> service.create(credential("token", config("token", "")))));
        assertEquals("API Key不能为空", error(() -> service.create(credential("api_key", config("other", "x")))));
        assertEquals("Client Secret不能为空", error(() -> service.create(
                credential("oauth", config("clientId", "id")))));
        assertEquals("私钥不能为空", error(() -> service.create(
                credential("ssh_key", config("username", "root", "privateKey", "  ")))));
        verify(mapper, never()).insert(any(Credential.class));

        service.create(credential("username_password", config("username", " ops ", "password", " pw ")));
        // kubeconfig 页面上没有星号，不强制
        service.create(credential("kubeconfig", config("note", "x")));
        ArgumentCaptor<Credential> captor = ArgumentCaptor.forClass(Credential.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        String json = captor.getAllValues().get(0).getConfigData();
        assertTrue(json.contains("\"username\":\"ops\""), json);
        assertTrue(json.contains("\"password\":\" pw \""), "密码不 trim：" + json);
    }

    @Test
    void credentialUpdateKeepsSecretWhenLeftEmptyOrMasked() {
        CredentialServiceImpl service = MockInjector.inject(new CredentialServiceImpl());
        CredentialMapper mapper = MockInjector.field(service, "credentialMapper");
        Credential stored = new Credential();
        stored.setId(1L);
        stored.setName("deploy-key");
        stored.setCredentialType("username_password");
        stored.setConfigData("{\"username\":\"ops\",\"password\":\"old\"}");
        when(mapper.selectById(1L)).thenReturn(stored);

        service.update(1L, credential("username_password", config("username", "ops", "password", "")));
        assertTrue(stored.getConfigData().contains("\"password\":\"old\""));
        service.update(1L, credential("username_password", config("username", "ops", "password", "******")));
        assertTrue(stored.getConfigData().contains("\"password\":\"old\""));

        assertEquals("用户名不能为空", error(() -> service.update(1L,
                credential("username_password", config("username", " ", "password", "")))));
    }

    @Test
    void credentialUpdateRejectsTypeSwitchWithoutRequiredSecret() {
        CredentialServiceImpl service = MockInjector.inject(new CredentialServiceImpl());
        CredentialMapper mapper = MockInjector.field(service, "credentialMapper");
        Credential stored = new Credential();
        stored.setId(1L);
        stored.setName("deploy-key");
        stored.setCredentialType("username_password");
        stored.setConfigData("{\"username\":\"ops\",\"password\":\"old\"}");
        when(mapper.selectById(1L)).thenReturn(stored);

        assertEquals("Token不能为空", error(() -> service.update(1L, credential("token", config("token", "")))));
        CredentialDTO typeOnly = credential("ssh_key", null);
        assertEquals("私钥不能为空", error(() -> service.update(1L, typeOnly)));
        verify(mapper, never()).updateById(any(Credential.class));
    }

    // ---------- 步骤定义 ----------

    private static PipelineStepDefDTO stepDef(String type, Map<String, String> content) {
        PipelineStepDefDTO r = new PipelineStepDefDTO();
        r.setName(" 构建 ");
        r.setStepType(type);
        r.setPhase("build");
        r.setTimeoutSeconds(600);
        r.setContentConfig(content);
        return r;
    }

    @Test
    void stepDefRequiresContentByType() {
        PipelineStepDefServiceImpl service = MockInjector.inject(new PipelineStepDefServiceImpl());
        PipelineStepDefMapper mapper = MockInjector.field(service, "pipelineStepDefMapper");

        assertEquals("构建命令不能为空", error(() -> service.create(stepDef("build", config("buildCommand", " ")))));
        assertEquals("Dockerfile 模版不能为空", error(() -> service.create(
                stepDef("docker_build", config("dockerBuildCommand", "docker build .")))));
        assertEquals("docker build 命令不能为空", error(() -> service.create(
                stepDef("docker_build", config("dockerfileTemplateId", "3")))));
        assertEquals("docker push 命令不能为空", error(() -> service.create(stepDef("push_image", null))));
        assertEquals("Deployment 模版不能为空", error(() -> service.create(stepDef("render_template", config()))));
        verify(mapper, never()).insert(any(PipelineStepDef.class));

        service.create(stepDef("build", config("buildCommand", "mvn package")));
        // 没有必填内容项的类型不受影响
        service.create(stepDef("checkout", null));
        ArgumentCaptor<PipelineStepDef> captor = ArgumentCaptor.forClass(PipelineStepDef.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertEquals("构建", captor.getAllValues().get(0).getName());
    }

    @Test
    void stepDefWithoutTimeoutDefaultsTo60() {
        PipelineStepDefServiceImpl service = MockInjector.inject(new PipelineStepDefServiceImpl());
        PipelineStepDefMapper mapper = MockInjector.field(service, "pipelineStepDefMapper");
        PipelineStepDefDTO noTimeout = stepDef("build", config("buildCommand", "mvn package"));
        noTimeout.setTimeoutSeconds(null);
        service.create(noTimeout);
        ArgumentCaptor<PipelineStepDef> captor = ArgumentCaptor.forClass(PipelineStepDef.class);
        verify(mapper).insert(captor.capture());
        assertEquals(60, captor.getValue().getTimeoutSeconds(), "没带超时时间沿用默认 60");

        PipelineStepDefDTO zero = stepDef("build", config("buildCommand", "mvn package"));
        zero.setTimeoutSeconds(0);
        assertEquals("超时时间不能小于1秒", error(() -> service.create(zero)));
    }

    @Test
    void stepDefUpdateRejectsMissingContent() {
        PipelineStepDefServiceImpl service = MockInjector.inject(new PipelineStepDefServiceImpl());
        PipelineStepDefMapper mapper = MockInjector.field(service, "pipelineStepDefMapper");
        when(mapper.selectById(2L)).thenReturn(new PipelineStepDef());
        assertEquals("docker push 命令不能为空", error(() -> service.update(2L,
                stepDef("push_image", config("dockerPushCommand", "")))));
        verify(mapper, never()).updateById(any(PipelineStepDef.class));
    }

    // ---------- 模版 ----------

    @Test
    void templateUpdateValidatesAgainstStoredType() {
        PipelineTemplateServiceImpl service = MockInjector.inject(new PipelineTemplateServiceImpl());
        PipelineTemplateMapper mapper = MockInjector.field(service, "pipelineTemplateMapper");
        PipelineTemplate stored = new PipelineTemplate();
        stored.setId(3L);
        stored.setType("dockerfile");
        stored.setName("java");
        when(mapper.selectById(3L)).thenReturn(stored);

        // 请求里没带类型：以前报「模版类型无效」，现在按库里的 dockerfile 校验基础镜像
        PipelineTemplateDTO r = new PipelineTemplateDTO();
        r.setName("java");
        r.setContent("FROM ${baseImage}");
        assertEquals("Dockerfile 模版需填写基础镜像", error(() -> service.update(3L, r)));

        r.setBaseImage(" eclipse-temurin:17 ");
        r.setName(" java17 ");
        service.update(3L, r);
        assertEquals("java17", stored.getName());
        assertEquals("eclipse-temurin:17", stored.getBaseImage());
        assertEquals("dockerfile", stored.getType());
    }

    // ---------- 流水线任务 ----------

    @Test
    void buildStartDoesNotRequireBranchButTrimsIt() {
        // 拉代码步骤可以在步骤参数里写死分支，或者流水线没有拉代码步骤，不带分支是合法用法
        BuildServiceImpl service = MockInjector.inject(new BuildServiceImpl());
        BuildRequest r = new BuildRequest();
        r.setServiceId(1L);
        r.setEnvId(2L);
        r.setPipelineTemplateId(3L);
        r.setBranch(" main ");
        // 服务不存在时在分支处理之后报错，借此只看分支处理结果
        assertEquals("服务不存在，ID: 1", error(() -> service.startBuild(r)));
        assertEquals("main", r.getBranch());
        r.setBranch("  ");
        assertEquals("服务不存在，ID: 1", error(() -> service.startBuild(r)));
        assertEquals(null, r.getBranch(), "空白分支按没带处理");
    }

    @Test
    void pipelineJobUpdateTrimsBranch() {
        PipelineRunServiceImpl service = new PipelineRunServiceImpl();
        BuildRequest r = new BuildRequest();
        r.setServiceId(1L);
        r.setEnvId(2L);
        r.setPipelineTemplateId(3L);
        r.setBranch(" release/1.0 ");
        ReflectionTestUtils.invokeMethod(service, "validateUpdateRequest", r);
        assertEquals("release/1.0", r.getBranch());
    }
}
