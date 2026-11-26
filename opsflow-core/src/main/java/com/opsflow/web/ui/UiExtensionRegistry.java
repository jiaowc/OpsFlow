package com.opsflow.web.ui;

import com.opsflow.spi.EditionInfo;
import com.opsflow.spi.UiExtension;
import com.opsflow.web.assets.StaticAssetVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 汇总所有 {@link UiExtension}，生成前端清单和需要注册的页面路由。
 * <p>声明不合法（id/section/path/页签 id 格式不对、地址不是站内路径、与已有扩展冲突）或 getter 抛异常时，
 * 跳过整个扩展并记 warn，不影响其余扩展和核心页面。</p>
 * <p>前端清单只下发已开通功能的扩展、菜单项和页签（见 {@link EditionInfo}）；页面路由不过滤。</p>
 */
@Component
public class UiExtensionRegistry {

    private static final Logger log = LoggerFactory.getLogger(UiExtensionRegistry.class);

    /** id、section：用作 DOM id 和 CSS 选择器，只允许小写字母、数字、横线 */
    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    /** 页面路径：单段 */
    static final Pattern PATH = Pattern.compile("^/[a-z0-9][a-z0-9-]*$");
    /** 静态资源地址：站内绝对路径，不允许协议、//、..、查询串之外的特殊字符 */
    static final Pattern ASSET = Pattern.compile("^/(?!/)[A-Za-z0-9._/-]+$");
    /** 全局函数名 */
    static final Pattern LOADER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    @Autowired(required = false)
    private List<UiExtension> extensions = new ArrayList<>();

    /** 当前已开通的功能；为 null 时不按功能过滤（只在单测里手工组装时出现，应用里总有社区版或商业版的实现） */
    @Autowired(required = false)
    private EditionInfo editionInfo;

    /** 给清单里的资源地址加内容版本号（?v=），浏览器可长期缓存；手工组装时为空，地址原样输出 */
    @Autowired(required = false)
    private StaticAssetVersions assetVersions;

    /** Spring 用这个构造器，扩展列表走字段注入（没有扩展时为空表） */
    public UiExtensionRegistry() {
    }

    /** 测试或手工组装用：不按功能过滤 */
    public UiExtensionRegistry(List<? extends UiExtension> extensions) {
        this(extensions, null);
    }

    /** 测试或手工组装用：按 {@code editionInfo} 已开通的功能过滤清单 */
    public UiExtensionRegistry(List<? extends UiExtension> extensions, EditionInfo editionInfo) {
        this.extensions = extensions != null ? new ArrayList<>(extensions) : new ArrayList<>();
        this.editionInfo = editionInfo;
    }

    /** 前端清单：{@code {"extensions":[...]}}，按 id 排序，只含已开通功能的扩展、菜单项和页签 */
    public Map<String, Object> manifest() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("extensions", describeEnabled());
        return result;
    }

    /**
     * 登录页清单：{@code {"extensions":[{"id","scripts","messageBundles"}]}}，只含声明了登录页脚本的扩展。
     * 登录前可访问，所以只输出静态资源地址，不输出菜单、权限等信息。同样只含已开通功能的扩展。
     */
    public Map<String, Object> loginManifest() {
        List<Map<String, Object>> login = new ArrayList<>();
        for (Map<String, Object> ext : describeEnabled()) {
            @SuppressWarnings("unchecked")
            List<String> scripts = (List<String>) ext.get("loginScripts");
            if (scripts.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ext.get("id"));
            item.put("scripts", scripts);
            item.put("messageBundles", ext.get("loginMessageBundles"));
            login.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("extensions", login);
        return result;
    }

    /**
     * 只保留已开通功能的扩展：扩展的 feature 未开通时整个扩展不下发；菜单项、页签自己的 feature 未开通时去掉该项。
     * 扩展的 feature 为空时，菜单项和页签各按自己的 feature 判断。
     * 没有 feature 的扩展（如 License 页面）总是下发。
     */
    private List<Map<String, Object>> describeEnabled() {
        List<Map<String, Object>> all = describeAll();
        if (editionInfo == null) {
            return all;
        }
        Set<String> enabled = editionInfo.enabledFeatures();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ext : all) {
            if (!isEnabled((String) ext.get("feature"), enabled)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) ext.get("menuItems");
            items.removeIf(item -> !isEnabled((String) item.get("feature"), enabled));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> tabs = (List<Map<String, Object>>) ext.get("tabs");
            tabs.removeIf(tab -> !isEnabled((String) tab.get("feature"), enabled));
            result.add(ext);
        }
        return result;
    }

    /** feature 为空表示不依赖功能；可逗号分隔，开通任一即可（与前端 data-feature 的判断一致） */
    static boolean isEnabled(String feature, Set<String> enabled) {
        if (feature == null || feature.trim().isEmpty()) {
            return true;
        }
        for (String part : feature.split(",")) {
            String code = part.trim();
            if (!code.isEmpty() && enabled.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 所有合法扩展声明的页面路径（不按功能过滤：开通功能后不用重启就能访问） */
    public Set<String> pagePaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Map<String, Object> ext : describeAll()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) ext.get("menuItems");
            for (Map<String, Object> item : items) {
                paths.add((String) item.get("path"));
            }
        }
        return paths;
    }

    private List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> described = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> tabKeys = new LinkedHashSet<>();
        List<UiExtension> sorted = new ArrayList<>(extensions);
        sorted.sort(Comparator.comparing(UiExtensionRegistry::safeId));
        for (UiExtension extension : sorted) {
            try {
                Map<String, Object> ext = describe(extension);
                String id = (String) ext.get("id");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) ext.get("menuItems");
                Set<String> extSections = new LinkedHashSet<>();
                Set<String> extPaths = new LinkedHashSet<>();
                for (Map<String, Object> item : items) {
                    extSections.add((String) item.get("section"));
                    extPaths.add((String) item.get("path"));
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tabs = (List<Map<String, Object>>) ext.get("tabs");
                Set<String> extTabKeys = new LinkedHashSet<>();
                for (Map<String, Object> tab : tabs) {
                    extTabKeys.add(tab.get("section") + "/" + tab.get("tab"));
                }
                if (ids.contains(id) || extSections.stream().anyMatch(sections::contains)
                        || extPaths.stream().anyMatch(paths::contains)
                        || extTabKeys.stream().anyMatch(tabKeys::contains)) {
                    throw new IllegalArgumentException("id/section/path/页签 与其他扩展重复");
                }
                ids.add(id);
                sections.addAll(extSections);
                paths.addAll(extPaths);
                tabKeys.addAll(extTabKeys);
                described.add(ext);
            } catch (RuntimeException e) {
                log.warn("跳过前端扩展 {}：{}", safeId(extension), e.getMessage());
            }
        }
        return described;
    }

    private static String safeId(UiExtension extension) {
        try {
            String id = extension.id();
            return id != null ? id : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private Map<String, Object> describe(UiExtension extension) {
        String id = require(extension.id(), NAME, "id");
        String feature = extension.feature();
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("id", id);
        ext.put("feature", feature);

        List<Map<String, Object>> menuItems = new ArrayList<>();
        for (UiExtension.MenuItem item : nullSafe(extension.menuItems())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("section", require(item.getSection(), NAME, "section"));
            m.put("path", require(item.getPath(), PATH, "path"));
            m.put("labelKey", requireText(item.getLabelKey(), "labelKey"));
            m.put("icon", item.getIcon());
            m.put("permission", item.getPermission());
            m.put("feature", item.getFeature() != null ? item.getFeature() : feature);
            m.put("after", item.getAfter() == null ? null : require(item.getAfter(), NAME, "after"));
            m.put("order", item.getOrder());
            m.put("loader", item.getLoader() == null ? null : require(item.getLoader(), LOADER, "loader"));
            menuItems.add(m);
        }
        ext.put("menuItems", menuItems);

        List<Map<String, Object>> tabs = new ArrayList<>();
        Set<String> tabKeys = new LinkedHashSet<>();
        for (UiExtension.Tab tab : nullSafe(extension.tabs())) {
            Map<String, Object> t = new LinkedHashMap<>();
            String section = require(tab.getSection(), NAME, "tab.section");
            String tabId = require(tab.getTab(), NAME, "tab.tab");
            if (!tabKeys.add(section + "/" + tabId)) {
                throw new IllegalArgumentException("页签重复：" + section + "/" + tabId);
            }
            t.put("section", section);
            t.put("tab", tabId);
            t.put("labelKey", requireText(tab.getLabelKey(), "tab.labelKey"));
            t.put("fragment", tab.getFragment() == null ? null : versioned(requireAsset(tab.getFragment())));
            t.put("feature", tab.getFeature() != null ? tab.getFeature() : feature);
            t.put("permission", tab.getPermission());
            t.put("order", tab.getOrder());
            t.put("loader", tab.getLoader() == null ? null : require(tab.getLoader(), LOADER, "tab.loader"));
            tabs.add(t);
        }
        ext.put("tabs", tabs);

        List<Map<String, Object>> fragments = new ArrayList<>();
        for (UiExtension.Fragment fragment : nullSafe(extension.fragments())) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("section", require(fragment.getSection(), NAME, "fragment.section"));
            f.put("url", versioned(requireAsset(fragment.getUrl())));
            fragments.add(f);
        }
        ext.put("fragments", fragments);
        ext.put("scripts", assets(extension.scripts()));
        ext.put("styles", assets(extension.styles()));
        ext.put("messageBundles", assets(extension.messageBundles()));
        ext.put("loginScripts", assets(extension.loginScripts()));
        ext.put("loginMessageBundles", assets(extension.loginMessageBundles()));
        return ext;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    private List<String> assets(List<String> urls) {
        List<String> result = new ArrayList<>();
        for (String url : nullSafe(urls)) {
            result.add(versioned(requireAsset(url)));
        }
        return result;
    }

    private String versioned(String url) {
        return assetVersions != null ? assetVersions.versioned(url) : url;
    }

    private static String requireAsset(String url) {
        String value = require(url, ASSET, "资源地址");
        if (value.contains("..")) {
            throw new IllegalArgumentException("资源地址不合法：" + value);
        }
        return value;
    }

    private static String require(String value, Pattern pattern, String field) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 不合法：" + value);
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }
}
