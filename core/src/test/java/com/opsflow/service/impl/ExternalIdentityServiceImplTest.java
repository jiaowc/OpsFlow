package com.opsflow.service.impl;

import com.opsflow.common.exception.BusinessException;
import com.opsflow.dao.mapper.UserExternalIdentityMapper;
import com.opsflow.dao.model.UserExternalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 同一用户同一登录方式只保留一条；重复和并发绑定 */
class ExternalIdentityServiceImplTest {

    private final UserExternalIdentityMapper mapper = mock(UserExternalIdentityMapper.class);
    private final ExternalIdentityServiceImpl service = new ExternalIdentityServiceImpl();

    ExternalIdentityServiceImplTest() {
        ReflectionTestUtils.setField(service, "mapper", mapper);
    }

    @Test
    void 首次绑定插入_写入前去空白() {
        List<UserExternalIdentity> inserted = new ArrayList<>();
        when(mapper.insert(any(UserExternalIdentity.class))).thenAnswer(inv -> {
            inserted.add(inv.getArgument(0));
            return 1;
        });
        service.bind(1L, "demo", "  Alice  ");
        assertEquals(1, inserted.size());
        assertEquals("Alice", inserted.get(0).getSubject());
    }

    @Test
    void 已有记录时改为更新_不再插入第二条() {
        UserExternalIdentity own = row(1L, "old");
        // 第一次查 subject 占用：无；第二次查本人记录：有
        when(mapper.selectOne(any())).thenReturn(null, own);
        service.bind(1L, "demo", "new");
        assertEquals("new", own.getSubject());
        verify(mapper).updateById(own);
        verify(mapper, never()).insert(any(UserExternalIdentity.class));
    }

    @Test
    void 重复绑定同一个ID什么也不做() {
        when(mapper.selectOne(any())).thenReturn(row(1L, "alice"));
        service.bind(1L, "demo", "alice");
        verify(mapper, never()).insert(any(UserExternalIdentity.class));
        verify(mapper, never()).updateById(any(UserExternalIdentity.class));
    }

    @Test
    void 并发绑定_插入撞唯一键后重读并更新() {
        UserExternalIdentity raced = row(1L, "other");
        // 第一轮：没占用、没本人记录 → 插入时另一个请求刚插入同一用户同一方式 → 唯一键冲突
        // 第二轮：没占用、本人记录已存在 → 更新
        when(mapper.selectOne(any())).thenReturn(null, null, null, raced);
        when(mapper.insert(any(UserExternalIdentity.class))).thenThrow(new DuplicateKeyException("uk_user_external_identity_user_provider"));
        service.bind(1L, "demo", "alice");
        assertEquals("alice", raced.getSubject());
        verify(mapper).updateById(raced);
        verify(mapper, times(1)).insert(any(UserExternalIdentity.class));
    }

    @Test
    void 并发绑定_外部ID被其他用户抢先占用_报业务错误() {
        when(mapper.selectOne(any())).thenReturn(null, null, row(2L, "alice"));
        when(mapper.insert(any(UserExternalIdentity.class))).thenThrow(new DuplicateKeyException("uk_user_external_identity_subject"));
        BusinessException e = assertThrows(BusinessException.class, () -> service.bind(1L, "demo", "alice"));
        assertEquals("该外部用户 ID 已对应到其他用户：alice", e.getMessage());
    }

    @Test
    void 已对应到其他用户时拒绝_空值解除() {
        when(mapper.selectOne(any())).thenReturn(row(2L, "alice"));
        assertThrows(BusinessException.class, () -> service.bind(1L, "demo", "alice"));
        service.bind(1L, "demo", " ");
        verify(mapper).delete(any());
    }

    @Test
    void 查找按去空白后的值() {
        when(mapper.selectOne(any())).thenReturn(row(7L, "alice"));
        assertEquals(7L, service.findUserId("demo", " alice "));
        assertNull(service.findUserId("demo", "  "));
    }

    private static UserExternalIdentity row(Long userId, String subject) {
        UserExternalIdentity r = new UserExternalIdentity();
        r.setUserId(userId);
        r.setProvider("demo");
        r.setSubject(subject);
        return r;
    }
}
