package com.opsflow.web.assets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 静态资源版本号：取 {@code classpath:/static/} 下文件内容的 SHA-256 前 10 位。
 * <p>页面和扩展清单里的资源地址带上 {@code ?v=<版本号>} 后，浏览器可以长期缓存（见 {@link StaticAssetCacheFilter}）；
 * 文件内容一变版本号就变，不需要手工维护。不带版本号的地址仍按 no-cache 每次校验。</p>
 */
@Component
public class StaticAssetVersions {

    /** 版本号参数名 */
    public static final String PARAM = "v";

    private static final int VERSION_LENGTH = 10;
    /** 只处理站内的脚本、样式、页面片段和文案包 */
    private static final Pattern ASSET_PATH = Pattern.compile("^/[A-Za-z0-9._/-]+\\.(?:js|css|html|json)$");
    /** HTML 里的 src="/x.js" / href="/x.css"（已带查询串、站外地址不匹配） */
    private static final Pattern HTML_REF = Pattern.compile("(\\b(?:src|href)=\")(/[A-Za-z0-9._/-]+\\.(?:js|css))(\")");

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    /** 资源的版本号；路径不合法或文件不存在时返回 null */
    public String version(String path) {
        Entry entry = entry(path);
        return entry != null ? entry.version : null;
    }

    /** 给地址加上 {@code ?v=}；拿不到版本号时原样返回（仍按 no-cache 处理） */
    public String versioned(String path) {
        String version = version(path);
        return version != null ? path + "?" + PARAM + "=" + version : path;
    }

    /** 把 HTML 里引用的本地脚本和样式改成带版本号的地址 */
    public String rewriteHtml(String html) {
        Matcher matcher = HTML_REF.matcher(html);
        StringBuffer out = new StringBuffer(html.length() + 512);
        while (matcher.find()) {
            String url = versioned(matcher.group(2));
            matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + url + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private Entry entry(String path) {
        if (path == null || path.contains("..") || !ASSET_PATH.matcher(path).matches()) {
            return null;
        }
        Resource resource = new ClassPathResource("static" + path);
        try {
            Entry cached = cache.get(path);
            // 打成 jar 后内容在进程存活期间不会变；从目录运行（开发时）按修改时间和大小判断是否重算
            if (cached != null && (cached.immutable
                    || (cached.lastModified == resource.lastModified() && cached.length == resource.contentLength()))) {
                return cached;
            }
            if (!resource.isReadable()) {
                cache.remove(path);
                return null;
            }
            byte[] content;
            try (InputStream in = resource.getInputStream()) {
                content = StreamUtils.copyToByteArray(in);
            }
            boolean immutable = !"file".equals(resource.getURL().getProtocol());
            Entry entry = new Entry(hash(content), resource.lastModified(), content.length, immutable);
            cache.put(path, entry);
            return entry;
        } catch (IOException e) {
            cache.remove(path);
            return null;
        }
    }

    private static String hash(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, VERSION_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Entry {
        final String version;
        final long lastModified;
        final long length;
        final boolean immutable;

        Entry(String version, long lastModified, long length, boolean immutable) {
            this.version = version;
            this.lastModified = lastModified;
            this.length = length;
            this.immutable = immutable;
        }
    }
}
