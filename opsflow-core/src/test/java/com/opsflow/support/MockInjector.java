package com.opsflow.support;

import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.util.List;

/**
 * 给 @Autowired 字段（List 除外）注入 Mockito mock，单测里直接调 controller / service 方法用。
 */
public final class MockInjector {

    private MockInjector() {
    }

    public static <T> T inject(T target) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Autowired.class) || List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    if (field.get(target) == null) {
                        field.set(target, Mockito.mock(field.getType()));
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            type = type.getSuperclass();
        }
        return target;
    }

    @SuppressWarnings("unchecked")
    public static <M> M field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (M) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name);
    }
}
