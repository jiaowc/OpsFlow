package com.opsflow.web.audit;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * 部分接口失败时仍返回 HTTP 200 + {"success": false, "message": ...}，在这里识别出来供审计记录为失败。
 */
@ControllerAdvice
public class AuditResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    public static final String ATTR_FAILED = AuditResponseBodyAdvice.class.getName() + ".failed";

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof Map && request instanceof ServletServerHttpRequest
                && Boolean.FALSE.equals(((Map<?, ?>) body).get("success"))) {
            Object message = ((Map<?, ?>) body).get("message");
            ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
            servletRequest.getServletRequest().setAttribute(ATTR_FAILED, Boolean.TRUE);
            if (message != null) {
                servletRequest.getServletRequest().setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, String.valueOf(message));
            }
        }
        return body;
    }
}
