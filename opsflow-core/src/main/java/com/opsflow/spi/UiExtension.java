package com.opsflow.spi;

import java.util.Collections;
import java.util.List;

/**
 * 前端扩展声明：菜单项、页签、页面片段、脚本、样式、文案包。可以有多个实现。
 * <p>核心通过 {@code GET /api/ui/extensions} 汇总给前端（{@code js/ui-extensions.js} 动态插入），
 * 并为每个菜单的 {@link MenuItem#getPath()} 注册页面路由。所有地址都相对 {@code static/}，以 {@code /} 开头。</p>
 * <p>{@link #feature()}（或菜单项、页签的 feature）未开通时，扩展（或菜单项、页签）不出现在清单里。
 * 菜单和页签的可见性沿用页面上的 {@code data-feature} / {@code data-permission}，后端不按用户过滤；
 * 真正的访问控制仍由扩展自己的业务 API 负责。</p>
 */
public interface UiExtension {

    /** 唯一标识，如 {@code audit}；前端以它为单位隔离加载失败 */
    String id();

    /** 整个扩展依赖的功能码，作为菜单项 feature 的默认值；null 表示不依赖 */
    default String feature() {
        return null;
    }

    default List<MenuItem> menuItems() {
        return Collections.emptyList();
    }

    default List<Fragment> fragments() {
        return Collections.emptyList();
    }

    /**
     * 往已有页面（核心或其他扩展的区块）追加的页签。可以只声明页签、不声明菜单。
     * 页签的 feature 为空时用扩展的 {@link #feature()}；扩展的 feature 也为空时，各页签按自己的 feature 过滤。
     */
    default List<Tab> tabs() {
        return Collections.emptyList();
    }

    /** 按顺序加载的脚本 */
    default List<String> scripts() {
        return Collections.emptyList();
    }

    default List<String> styles() {
        return Collections.emptyList();
    }

    /** 文案包（JSON：{"zh":{...},"en":{...}}），不覆盖核心已有的 key */
    default List<String> messageBundles() {
        return Collections.emptyList();
    }

    /**
     * 登录页要加载的脚本（如品牌、第三方登录按钮）。登录前即可获取（{@code GET /api/ui/login-extensions}），
     * 脚本自己调用的接口如需登录前访问，要另外通过 {@link WebSecurityExtension} 放开。
     */
    default List<String> loginScripts() {
        return Collections.emptyList();
    }

    /** 登录页脚本用到的文案包，格式同 {@link #messageBundles()} */
    default List<String> loginMessageBundles() {
        return Collections.emptyList();
    }

    /** 左侧菜单项 */
    final class MenuItem {
        private final String section;
        private final String path;
        private final String labelKey;
        private String icon;
        private String permission;
        private String feature;
        private String after;
        private int order;
        private String loader;

        /**
         * @param section  区块 id，前端对应 {@code #<section>-section}
         * @param path     页面路径，如 {@code /audit}（只允许小写字母、数字和横线）
         * @param labelKey 菜单文字的 i18n key
         */
        public MenuItem(String section, String path, String labelKey) {
            this.section = section;
            this.path = path;
            this.labelKey = labelKey;
        }

        /** 内联 SVG 的内部元素（path/rect/circle 等），外层 svg 由前端生成 */
        public MenuItem icon(String icon) {
            this.icon = icon;
            return this;
        }

        /** 对应 data-permission，可逗号分隔表示任一 */
        public MenuItem permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** 对应 data-feature；为空时用扩展的 {@link UiExtension#feature()} */
        public MenuItem feature(String feature) {
            this.feature = feature;
            return this;
        }

        /** 插在哪个已有菜单（data-section）之后；为空或找不到时放到主菜单末尾 */
        public MenuItem after(String after) {
            this.after = after;
            return this;
        }

        /** 同一位置有多个扩展菜单时的先后，越小越靠前 */
        public MenuItem order(int order) {
            this.order = order;
            return this;
        }

        /** 进入该页时调用的全局函数名，如 {@code loadAuditPage} */
        public MenuItem loader(String loader) {
            this.loader = loader;
            return this;
        }

        public String getSection() {
            return section;
        }

        public String getPath() {
            return path;
        }

        public String getLabelKey() {
            return labelKey;
        }

        public String getIcon() {
            return icon;
        }

        public String getPermission() {
            return permission;
        }

        public String getFeature() {
            return feature;
        }

        public String getAfter() {
            return after;
        }

        public int getOrder() {
            return order;
        }

        public String getLoader() {
            return loader;
        }
    }

    /** 追加到已有页面的页签 */
    final class Tab {
        private final String section;
        private final String tab;
        private final String labelKey;
        private String fragment;
        private String feature;
        private String permission;
        private int order;
        private String loader;

        /**
         * @param section  目标页面的区块 id（对应 {@code #<section>-section}）
         * @param tab      页签 id，同一个 section 下唯一（跨扩展也不能重复）
         * @param labelKey 页签文字的 i18n key
         */
        public Tab(String section, String tab, String labelKey) {
            this.section = section;
            this.tab = tab;
            this.labelKey = labelKey;
        }

        /** 页签内容的 HTML 片段地址（相对 {@code static/}，以 {@code /} 开头） */
        public Tab fragment(String fragment) {
            this.fragment = fragment;
            return this;
        }

        /** 对应 data-feature，可逗号分隔表示任一；为空时用扩展的 {@link UiExtension#feature()} */
        public Tab feature(String feature) {
            this.feature = feature;
            return this;
        }

        /** 对应 data-permission，可逗号分隔表示任一 */
        public Tab permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** 同一页面里扩展页签的先后，越小越靠前 */
        public Tab order(int order) {
            this.order = order;
            return this;
        }

        /** 进入该页签时调用的全局函数名 */
        public Tab loader(String loader) {
            this.loader = loader;
            return this;
        }

        public String getSection() {
            return section;
        }

        public String getTab() {
            return tab;
        }

        public String getLabelKey() {
            return labelKey;
        }

        public String getFragment() {
            return fragment;
        }

        public String getFeature() {
            return feature;
        }

        public String getPermission() {
            return permission;
        }

        public int getOrder() {
            return order;
        }

        public String getLoader() {
            return loader;
        }
    }

    /** 页面片段：区块内部的 HTML，外层 {@code <div id="<section>-section" class="content-section">} 由前端生成 */
    final class Fragment {
        private final String section;
        private final String url;

        public Fragment(String section, String url) {
            this.section = section;
            this.url = url;
        }

        public String getSection() {
            return section;
        }

        public String getUrl() {
            return url;
        }
    }
}
