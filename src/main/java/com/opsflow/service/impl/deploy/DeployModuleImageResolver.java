package com.opsflow.service.impl.deploy;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.common.exception.BusinessException;
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
 * 注意目前保留了两套规则：{@link #normalizeDeployModules} 宽容（创建/展示用），
 * {@link #parseModule} / {@link #resolveImageFullName} 严格（CD 建 Job 用），尚未合并。
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
     * 录入时按 CD 建 Job 的严格规则（{@link #parseModule}）预先校验模块地址。
     * <p>
     * 规范化（{@link #normalizeDeployModules}）本身很宽容，格式不对的地址也能存进任务；
     * 以前要等审批通过、CD 建 Job 时才报错，任务直接变成失败。这里把同一套检查提前到创建/编辑时，
     * 让填写的人当场看到问题。空白项不检查（规范化时会被丢弃）。
     * </p>
     *
     * @param modules 用户提交的模块地址，完整或相对路径均可
     * @throws BusinessException 任一地址不是 {@code [registry/]project/service:version} 形式
     */
    public void assertValidModules(List<String> modules) {
        if (modules == null) {
            return;
        }
        for (String module : modules) {
            if (StringUtils.hasText(module)) {
                parseModule(module);
            }
        }
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

    /**
     * 解析上线模块镜像字符串，拆出 Harbor 项目、服务标识与版本号。
     * <p>
     * <b>支持的格式</b>：
     * <ul>
     *   <li>{@code project/service:version} — 相对路径，registry 由 {@link #resolveImageFullName} 补全</li>
     *   <li>{@code registry/project/service:version} — 完整路径，registry 可含多级（如 {@code harbor.example.com/a/b}）</li>
     * </ul>
     * 以最后一个 {@code :} 分隔 tag；路径以 {@code /} 分段，倒数第二段为 project，最后一段为 serviceCode。
     * </p>
     * <p>
     * <b>业务约定</b>：serviceCode 须与服务管理中的 code（或 name）对应，供 {@link CdJobFactory#createCdJob}
     * 查找服务并注入端口；project 写入 buildParameters.harborProject 供镜像拉取步骤使用。
     * </p>
     * <p>
     * <b>边界</b>：缺少 {@code :}、分段不足 2 段、project/serviceCode/version 任一为空时抛
     * {@link BusinessException}，错误信息含原始 module 便于排查。
     * </p>
     *
     * @param module 上线模块镜像地址
     * @return 解析结果（含可选的 registry 与 imageFullName）
     * @throws BusinessException 格式不完整或无效时
     */
    public ParsedModule parseModule(String module) {
        // 支持:
        //   project/service:version
        //   registry/project/service:version
        String text = module == null ? "" : module.trim();
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon >= text.length() - 1) {
            throw new BusinessException("上线模块格式无效: " + module
                    + "，期望 registry/project/service:version 或 project/service:version");
        }
        String left = text.substring(0, colon);
        String version = text.substring(colon + 1).trim();
        String[] parts = left.split("/");
        if (parts.length < 2) {
            throw new BusinessException("上线模块格式无效: " + module
                    + "，期望 registry/project/service:version 或 project/service:version");
        }
        ParsedModule parsed = new ParsedModule();
        parsed.version = version;
        parsed.serviceCode = parts[parts.length - 1].trim();
        parsed.project = parts[parts.length - 2].trim();
        if (parts.length >= 3) {
            StringBuilder registry = new StringBuilder(parts[0].trim());
            for (int i = 1; i < parts.length - 2; i++) {
                registry.append('/').append(parts[i].trim());
            }
            parsed.registry = registry.toString();
            parsed.imageFullName = text;
        }
        if (!StringUtils.hasText(parsed.project) || !StringUtils.hasText(parsed.serviceCode)
                || !StringUtils.hasText(parsed.version)) {
            throw new BusinessException("上线模块不完整: " + module);
        }
        return parsed;
    }

    /**
     * 将上线模块规范为流水线可用的完整镜像地址 {@code registry/project/service:tag}。
     * <p>
     * <b>逻辑</b>：先 {@link #parseModule}；若解析结果已含 registry（输入为完整路径），
     * 直接返回原始文本；否则用 defaultRegistry 拼接：
     * {@code registry + "/" + project + "/" + serviceCode + ":" + version}。
     * </p>
     * <p>
     * <b>为何需要补全</b>：前端创建任务时可能只传 {@code project/service:tag}（相对路径），
     * CD 流水线的 deploy/pull 步骤需要可直连 Harbor 的 FQDN 形态；Registry 来自系统 Harbor 配置，
     * 与 {@link #normalizeDeployModules} 的规范化规则语义一致，但这里更严格：格式不完整会直接报错。
     * </p>
     * <p>
     * <b>边界</b>：相对路径且 defaultRegistry 未配置时抛 {@link BusinessException}。
     * </p>
     *
     * @param module 上线模块（完整或相对路径）
     * @param defaultRegistry 系统配置的 Harbor Registry 主机名
     * @return 完整镜像地址，可直接用于 docker pull / K8s image 字段
     * @throws BusinessException 相对路径但 Registry 未配置时
     */
    public String resolveImageFullName(String module, String defaultRegistry) {
        ParsedModule parsed = parseModule(module);
        if (StringUtils.hasText(parsed.imageFullName)) {
            return parsed.imageFullName;
        }
        if (!StringUtils.hasText(defaultRegistry)) {
            throw new BusinessException("Harbor 仓库地址未配置，无法拼接完整镜像地址");
        }
        return defaultRegistry.trim() + "/" + parsed.project + "/" + parsed.serviceCode + ":" + parsed.version;
    }

    /** 上线模块镜像地址解析结果。 */
    public static class ParsedModule {
        String registry;
        String project;
        String serviceCode;
        String version;
        String imageFullName;
    }
}
