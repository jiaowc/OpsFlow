package com.opsflow.web.ui;

import com.opsflow.spi.EditionInfo;
import com.opsflow.spi.UiExtension;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端清单只下发已开通功能的扩展、菜单项和页签；页面路由不过滤。
 */
class UiExtensionRegistryFeatureFilterTest {

    /** 扩展：可指定扩展级 feature、若干菜单项（每项可带自己的 feature）、登录页脚本 */
    static class Ext implements UiExtension {
        final String id;
        final String feature;
        final String[][] items;
        final boolean loginScript;

        Ext(String id, String feature, boolean loginScript, String[]... items) {
            this.id = id;
            this.feature = feature;
            this.items = items;
            this.loginScript = loginScript;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String feature() {
            return feature;
        }

        @Override
        public List<MenuItem> menuItems() {
            return Arrays.stream(items)
                    .map(i -> new MenuItem(i[0], "/" + i[0], "menu." + i[0]).feature(i.length > 1 ? i[1] : null))
                    .collect(Collectors.toList());
        }

        @Override
        public List<String> scripts() {
            return Collections.singletonList("/ext/" + id + "/main.js");
        }

        @Override
        public List<String> loginScripts() {
            return loginScript ? Collections.singletonList("/ext/" + id + "/login.js") : Collections.emptyList();
        }
    }

    static EditionInfo enabled(String... features) {
        Set<String> set = new LinkedHashSet<>(Arrays.asList(features));
        return new EditionInfo() {
            @Override
            public String edition() {
                return "enterprise";
            }

            @Override
            public Set<String> enabledFeatures() {
                return set;
            }
        };
    }

    private static final List<UiExtension> EXTENSIONS = Arrays.asList(
            new Ext("audit", "audit", false, new String[]{"audit"}),
            new Ext("branding", "branding", true, new String[]{"branding"}),
            new Ext("license", null, false, new String[]{"license"}),
            new Ext("deploy", "deploy_approval", false, new String[]{"tasks"}, new String[]{"approval"}),
            new Ext("mixed", null, false, new String[]{"plain"}, new String[]{"either", "sso,notify_integration"}));

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> manifest(UiExtensionRegistry registry) {
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> ext : (List<Map<String, Object>>) registry.manifest().get("extensions")) {
            List<String> sections = ((List<Map<String, Object>>) ext.get("menuItems")).stream()
                    .map(m -> (String) m.get("section")).collect(Collectors.toList());
            result.put((String) ext.get("id"), sections);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<String> loginIds(UiExtensionRegistry registry) {
        return ((List<Map<String, Object>>) registry.loginManifest().get("extensions")).stream()
                .map(e -> (String) e.get("id")).collect(Collectors.toList());
    }

    @Test
    void withoutFeaturesOnlyExtensionsWithoutFeatureAreSent() {
        UiExtensionRegistry registry = new UiExtensionRegistry(EXTENSIONS, enabled());
        Map<String, List<String>> m = manifest(registry);
        assertEquals(Arrays.asList("license", "mixed"), new java.util.ArrayList<>(m.keySet()));
        assertEquals(Collections.singletonList("plain"), m.get("mixed"));
        assertTrue(loginIds(registry).isEmpty());
    }

    @Test
    void enabledFeatureBringsItsExtensionAndLoginScripts() {
        UiExtensionRegistry registry = new UiExtensionRegistry(EXTENSIONS, enabled("branding", "deploy_approval"));
        Map<String, List<String>> m = manifest(registry);
        assertEquals(Arrays.asList("branding", "deploy", "license", "mixed"), new java.util.ArrayList<>(m.keySet()));
        assertEquals(Arrays.asList("tasks", "approval"), m.get("deploy"));
        assertEquals(Collections.singletonList("branding"), loginIds(registry));
    }

    @Test
    void commaSeparatedMenuFeatureNeedsAnyOne() {
        UiExtensionRegistry registry = new UiExtensionRegistry(EXTENSIONS, enabled("notify_integration"));
        assertEquals(Arrays.asList("plain", "either"), manifest(registry).get("mixed"));
        assertFalse(manifest(registry).containsKey("audit"));
    }

    @Test
    void pagePathsAreNotFiltered() {
        UiExtensionRegistry registry = new UiExtensionRegistry(EXTENSIONS, enabled());
        assertEquals(new LinkedHashSet<>(Arrays.asList("/audit", "/branding", "/tasks", "/approval", "/license",
                "/plain", "/either")), registry.pagePaths());
    }

    @Test
    void withoutEditionInfoEverythingIsSent() {
        assertEquals(5, manifest(new UiExtensionRegistry(EXTENSIONS)).size());
    }

    @Test
    void isEnabledMatchesFrontendDataFeatureRules() {
        Set<String> on = Collections.singleton("audit");
        assertTrue(UiExtensionRegistry.isEnabled(null, on));
        assertTrue(UiExtensionRegistry.isEnabled(" ", on));
        assertTrue(UiExtensionRegistry.isEnabled("sso, audit", on));
        assertFalse(UiExtensionRegistry.isEnabled("sso", on));
        assertFalse(UiExtensionRegistry.isEnabled(" , ", on));
    }

    /** 扩展级 feature 为空、页签分属两个功能码（后面会有一个扩展里的页签受不同功能控制） */
    static UiExtension tabsWithOwnFeatures(String extFeature) {
        return new UiExtension() {
            @Override
            public String id() {
                return "extra";
            }

            @Override
            public String feature() {
                return extFeature;
            }

            @Override
            public List<MenuItem> menuItems() {
                return Collections.singletonList(new MenuItem("extra-page", "/extra-page", "menu.extra").feature("beta"));
            }

            @Override
            public List<Tab> tabs() {
                return Arrays.asList(
                        new Tab("settings", "one", "tab.one").feature("alpha"),
                        new Tab("settings", "two", "tab.two").feature("alpha"),
                        new Tab("profile", "three", "tab.three").feature("beta"),
                        new Tab("profile", "four", "tab.four").feature("alpha,beta"),
                        new Tab("profile", "always", "tab.always"));
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extra(UiExtensionRegistry registry) {
        List<Map<String, Object>> exts = (List<Map<String, Object>>) registry.manifest().get("extensions");
        return exts.stream().filter(e -> "extra".equals(e.get("id"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tabIds(Map<String, Object> ext) {
        return ((List<Map<String, Object>>) ext.get("tabs")).stream()
                .map(t -> (String) t.get("tab")).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private static List<String> menuSections(Map<String, Object> ext) {
        return ((List<Map<String, Object>>) ext.get("menuItems")).stream()
                .map(m -> (String) m.get("section")).collect(Collectors.toList());
    }

    @Test
    void extensionWithoutFeatureFiltersEachTabAndMenuByItsOwnFeature() {
        Map<String, Object> onlyAlpha = extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures(null)), enabled("alpha")));
        assertEquals(Arrays.asList("one", "two", "four", "always"), tabIds(onlyAlpha));
        assertEquals(Collections.emptyList(), menuSections(onlyAlpha));

        Map<String, Object> onlyBeta = extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures(null)), enabled("beta")));
        assertEquals(Arrays.asList("three", "four", "always"), tabIds(onlyBeta));
        assertEquals(Collections.singletonList("extra-page"), menuSections(onlyBeta));

        Map<String, Object> none = extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures(null)), enabled()));
        assertEquals(Collections.singletonList("always"), tabIds(none));
        assertEquals(Collections.emptyList(), menuSections(none));
    }

    @Test
    void extensionFeatureNotEnabledDropsAllItsTabs() {
        assertNull(extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures("gamma")), enabled("alpha", "beta"))));
        Map<String, Object> on = extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures("gamma")), enabled("gamma", "alpha")));
        assertEquals(Arrays.asList("one", "two", "four", "always"), tabIds(on),
                "页签自己的 feature 仍要开通（three 只要 beta，被去掉）；always 没写 feature，继承扩展的 gamma");
    }

    @Test
    void tabsAreNotFilteredWithoutEditionInfo() {
        assertEquals(5, tabIds(extra(new UiExtensionRegistry(
                Collections.singletonList(tabsWithOwnFeatures(null))))).size());
    }
}
