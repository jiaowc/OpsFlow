package com.opsflow.spi;

import java.util.Collections;
import java.util.Set;

/** 社区版：没有任何商业功能码 */
public class DefaultEditionInfo implements EditionInfo {

    @Override
    public String edition() {
        return COMMUNITY;
    }

    @Override
    public Set<String> enabledFeatures() {
        return Collections.emptySet();
    }
}
