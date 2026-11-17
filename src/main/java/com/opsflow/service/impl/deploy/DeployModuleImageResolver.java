package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.dao.mapper.ServiceMapper;
import com.opsflow.integration.harbor.HarborCredentialService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 上线模块（镜像地址）的解析与规范化，以及按服务标识查服务。
 * <p>
 * 这些规则原先分散在 DeployTaskController 和 DeployTaskCdServiceImpl 里各写一份，集中到这里统一维护。
 * </p>
 */
@Component
public class DeployModuleImageResolver {

    @Autowired
    private HarborCredentialService harborCredentialService;

    @Autowired
    private ServiceMapper serviceMapper;

    /**
     * 将上线模块列表规范化为完整 Harbor 镜像地址，供入库与展示共用。
     * <p>
     * <b>规范化规则</b>（按条处理，跳过空串）：
     * <ul>
     *   <li>未配置 Registry → 原样返回（无法补全，交给下游 CD 服务报错）</li>
     *   <li>已以 {@code registry/} 开头 → 不变</li>
     *   <li>形如 {@code host-or-ip/.../service:tag}（首段含 {@code .} 或 {@code :}）→ 视为已含 registry，不变</li>
     *   <li>其余相对路径（如 {@code project/service:tag}）→ 前缀拼接 {@code registry/}</li>
     * </ul>
     * </p>
     * <p>
     * <b>与 CD 侧关系</b>：创建任务入库前调用，保证 deployModules 字段持久化为完整地址；
     * 组装展示 DTO 时读库后再次调用，兼容旧数据中的相对路径。规则与
     * {@link com.opsflow.service.impl.DeployTaskCdServiceImpl#resolveImageFullName} 语义一致。
     * </p>
     *
     * @param modules 原始或已部分规范化的模块列表
     * @return 规范化后的镜像地址列表（可能为空列表，不为 null）
     */
    public List<String> normalizeDeployModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return modules;
        }
        String registry = harborCredentialService.getRegistryHost(null);
        List<String> result = new java.util.ArrayList<>();
        for (String module : modules) {
            if (!StringUtils.hasText(module)) {
                continue;
            }
            String text = module.trim();
            if (!StringUtils.hasText(registry)) {
                result.add(text);
                continue;
            }
            String reg = registry.trim();
            if (text.startsWith(reg + "/")) {
                result.add(text);
                continue;
            }
            // 已是 host/... 形态
            int slash = text.indexOf('/');
            int colon = text.lastIndexOf(':');
            if (slash > 0 && colon > slash) {
                String first = text.substring(0, slash);
                if (first.contains(".") || first.contains(":")) {
                    result.add(text);
                    continue;
                }
            }
            result.add(reg + "/" + text);
        }
        return result;
    }

    /**
     * 从镜像地址中提取服务标识（路径最后一段，不含 tag）。
     *
     * @param module 完整或相对镜像地址，如 {@code registry/proj/service:tag}
     * @return 服务 code；无法解析时返回 {@code null}
     */
    public String extractServiceCode(String module) {
        if (!StringUtils.hasText(module)) {
            return null;
        }
        String text = module.trim();
        int colon = text.lastIndexOf(':');
        String left = colon > 0 ? text.substring(0, colon) : text;
        int slash = left.lastIndexOf('/');
        if (slash >= 0 && slash < left.length() - 1) {
            return left.substring(slash + 1).trim();
        }
        return left.trim();
    }

    /**
     * 按服务标识查服务：先按 code 精确匹配，找不到再按 name 兜底。
     *
     * @param code 服务标识，通常来自镜像路径最后一段
     * @return 服务；code 为空或找不到时返回 {@code null}
     */
    public com.opsflow.dao.model.Service findServiceByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        com.opsflow.dao.model.Service byCode = serviceMapper.selectOne(
                new QueryWrapper<com.opsflow.dao.model.Service>().eq("code", code.trim()).last("LIMIT 1"));
        if (byCode != null) {
            return byCode;
        }
        return serviceMapper.selectOne(
                new QueryWrapper<com.opsflow.dao.model.Service>().eq("name", code.trim()).last("LIMIT 1"));
    }
}
