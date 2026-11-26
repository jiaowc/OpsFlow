package com.opsflow.web.ui;

import com.opsflow.spi.UiExtension;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiExtensionRegistryTest {

    /** 测试用扩展：一个菜单、一个片段、一个脚本 */
    static class Simple implements UiExtension {
        final String id;
        final String path;
        final String script;

        Simple(String id, String path, String script) {
            this.id = id;
            this.path = path;
            this.script = script;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String feature() {
            return "feat-" + id;
        }

        @Override
        public List<MenuItem> menuItems() {
            return Collections.singletonList(new MenuItem(id, path, "menu." + id)
                    .permission(id + ":view").after("users").order(5).loader("load" + id));
        }

        @Override
        public List<Fragment> fragments() {
            return Collections.singletonList(new Fragment(id, "/ext/" + id + "/page.html"));
        }

        @Override
        public List<String> scripts() {
            return Collections.singletonList(script);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extensions(UiExtensionRegistry registry) {
        return (List<Map<String, Object>>) registry.manifest().get("extensions");
    }

    @Test
    void 没有扩展时返回空列表() {
        UiExtensionRegistry registry = new UiExtensionRegistry(new ArrayList<>());
        assertEquals(Collections.singletonMap("extensions", Collections.emptyList()), registry.manifest());
        assertTrue(registry.pagePaths().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void 按id排序并输出完整字段() {
        UiExtensionRegistry registry = new UiExtensionRegistry(Arrays.asList(
                new Simple("zeta", "/zeta", "/ext/zeta/z.js"),
                new Simple("alpha", "/alpha", "/ext/alpha/a.js")));
        List<Map<String, Object>> exts = extensions(registry);
        assertEquals(2, exts.size());
        assertEquals("alpha", exts.get(0).get("id"));
        assertEquals("zeta", exts.get(1).get("id"));

        Map<String, Object> alpha = exts.get(0);
        assertEquals("feat-alpha", alpha.get("feature"));
        Map<String, Object> item = ((List<Map<String, Object>>) alpha.get("menuItems")).get(0);
        assertEquals("alpha", item.get("section"));
        assertEquals("/alpha", item.get("path"));
        assertEquals("menu.alpha", item.get("labelKey"));
        assertEquals("alpha:view", item.get("permission"));
        assertEquals("feat-alpha", item.get("feature"), "菜单没写 feature 时继承扩展的 feature");
        assertEquals("users", item.get("after"));
        assertEquals(5, item.get("order"));
        assertEquals("loadalpha", item.get("loader"));
        Map<String, Object> fragment = ((List<Map<String, Object>>) alpha.get("fragments")).get(0);
        assertEquals("/ext/alpha/page.html", fragment.get("url"));
        assertEquals(Collections.singletonList("/ext/alpha/a.js"), alpha.get("scripts"));
        assertEquals(Collections.emptyList(), alpha.get("styles"));
        assertEquals(Collections.emptyList(), alpha.get("messageBundles"));
        assertEquals(Arrays.asList("/alpha", "/zeta"), new ArrayList<>(registry.pagePaths()));
    }

    @Test
    void 抛异常或声明不合法的扩展被跳过_其余照常返回() {
        UiExtension throwing = new Simple("boom", "/boom", "/ext/boom/b.js") {
            @Override
            public List<String> scripts() {
                throw new IllegalStateException("broken");
            }
        };
        UiExtension badId = () -> null;
        List<UiExtension> all = Arrays.asList(
                throwing,
                badId,
                new Simple("ok", "/ok", "/ext/ok/ok.js"),
                new Simple("remote", "/remote", "https://evil.example.com/x.js"),
                new Simple("proto", "/proto", "//evil.example.com/x.js"),
                new Simple("dotdot", "/dotdot", "/ext/../secret.js"),
                new Simple("badpath", "/a/b", "/ext/badpath/x.js"),
                new Simple("upper", "/Upper", "/ext/upper/x.js"));
        UiExtensionRegistry registry = new UiExtensionRegistry(all);
        List<Map<String, Object>> exts = extensions(registry);
        assertEquals(1, exts.size());
        assertEquals("ok", exts.get(0).get("id"));
        assertEquals(Collections.singleton("/ok"), registry.pagePaths());
    }

    @Test
    void 与先登记的扩展重复的id或路径被跳过() {
        UiExtensionRegistry registry = new UiExtensionRegistry(Arrays.asList(
                new Simple("aaa", "/same", "/ext/a.js"),
                new Simple("bbb", "/same", "/ext/b.js"),
                new Simple("aaa", "/other", "/ext/c.js")));
        List<Map<String, Object>> exts = extensions(registry);
        assertEquals(1, exts.size());
        assertEquals("/ext/a.js", ((List<?>) exts.get(0).get("scripts")).get(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 菜单显式feature优先_扩展无feature时为null() {
        UiExtension ext = new UiExtension() {
            @Override
            public String id() {
                return "plain";
            }

            @Override
            public List<MenuItem> menuItems() {
                return Arrays.asList(new MenuItem("p1", "/p1", "menu.p1"),
                        new MenuItem("p2", "/p2", "menu.p2").feature("special"));
            }
        };
        Map<String, Object> described = extensions(new UiExtensionRegistry(Collections.singletonList(ext))).get(0);
        assertNull(described.get("feature"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) described.get("menuItems");
        assertNull(items.get(0).get("feature"));
        assertEquals("special", items.get(1).get("feature"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 登录页清单只含声明了登录脚本的扩展且不暴露菜单() {
        UiExtension withLogin = new UiExtension() {
            @Override
            public String id() {
                return "brand";
            }

            @Override
            public List<MenuItem> menuItems() {
                return Collections.singletonList(new MenuItem("brand", "/brand", "menu.brand").permission("system:config"));
            }

            @Override
            public List<String> loginScripts() {
                return Collections.singletonList("/ext/brand/login.js");
            }

            @Override
            public List<String> loginMessageBundles() {
                return Collections.singletonList("/ext/brand/login-messages.json");
            }
        };
        UiExtensionRegistry registry = new UiExtensionRegistry(Arrays.asList(
                withLogin, new Simple("plain", "/plain", "/ext/plain/p.js")));
        List<Map<String, Object>> login = (List<Map<String, Object>>) registry.loginManifest().get("extensions");
        assertEquals(1, login.size());
        Map<String, Object> ext = login.get(0);
        assertEquals("brand", ext.get("id"));
        assertEquals(Collections.singletonList("/ext/brand/login.js"), ext.get("scripts"));
        assertEquals(Collections.singletonList("/ext/brand/login-messages.json"), ext.get("messageBundles"));
        assertEquals(Arrays.asList("id", "scripts", "messageBundles"), new ArrayList<>(ext.keySet()));
    }

    @Test
    void 登录脚本地址同样要求站内路径() {
        UiExtension bad = new UiExtension() {
            @Override
            public String id() {
                return "bad";
            }

            @Override
            public List<String> loginScripts() {
                return Collections.singletonList("https://evil.example.com/x.js");
            }
        };
        UiExtensionRegistry registry = new UiExtensionRegistry(Collections.singletonList(bad));
        assertEquals(Collections.emptyList(), registry.loginManifest().get("extensions"));
    }

    /** 只声明页签的扩展 */
    static UiExtension tabsOnly(String id, UiExtension.Tab... tabs) {
        return new UiExtension() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<Tab> tabs() {
                return Arrays.asList(tabs);
            }

            @Override
            public List<String> scripts() {
                return Collections.singletonList("/ext/" + id + "/main.js");
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tabsOf(Map<String, Object> ext) {
        return (List<Map<String, Object>>) ext.get("tabs");
    }

    @Test
    void 只有页签没有菜单的扩展正常注册并输出完整字段() {
        UiExtensionRegistry registry = new UiExtensionRegistry(Collections.singletonList(tabsOnly("extra",
                new UiExtension.Tab("settings", "extra-one", "tab.extraOne")
                        .fragment("/ext/extra/one.html").feature("feat-one").permission("system:config")
                        .order(10).loader("loadExtraOne"),
                new UiExtension.Tab("settings", "extra-two", "tab.extraTwo"))));
        List<Map<String, Object>> exts = extensions(registry);
        assertEquals(1, exts.size());
        Map<String, Object> ext = exts.get(0);
        assertEquals(Collections.emptyList(), ext.get("menuItems"));
        List<Map<String, Object>> tabs = tabsOf(ext);
        assertEquals(2, tabs.size());
        Map<String, Object> one = tabs.get(0);
        assertEquals(Arrays.asList("section", "tab", "labelKey", "fragment", "feature", "permission", "order", "loader"),
                new ArrayList<>(one.keySet()));
        assertEquals("settings", one.get("section"));
        assertEquals("extra-one", one.get("tab"));
        assertEquals("tab.extraOne", one.get("labelKey"));
        assertEquals("/ext/extra/one.html", one.get("fragment"));
        assertEquals("feat-one", one.get("feature"));
        assertEquals("system:config", one.get("permission"));
        assertEquals(10, one.get("order"));
        assertEquals("loadExtraOne", one.get("loader"));
        Map<String, Object> two = tabs.get(1);
        assertNull(two.get("fragment"));
        assertNull(two.get("feature"));
        assertNull(two.get("permission"));
        assertEquals(0, two.get("order"));
        assertNull(two.get("loader"));
        assertTrue(registry.pagePaths().isEmpty(), "页签不注册页面路由");
    }

    @Test
    void 没有声明页签时输出空列表_页签feature为空时继承扩展的feature() {
        Map<String, Object> simple = extensions(new UiExtensionRegistry(Collections.singletonList(
                new Simple("plain", "/plain", "/ext/plain/p.js")))).get(0);
        assertEquals(Collections.emptyList(), simple.get("tabs"));

        UiExtension withFeature = new UiExtension() {
            @Override
            public String id() {
                return "extra";
            }

            @Override
            public String feature() {
                return "feat-extra";
            }

            @Override
            public List<Tab> tabs() {
                return Arrays.asList(new Tab("settings", "a", "tab.a"), new Tab("settings", "b", "tab.b").feature("feat-b"));
            }
        };
        List<Map<String, Object>> tabs = tabsOf(extensions(new UiExtensionRegistry(
                Collections.singletonList(withFeature))).get(0));
        assertEquals("feat-extra", tabs.get(0).get("feature"));
        assertEquals("feat-b", tabs.get(1).get("feature"));
    }

    @Test
    void 页签声明不合法时跳过整个扩展() {
        List<UiExtension> all = Arrays.asList(
                tabsOnly("ok", new UiExtension.Tab("settings", "ok", "tab.ok").fragment("/ext/ok/ok.html")),
                tabsOnly("badsection", new UiExtension.Tab("Settings", "x", "tab.x")),
                tabsOnly("nosection", new UiExtension.Tab(null, "x", "tab.x")),
                tabsOnly("badtab", new UiExtension.Tab("settings", "a b", "tab.x")),
                tabsOnly("notab", new UiExtension.Tab("settings", null, "tab.x")),
                tabsOnly("nolabel", new UiExtension.Tab("settings", "nolabel", " ")),
                tabsOnly("remote", new UiExtension.Tab("settings", "remote", "tab.x")
                        .fragment("https://evil.example.com/x.html")),
                tabsOnly("proto", new UiExtension.Tab("settings", "proto", "tab.x").fragment("//evil.example.com/x.html")),
                tabsOnly("dotdot", new UiExtension.Tab("settings", "dotdot", "tab.x").fragment("/ext/../secret.html")),
                tabsOnly("badloader", new UiExtension.Tab("settings", "badloader", "tab.x").loader("alert(1)")),
                tabsOnly("selfdup", new UiExtension.Tab("settings", "same", "tab.x"),
                        new UiExtension.Tab("settings", "same", "tab.y")));
        List<Map<String, Object>> exts = extensions(new UiExtensionRegistry(all));
        assertEquals(1, exts.size());
        assertEquals("ok", exts.get(0).get("id"));
    }

    @Test
    void 同一section下页签id跨扩展重复时后登记的扩展被跳过_不同section可以同名() {
        UiExtensionRegistry registry = new UiExtensionRegistry(Arrays.asList(
                tabsOnly("bbb", new UiExtension.Tab("settings", "shared", "tab.b")),
                tabsOnly("aaa", new UiExtension.Tab("settings", "shared", "tab.a")),
                tabsOnly("ccc", new UiExtension.Tab("profile", "shared", "tab.c"))));
        List<Map<String, Object>> exts = extensions(registry);
        assertEquals(2, exts.size());
        assertEquals("aaa", exts.get(0).get("id"), "按 id 排序，先登记的 aaa 保留");
        assertEquals("ccc", exts.get(1).get("id"));
    }
}
