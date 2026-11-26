package com.opsflow.web.exception;

import com.opsflow.common.exception.AccessDeniedException;
import com.opsflow.common.exception.BusinessException;
import com.opsflow.web.audit.AuditLogInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import javax.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, e.getMessage());
        logger.warn("无权限: {}", e.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("error", "无权限");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, e.getMessage());
        logger.warn("业务错误: {}", e.getMessage());
        Map<String, Object> error = new HashMap<>();
        error.put("error", "业务错误");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, e.getMessage());
        logger.error("参数错误: {}", e.getMessage(), e);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "参数错误");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /** 唯一键冲突：只告诉用户「已存在」，不回显 SQL */
    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateKey(org.springframework.dao.DuplicateKeyException e, HttpServletRequest request) {
        logger.warn("唯一键冲突: {}", rootMessage(e));
        return badRequest(request, "参数错误", "名称或唯一标识已存在，请更换后重试");
    }

    /** 必填列为空、内容超长、外键约束等 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(org.springframework.dao.DataIntegrityViolationException e, HttpServletRequest request) {
        String root = rootMessage(e);
        logger.warn("数据完整性错误: {}", root);
        String message = "提交的数据不符合要求，请检查必填项和内容长度";
        if (root.contains("Data too long")) {
            message = "内容超出长度限制，请缩短后重试";
        } else if (root.contains("doesn't have a default value") || root.contains("cannot be null")) {
            message = "缺少必填项，请补全后重试";
        } else if (root.contains("Duplicate entry")) {
            message = "名称或唯一标识已存在，请更换后重试";
        }
        return badRequest(request, "参数错误", message);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e, HttpServletRequest request) {
        return badRequest(request, "参数错误", "缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return badRequest(request, "参数错误", "参数 " + e.getName() + " 格式不正确");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e, HttpServletRequest request) {
        logger.warn("请求体无法解析: {}", rootMessage(e));
        return badRequest(request, "参数错误", "请求体格式不正确");
    }

    /** 其余数据库错误：详情只进日志，避免把 SQL 和表结构返回给前端 */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(org.springframework.dao.DataAccessException e, HttpServletRequest request) {
        String message = "数据库操作失败，请稍后重试或联系管理员";
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, message);
        logger.error("数据库错误: {}", e.getMessage(), e);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "服务器错误");
        error.put("message", message);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    private ResponseEntity<Map<String, Object>> badRequest(HttpServletRequest request, String title, String message) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, message);
        Map<String, Object> error = new HashMap<>();
        error.put("error", title);
        error.put("message", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, e.getMessage());
        logger.error("运行时错误: {}", e.getMessage(), e);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "服务器错误");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e, HttpServletRequest request) {
        request.setAttribute(AuditLogInterceptor.ATTR_ERROR_MESSAGE, e.getMessage());
        logger.error("未知错误: {}", e.getMessage(), e);
        Map<String, Object> error = new HashMap<>();
        error.put("error", "未知错误");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}


