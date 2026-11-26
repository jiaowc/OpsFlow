package com.opsflow.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitRepoKeyTest {

    @Test
    void normalizesCommonRepoForms() {
        assertEquals("group/sub/project", GitRepoKey.normalize("https://git.example.com/group/sub/project.git"));
        assertEquals("group/project", GitRepoKey.normalize("http://git.example.com:8080/Group/Project/"));
        assertEquals("group/project", GitRepoKey.normalize("git@git.example.com:group/project.git"));
        assertEquals("group/project", GitRepoKey.normalize("ssh://git@git.example.com:2222/group/project.git"));
        assertEquals("jiaowc/opsflow", GitRepoKey.normalize("jiaowc/OpsFlow"));
    }

    @Test
    void blankReturnsNull() {
        assertNull(GitRepoKey.normalize(null));
        assertNull(GitRepoKey.normalize("  "));
        assertNull(GitRepoKey.normalize("https://git.example.com/"));
    }
}
