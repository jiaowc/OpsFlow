package com.opsflow.service.impl;

import com.opsflow.api.dto.NodeEnvCheckResultDTO;
import com.opsflow.dao.mapper.BuildNodeMapper;
import com.opsflow.dao.model.BuildNode;
import com.opsflow.integration.ssh.SshClient;
import com.opsflow.integration.ssh.SshCommandResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeServiceImplTest {

    private BuildNodeMapper buildNodeMapper;
    private SshClient sshClient;
    private NodeServiceImpl service;

    @BeforeEach
    void setUp() {
        buildNodeMapper = mock(BuildNodeMapper.class);
        sshClient = mock(SshClient.class);
        service = new NodeServiceImpl();
        ReflectionTestUtils.setField(service, "buildNodeMapper", buildNodeMapper);
        ReflectionTestUtils.setField(service, "sshClient", sshClient);
    }

    @Test
    void marksOfflineWhenSshFails() {
        BuildNode node = onlineBuildNode();
        when(buildNodeMapper.selectById(7L)).thenReturn(node);
        when(sshClient.testConnection(node)).thenReturn(SshCommandResult.fail("timeout: socket is not established"));

        NodeEnvCheckResultDTO result = service.checkEnvironment(7L);

        assertFalse(result.getPassed());
        assertEquals(NodeServiceImpl.STATUS_OFFLINE, result.getStatus());
        ArgumentCaptor<BuildNode> captor = ArgumentCaptor.forClass(BuildNode.class);
        verify(buildNodeMapper).updateById(captor.capture());
        assertEquals(NodeServiceImpl.STATUS_OFFLINE, captor.getValue().getStatus());
        verify(sshClient, never()).executeCheck(any(), anyString());
    }

    @Test
    void marksOnlineWhenSshWorksEvenIfOptionalDockerMissing() {
        BuildNode node = offlineBuildNode();
        when(buildNodeMapper.selectById(8L)).thenReturn(node);
        when(sshClient.testConnection(node)).thenReturn(SshCommandResult.of(0, "SSH_OK\nLinux"));
        when(sshClient.executeCheck(any(), anyString())).thenAnswer(inv -> {
            String cmd = inv.getArgument(1);
            if (cmd.contains("docker")) {
                return SshCommandResult.fail("command not found");
            }
            if (cmd.contains("java")) {
                return SshCommandResult.of(0, "openjdk version \"1.8.0\"");
            }
            if (cmd.contains("mvn")) {
                return SshCommandResult.of(0, "Apache Maven 3.6.3");
            }
            if (cmd.contains("git")) {
                return SshCommandResult.of(0, "git version 2.34.1");
            }
            return SshCommandResult.of(0, "ok");
        });

        NodeEnvCheckResultDTO result = service.checkEnvironment(8L);

        assertTrue(result.getPassed());
        assertEquals(NodeServiceImpl.STATUS_ONLINE, result.getStatus());
        ArgumentCaptor<BuildNode> captor = ArgumentCaptor.forClass(BuildNode.class);
        verify(buildNodeMapper).updateById(captor.capture());
        assertEquals(NodeServiceImpl.STATUS_ONLINE, captor.getValue().getStatus());
    }

    private static BuildNode onlineBuildNode() {
        BuildNode node = new BuildNode();
        node.setId(7L);
        node.setName("local-build-1");
        node.setNodeType("build");
        node.setHost("127.0.0.1");
        node.setPort(2222);
        node.setUsername("root");
        node.setPassword("Build12345");
        node.setAuthType("password");
        node.setStatus(NodeServiceImpl.STATUS_ONLINE);
        return node;
    }

    private static BuildNode offlineBuildNode() {
        BuildNode node = onlineBuildNode();
        node.setId(8L);
        node.setName("local-build-2");
        node.setPort(2223);
        node.setStatus(NodeServiceImpl.STATUS_OFFLINE);
        return node;
    }
}
