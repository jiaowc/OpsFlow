package com.opsflow.spi;

import java.util.Collections;
import java.util.List;

/**
 * 扩展模块需要放开的路径（Spring MVC 路径模式，如 {@code /api/foo/public}、{@code /api/foo/**}）。可以有多个实现。
 * <p>{@link #publicPaths()}：不需要登录（如登录前页面要用的接口、第三方回调）；
 * {@link #csrfExemptPaths()}：不做 Origin/Referer 来源校验（只用于自带签名校验的回调）。</p>
 * <p>只在启动时读取一次。</p>
 */
public interface WebSecurityExtension {

    default List<String> publicPaths() {
        return Collections.emptyList();
    }

    default List<String> csrfExemptPaths() {
        return Collections.emptyList();
    }
}
