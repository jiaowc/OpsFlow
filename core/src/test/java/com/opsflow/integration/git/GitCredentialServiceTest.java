package com.opsflow.integration.git;

import com.opsflow.dao.mapper.ComponentMapper;
import com.opsflow.dao.model.Component;
import com.opsflow.integration.credential.ComponentAuthResolver;
import com.opsflow.integration.credential.ResolvedAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URLDecoder;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitCredentialServiceTest {

    @Mock
    private ComponentMapper componentMapper;

    @Mock
    private ComponentAuthResolver componentAuthResolver;

    @InjectMocks
    private GitCredentialService service;

    @Test
    void encodesSpecialCharactersInToken() throws Exception {
        String token = "a@b:c/d?e#f%g+h i&j=k";
        String line = GitCredentialService.formatStoreLine("https", "git.example.com", "oauth2", token);

        assertEquals("https://oauth2:a%40b%3Ac%2Fd%3Fe%23f%25g%2Bh%20i%26j%3Dk@git.example.com", line);
        // 编码后 user:token 段只剩一个 ":" 和一个 "@" 作为分隔符
        String userInfo = line.substring("https://".length(), line.lastIndexOf('@'));
        assertEquals(1, userInfo.chars().filter(c -> c == ':').count());
        assertFalse(userInfo.contains("@"));
        assertFalse(userInfo.contains("/"));
        assertEquals(token, URLDecoder.decode(userInfo.substring(userInfo.indexOf(':') + 1).replace("+", "%2B"), "UTF-8"));
    }

    @Test
    void encodesUserAndKeepsPort() {
        assertEquals("http://us%40er:p%3Aw@git.example.com:8080",
            GitCredentialService.formatStoreLine("http", "git.example.com:8080", "us@er", "p:w"));
    }

    @Test
    void buildsGitLabLineFromComponent() {
        Component component = new Component();
        component.setId(7L);
        component.setStatus(1);
        when(componentMapper.selectById(7L)).thenReturn(component);
        when(componentAuthResolver.resolve(component)).thenReturn(auth("token", "token", "glpat-x@y:z/w"));

        assertEquals("https://oauth2:glpat-x%40y%3Az%2Fw@git.example.com:8443",
            service.buildCredentialStoreLine("https://git.example.com:8443/group/app.git", 7L));
    }

    @Test
    void buildsGitHubLineWithAccessTokenUser() {
        Component component = new Component();
        component.setId(8L);
        component.setStatus(1);
        when(componentMapper.selectById(8L)).thenReturn(component);
        when(componentAuthResolver.resolve(component)).thenReturn(auth("token", "token", "ghp_abc"));

        assertEquals("https://x-access-token:ghp_abc@github.com",
            service.buildCredentialStoreLine("https://github.com/owner/repo.git", 8L));
    }

    @Test
    void returnsNullWithoutUsableCredential() {
        when(componentMapper.selectOne(any())).thenReturn(null);
        assertNull(service.buildCredentialStoreLine("https://git.example.com/group/app.git", null));
    }

    @Test
    void returnsNullForSshOrUrlWithUserInfo() {
        assertNull(service.buildCredentialStoreLine("git@git.example.com:group/app.git", 1L));
        assertNull(service.buildCredentialStoreLine("https://user@git.example.com/group/app.git", 1L));
        assertNull(service.buildCredentialStoreLine(null, 1L));
        verifyNoInteractions(componentMapper, componentAuthResolver);
    }

    private static ResolvedAuth auth(String type, String key, String value) {
        ResolvedAuth auth = new ResolvedAuth();
        auth.setAuthType(type);
        auth.setAuthConfig(Collections.singletonMap(key, value));
        return auth;
    }
}
