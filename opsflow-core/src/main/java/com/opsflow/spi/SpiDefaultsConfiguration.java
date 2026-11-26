package com.opsflow.spi;

import com.opsflow.service.PermissionService;
import com.opsflow.web.security.SessionPermissionResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 单实例扩展点的核心默认实现。
 * <p>用 {@code @Bean} 注册而不是 {@code @Component}：{@code @Bean} 方法的条件在所有扫描到的组件注册之后才判断，
 * 商业版的同类型组件不论在哪个包、扫描顺序如何，都能可靠地替换掉默认实现。</p>
 * <p>多实例扩展点（{@link BuildJobLifecycleListener}、{@link ResourceReferenceChecker}、{@link StatisticsContributor}、
 * {@link UiExtension}、{@link UserAccountExtension}、{@link SystemConfigExtension}）没有默认实现，核心注入的是可能为空的列表。</p>
 */
@Configuration(proxyBeanMethods = false)
public class SpiDefaultsConfiguration {

    @Bean
    @ConditionalOnMissingBean(EditionInfo.class)
    public EditionInfo defaultEditionInfo() {
        return new DefaultEditionInfo();
    }

    @Bean
    @ConditionalOnMissingBean(JobNotifyExtension.class)
    public JobNotifyExtension defaultJobNotifyExtension() {
        return new DefaultJobNotifyExtension();
    }

    @Bean
    @ConditionalOnMissingBean(ProdDeployPolicy.class)
    public ProdDeployPolicy defaultProdDeployPolicy(SessionPermissionResolver sessionPermissionResolver,
                                                    PermissionService permissionService) {
        return new DefaultProdDeployPolicy(sessionPermissionResolver, permissionService);
    }
}
