package com.opsflow.web.audit;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/**
 * 把反序列化后的 @RequestBody 暂存到请求属性，供审计拦截器生成参数摘要（请求流只能读一次）。
 */
@ControllerAdvice
public class AuditRequestBodyAdvice extends RequestBodyAdviceAdapter {

    public static final String ATTR_BODY = AuditRequestBodyAdvice.class.getName() + ".body";

    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(ATTR_BODY, body, RequestAttributes.SCOPE_REQUEST);
        }
        return body;
    }
}
