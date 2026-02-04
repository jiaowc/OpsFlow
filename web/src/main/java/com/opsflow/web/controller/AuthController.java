package com.opsflow.web.controller;

import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 登录接口
     * 简化处理：默认用户名密码为 admin/admin
     * TODO: 后续集成真实的用户认证系统
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> loginRequest, HttpSession session) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");
        
        Map<String, Object> result = new HashMap<>();
        
        // 简化认证：默认用户名密码为 admin/admin
        if ("admin".equals(username) && "admin".equals(password)) {
            // 设置session
            session.setAttribute("user", username);
            session.setAttribute("authenticated", true);
            
            result.put("success", true);
            result.put("token", session.getId()); // 使用session ID作为token
            result.put("username", username);
            result.put("message", "登录成功");
        } else {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
        }
        
        return result;
    }
    
    /**
     * 获取当前用户信息
     */
    @GetMapping("/user")
    public Map<String, Object> getCurrentUser(HttpSession session) {
        Map<String, Object> result = new HashMap<>();
        
        String username = (String) session.getAttribute("user");
        Boolean authenticated = (Boolean) session.getAttribute("authenticated");
        
        if (authenticated != null && authenticated && username != null) {
            result.put("username", username);
            result.put("authenticated", true);
        } else {
            result.put("authenticated", false);
        }
        
        return result;
    }
    
    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "退出成功");
        return result;
    }
}



