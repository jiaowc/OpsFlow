package com.opsflow.web.assets;

import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;
import org.springframework.web.servlet.resource.ResourceTransformer;
import org.springframework.web.servlet.resource.ResourceTransformerChain;
import org.springframework.web.servlet.resource.TransformedResource;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 返回页面（dashboard.html、index.html）时，把里面引用的本地脚本和样式改成带版本号的地址。
 * <p>页面本身是 no-cache 且不带 Last-Modified：每次都返回完整内容，不走 304。
 * 按修改时间校验在回滚到旧版本时会出错（旧包的时间更早，浏览器手里的新页面被判定为"未修改"），
 * 而页面是版本号的唯一来源，必须保证拿到的就是当前这一版。</p>
 * <p>带版本号请求的 HTML（扩展的页面片段）不改写：它的版本号只按自身内容算。</p>
 */
public class HtmlAssetVersionTransformer implements ResourceTransformer {

    private final StaticAssetVersions versions;

    public HtmlAssetVersionTransformer(StaticAssetVersions versions) {
        this.versions = versions;
    }

    @Override
    public Resource transform(HttpServletRequest request, Resource resource, ResourceTransformerChain chain)
            throws IOException {
        Resource transformed = chain.transform(request, resource);
        String filename = transformed.getFilename();
        if (filename == null || !filename.endsWith(".html") || request.getQueryString() != null) {
            return transformed;
        }
        String html;
        try (InputStream in = transformed.getInputStream()) {
            html = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        return new VersionedHtml(transformed, versions.rewriteHtml(html).getBytes(StandardCharsets.UTF_8));
    }

    private static final class VersionedHtml extends TransformedResource {

        VersionedHtml(Resource original, byte[] content) {
            super(original, content);
        }

        /** 负数：资源处理器不输出 Last-Modified，也不会返回 304 */
        @Override
        public long lastModified() {
            return -1;
        }
    }
}
