package com.opsflow.spi;

import java.util.Set;

/**
 * 当前运行的版本和已启用的功能码，供 {@code GET /api/edition} 使用。
 * <p>核心默认是社区版、没有功能码；商业版提供自己的实现。</p>
 */
public interface EditionInfo {

    String COMMUNITY = "community";

    /** 版本名，如 {@code community}、{@code enterprise} */
    String edition();

    /** 已启用的功能码（小写），社区版为空集 */
    Set<String> enabledFeatures();
}
