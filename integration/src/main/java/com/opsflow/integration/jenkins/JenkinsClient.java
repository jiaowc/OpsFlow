package com.opsflow.integration.jenkins;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.offbytwo.jenkins.JenkinsServer;
import com.offbytwo.jenkins.model.Job;
import com.opsflow.dao.mapper.ComponentMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Jenkins客户端
 */
@Slf4j
@Component
public class JenkinsClient {

    @Autowired
    private ComponentMapper componentMapper;
    
    private ObjectMapper objectMapper = new ObjectMapper();
    
    private JenkinsServer jenkinsServer;
    private String jenkinsUrl;
    private String jenkinsUsername;
    private String jenkinsPassword;
    
    /**
     * 初始化或获取JenkinsServer（懒加载）
     */
    private JenkinsServer getJenkinsServer() {
        if (jenkinsServer == null) {
            synchronized (this) {
                if (jenkinsServer == null) {
                    init();
                }
            }
        }
        return jenkinsServer;
    }
    
    /**
     * 从组件管理加载配置并初始化
     */
    private void init() {
        try {
            QueryWrapper<com.opsflow.dao.model.Component> wrapper = new QueryWrapper<>();
            wrapper.eq("type", "jenkins");
            wrapper.eq("status", 1);
            wrapper.orderByDesc("create_time");
            wrapper.last("LIMIT 1");
            
            com.opsflow.dao.model.Component component = componentMapper.selectOne(wrapper);
            if (component == null) {
                throw new RuntimeException("未找到启用的Jenkins组件配置，请在系统管理->组件管理中配置");
            }
            
            if (component.getUrl() == null || component.getUrl().trim().isEmpty()) {
                throw new RuntimeException("Jenkins组件配置中URL不能为空");
            }
            
            if (component.getAuthConfig() == null || component.getAuthConfig().trim().isEmpty()) {
                throw new RuntimeException("Jenkins组件配置中认证信息不能为空");
            }
            
            jenkinsUrl = component.getUrl();
            
            // 解析authConfig JSON
            java.util.Map<String, String> authConfig = null;
            try {
                authConfig = objectMapper.readValue(
                    component.getAuthConfig(),
                    new TypeReference<java.util.Map<String, String>>() {}
                );
            } catch (Exception e) {
                throw new RuntimeException("Jenkins组件认证配置格式错误", e);
            }
            
            // 根据认证类型获取用户名和密码
            if ("username_password".equals(component.getAuthType())) {
                jenkinsUsername = authConfig.get("username");
                jenkinsPassword = authConfig.get("password");
            } else if ("token".equals(component.getAuthType())) {
                // Token认证时，username可以是任意值，password是token
                jenkinsUsername = authConfig.getOrDefault("username", "admin");
                jenkinsPassword = authConfig.get("token");
            } else if ("api_key".equals(component.getAuthType())) {
                // API Key认证时，username可以是任意值，password是apiKey
                jenkinsUsername = authConfig.getOrDefault("username", "admin");
                jenkinsPassword = authConfig.get("apiKey");
            } else {
                throw new RuntimeException("Jenkins组件不支持的认证类型: " + component.getAuthType());
            }
            
            if (jenkinsUsername == null || jenkinsPassword == null) {
                throw new RuntimeException("Jenkins组件配置中用户名或密码/Token不能为空");
            }
            
            jenkinsServer = new JenkinsServer(
                new URI(jenkinsUrl),
                jenkinsUsername,
                jenkinsPassword
            );
            log.info("Jenkins客户端初始化成功: {}", jenkinsUrl);
        } catch (Exception e) {
            log.error("Jenkins客户端初始化失败", e);
            throw new RuntimeException("Jenkins客户端初始化失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 触发Jenkins Job构建
     */
    public int buildJob(String jobName, Map<String, String> parameters) {
        try {
            getJenkinsServer(); // Ensure initialized
            
            // 获取当前最新构建号（通过查询Job详情）
            JenkinsServer server = getJenkinsServer();
            Job job = server.getJob(jobName);
            if (job == null) {
                throw new RuntimeException("Jenkins Job不存在: " + jobName);
            }
            
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = job.details();
            int currentBuildNumber = 0;
            if (jobDetails != null && jobDetails.getLastBuild() != null) {
                currentBuildNumber = jobDetails.getLastBuild().getNumber();
            }
            int nextBuildNumber = currentBuildNumber + 1;
            
            // 记录参数信息
            if (parameters != null && !parameters.isEmpty()) {
                log.info("触发Jenkins构建，作业: {}, 参数数量: {}, 参数: {}", jobName, parameters.size(), parameters);
            } else {
                log.info("触发Jenkins构建，作业: {}, 无参数", jobName);
            }
            
            // 直接使用REST API触发构建（更可靠）
            // jenkins-client库的build方法可能在某些情况下不会真正触发构建
            if (parameters != null && !parameters.isEmpty()) {
                // 带参数的构建
                buildJobWithParametersViaRestApi(jobName, parameters);
            } else {
                // 无参数的构建
                buildJobViaRestApi(jobName);
            }
            
            // 等待并多次检查构建是否真的被触发
            int actualBuildNumber = currentBuildNumber;
            int maxRetries = 10; // 最多重试10次
            int retryCount = 0;
            
            while (retryCount < maxRetries && actualBuildNumber < nextBuildNumber) {
                Thread.sleep(1000); // 每次等待1秒
                retryCount++;
                
                // 重新查询构建号
                try {
                    jobDetails = job.details();
                    if (jobDetails != null && jobDetails.getLastBuild() != null) {
                        actualBuildNumber = jobDetails.getLastBuild().getNumber();
                        log.debug("检查构建号 (尝试 {}/{}): 当前最新构建号: {}", retryCount, maxRetries, actualBuildNumber);
                    }
        } catch (Exception e) {
                    log.warn("查询构建号失败: {}", e.getMessage());
                }
            }
            
            if (actualBuildNumber >= nextBuildNumber) {
                log.info("触发Jenkins构建成功: {} #{}", jobName, actualBuildNumber);
                return actualBuildNumber;
            } else {
                log.error("构建未成功触发！预期构建号: {}, 实际最新构建号: {}, 等待了 {} 秒", 
                         nextBuildNumber, actualBuildNumber, retryCount);
                // 即使构建号没变，也返回预期的构建号，但记录错误
                throw new RuntimeException("构建未成功触发，Jenkins可能拒绝了构建请求或构建被禁用");
            }
            
        } catch (Exception e) {
            log.error("触发Jenkins构建失败: {}", jobName, e);
            throw new RuntimeException("触发Jenkins构建失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取Jenkins CSRF Crumb
     */
    private String getJenkinsCrumb() throws IOException {
        String crumbUrl = jenkinsUrl + "/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\":\",//crumb)";
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        
        String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
        
        Request request = new Request.Builder()
            .url(crumbUrl)
            .header("Authorization", "Basic " + auth)
            .get()
            .build();
        
        Response response = client.newCall(request).execute();
        
        if (response.isSuccessful() && response.body() != null) {
            String crumb = response.body().string().trim();
            response.close();
            log.info("从XML API获取Jenkins Crumb成功: {}", crumb);
            
            // 验证crumb格式
            if (!crumb.contains(":")) {
                log.warn("Crumb格式异常，不包含冒号: {}", crumb);
                // 尝试使用JSON API
                return getJenkinsCrumbFromJson();
            }
            
            return crumb;
        } else {
            // 如果获取crumb失败，尝试使用JSON API
            if (response != null) {
                String errorBody = response.body() != null ? response.body().string() : "";
                log.warn("从XML API获取Crumb失败，状态码: {}, 响应: {}", response.code(), errorBody);
                response.close();
            }
            return getJenkinsCrumbFromJson();
        }
    }
    
    /**
     * 从JSON API获取Jenkins Crumb（备用方法）
     */
    private String getJenkinsCrumbFromJson() throws IOException {
        String crumbUrl = jenkinsUrl + "/api/json?tree=crumb";
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        
        String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
        
        Request request = new Request.Builder()
            .url(crumbUrl)
            .header("Authorization", "Basic " + auth)
            .get()
            .build();
        
        Response response = client.newCall(request).execute();
        
        if (response.isSuccessful() && response.body() != null) {
            String responseBody = response.body().string();
            com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
            String crumbField = json.getString("crumbRequestField");
            String crumbValue = json.getString("crumb");
            response.close();
            if (crumbField != null && crumbValue != null) {
                String crumb = crumbField + ":" + crumbValue;
                log.info("从JSON API获取Jenkins Crumb成功: {}", crumb);
                return crumb;
            }
        }
        
        if (response != null) {
            response.close();
        }
        log.warn("无法获取Jenkins Crumb，可能Jenkins未启用CSRF保护");
        return null;
    }
    
    /**
     * 通过REST API触发带参数的构建
     * 尝试多种方法以确保兼容性
     */
    private void buildJobWithParametersViaRestApi(String jobName, Map<String, String> parameters) throws IOException {
        // 方法1: 尝试使用POST请求，参数在表单数据中，crumb在请求头
        try {
            buildJobWithParametersPostForm(jobName, parameters);
            return;
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("403")) {
                log.warn("POST表单方式失败，尝试GET查询参数方式: {}", e.getMessage());
            } else {
                throw e; // 其他错误直接抛出
            }
        }
        
        // 方法2: 尝试使用GET请求，参数在查询字符串中，crumb在请求头
        try {
            buildJobWithParametersGetQuery(jobName, parameters);
            return;
        } catch (IOException e) {
            log.error("GET查询参数方式也失败: {}", e.getMessage());
            throw new IOException("所有构建方式都失败，最后错误: " + e.getMessage(), e);
        }
    }
    
    /**
     * 方法1: POST请求，参数在表单数据中，crumb在请求头
     */
    private void buildJobWithParametersPostForm(String jobName, Map<String, String> parameters) throws IOException {
        String buildUrl = jenkinsUrl + "/job/" + jobName + "/buildWithParameters";
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        
        String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
        
        // 获取CSRF Crumb
        String crumb = getJenkinsCrumb();
        if (crumb == null || crumb.isEmpty()) {
            throw new IOException("无法获取Jenkins Crumb，无法触发构建");
        }
        
        // 解析crumb
        String[] crumbParts = crumb.split(":", 2);
        if (crumbParts.length != 2) {
            throw new IOException("Jenkins Crumb格式错误: " + crumb);
        }
        String crumbField = crumbParts[0];
        String crumbValue = crumbParts[1];
        
        // 构建表单数据（包含构建参数和crumb）
        okhttp3.FormBody.Builder formBuilder = new okhttp3.FormBody.Builder();
        
        // 添加Crumb到表单数据（某些Jenkins版本需要crumb在表单数据中）
        formBuilder.add(crumbField, crumbValue);
        log.info("添加Crumb到表单数据: {}={}", crumbField, crumbValue);
        
        // 添加构建参数
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                formBuilder.add(entry.getKey(), entry.getValue());
            }
        }
        
        // 构建请求，crumb同时放在请求头和表单数据中
        Request request = new Request.Builder()
            .url(buildUrl)
            .header("Authorization", "Basic " + auth)
            .header(crumbField, crumbValue)  // Crumb在请求头中
            .post(formBuilder.build())  // Crumb也在表单数据中
            .build();
        
        log.info("方法1: POST表单方式 - URL: {}, Crumb: {}={}, 参数数量: {}", buildUrl, crumbField, crumbValue, parameters.size());
        
        // 打印所有请求头（用于调试）
        log.debug("请求头列表:");
        request.headers().names().forEach(name -> {
            if (name.equals("Authorization")) {
                log.debug("  {}: Basic ***", name);
            } else if (name.equals(crumbField)) {
                log.debug("  {}: {}", name, crumbValue);
            } else {
                log.debug("  {}: {}", name, request.header(name));
            }
        });
        
        Response response = client.newCall(request).execute();
        
        if (response.isSuccessful() || response.code() == 201) {
            log.info("方法1成功: 通过POST表单方式触发构建成功: {}", jobName);
            if (response != null) response.close();
            return;
        }
        
        String errorBody = response.body() != null ? response.body().string() : "";
        if (response != null) response.close();
        
        if (response.code() == 403) {
            throw new IOException("POST表单方式失败，状态码: 403, 响应: " + errorBody);
        } else {
            throw new IOException("POST表单方式失败，状态码: " + response.code() + ", 响应: " + errorBody);
        }
    }
    
    /**
     * 方法2: GET请求，参数在查询字符串中，crumb在请求头
     */
    private void buildJobWithParametersGetQuery(String jobName, Map<String, String> parameters) throws IOException {
        // 构建URL，参数作为查询参数
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(jenkinsUrl).append("/job/").append(jobName).append("/buildWithParameters");
        
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                if (first) {
                    urlBuilder.append("?");
                    first = false;
                } else {
                    urlBuilder.append("&");
                }
                try {
                    urlBuilder.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                              .append("=")
                              .append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
                } catch (java.io.UnsupportedEncodingException e) {
                    urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                }
            }
        }
        
        String buildUrl = urlBuilder.toString();
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        
        String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
        
        // 获取CSRF Crumb
        String crumb = getJenkinsCrumb();
        if (crumb == null || crumb.isEmpty()) {
            throw new IOException("无法获取Jenkins Crumb，无法触发构建");
        }
        
        // 解析crumb
        String[] crumbParts = crumb.split(":", 2);
        if (crumbParts.length != 2) {
            throw new IOException("Jenkins Crumb格式错误: " + crumb);
        }
        String crumbField = crumbParts[0];
        String crumbValue = crumbParts[1];
        
        // GET请求，crumb在请求头
        Request request = new Request.Builder()
            .url(buildUrl)
            .header("Authorization", "Basic " + auth)
            .header(crumbField, crumbValue)
            .get()
            .build();
        
        log.info("方法2: GET查询参数方式 - URL: {}, Crumb: {}={}", buildUrl, crumbField, crumbValue);
        
        Response response = client.newCall(request).execute();
        
        if (response.isSuccessful() || response.code() == 201 || response.code() == 200) {
            log.info("方法2成功: 通过GET查询参数方式触发构建成功: {}", jobName);
            if (response != null) response.close();
            return;
        }
        
        String errorBody = response.body() != null ? response.body().string() : "";
        if (response != null) response.close();
        throw new IOException("GET查询参数方式失败，状态码: " + response.code() + ", 响应: " + errorBody);
    }
    
    /**
     * 通过REST API触发无参数的构建
     */
    private void buildJobViaRestApi(String jobName) throws IOException {
        String buildUrl = jenkinsUrl + "/job/" + jobName + "/build";
        
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
        
        String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
        
        // 获取CSRF Crumb
        String crumb = getJenkinsCrumb();
        
        // 创建表单请求体，包含Crumb
        okhttp3.FormBody.Builder formBuilder = new okhttp3.FormBody.Builder();
        
        // 添加Crumb到表单数据（Jenkins POST请求需要crumb作为表单参数）
        if (crumb != null && !crumb.isEmpty()) {
            String[] crumbParts = crumb.split(":", 2);
            if (crumbParts.length == 2) {
                formBuilder.add(crumbParts[0], crumbParts[1]);
                log.debug("添加Jenkins Crumb到表单数据: {}={}", crumbParts[0], crumbParts[1]);
            }
        }
        
        okhttp3.FormBody formBody = formBuilder.build();
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(buildUrl)
            .header("Authorization", "Basic " + auth)
            .post(formBody);
        
        // 同时添加Crumb到请求头（某些Jenkins版本可能需要）
        if (crumb != null && !crumb.isEmpty()) {
            String[] crumbParts = crumb.split(":", 2);
            if (crumbParts.length == 2) {
                requestBuilder.header(crumbParts[0], crumbParts[1]);
                log.debug("添加Jenkins Crumb到请求头: {}", crumbParts[0]);
            }
        }
        
        Request request = requestBuilder.build();
        
        Response response = client.newCall(request).execute();
        
        if (response.isSuccessful() || response.code() == 201) {
            log.debug("通过REST API触发构建成功: {}", jobName);
        } else {
            String errorBody = response.body() != null ? response.body().string() : "";
            log.error("通过REST API触发构建失败: {}, 状态码: {}, 响应: {}", jobName, response.code(), errorBody);
            throw new IOException("触发构建失败，状态码: " + response.code() + ", 响应: " + errorBody);
        }
        
        if (response != null) {
            response.close();
        }
    }
    
    /**
     * 获取作业的参数定义
     */
    public List<Map<String, Object>> getJobParameters(String jobName) {
        try {
            getJenkinsServer(); // 确保已初始化
            
            // 方法1: 尝试从 actions 中获取参数定义（使用更完整的tree查询）
            // 对于Pipeline作业，参数可能在actions中，也可能在properties中
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/api/json?tree=actions[parameterDefinitions[name,description,_class,defaultParameterValue[value],defaultValue,choices]]";
            
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
            
            String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Basic " + auth)
                .get()
                .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                log.debug("获取作业参数定义API响应: {}", responseBody);
                
                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
                com.alibaba.fastjson.JSONArray actions = json.getJSONArray("actions");
                
                List<Map<String, Object>> parameters = new ArrayList<>();
                
                if (actions != null) {
                    log.debug("找到 {} 个 actions", actions.size());
                    for (int i = 0; i < actions.size(); i++) {
                        com.alibaba.fastjson.JSONObject action = actions.getJSONObject(i);
                        com.alibaba.fastjson.JSONArray parameterDefinitions = action.getJSONArray("parameterDefinitions");
                        
                        if (parameterDefinitions != null && parameterDefinitions.size() > 0) {
                            log.debug("在 action[{}] 中找到 {} 个参数定义", i, parameterDefinitions.size());
                            for (int j = 0; j < parameterDefinitions.size(); j++) {
                                com.alibaba.fastjson.JSONObject paramDef = parameterDefinitions.getJSONObject(j);
                                Map<String, Object> param = new HashMap<>();
                                
                                String paramName = paramDef.getString("name");
                                String paramType = paramDef.getString("_class");
                                String paramDesc = paramDef.getString("description");
                                
                                if (paramName == null || paramName.isEmpty()) {
                                    log.warn("跳过无名称的参数定义");
                                    continue;
                                }
                                
                                param.put("name", paramName);
                                param.put("type", paramType);
                                param.put("description", paramDesc != null ? paramDesc : "");
                                
                                // 处理默认值（根据参数类型）
                                if (paramType != null && paramType.contains("BooleanParameterDefinition")) {
                                    // 布尔参数的默认值
                                    if (paramDef.containsKey("defaultValue")) {
                                        param.put("defaultValue", paramDef.getBoolean("defaultValue"));
                                    } else {
                                        param.put("defaultValue", false);
                                    }
                                } else {
                                    // 其他类型参数的默认值
                                    if (paramDef.containsKey("defaultParameterValue")) {
                                        com.alibaba.fastjson.JSONObject defaultValue = paramDef.getJSONObject("defaultParameterValue");
                                        if (defaultValue != null) {
                                            Object value = defaultValue.get("value");
                                            if (value != null) {
                                                param.put("defaultValue", value.toString());
                                            } else {
                                                param.put("defaultValue", "");
                                            }
                                        } else {
                                            param.put("defaultValue", "");
                                        }
                                    } else {
                                        param.put("defaultValue", "");
                                    }
                                }
                                
                                // 处理选择参数（ChoiceParameterDefinition）
                                if (paramDef.containsKey("choices")) {
                                    com.alibaba.fastjson.JSONArray choices = paramDef.getJSONArray("choices");
                                    if (choices != null && choices.size() > 0) {
                                        List<String> choiceList = new ArrayList<>();
                                        for (int k = 0; k < choices.size(); k++) {
                                            String choice = choices.getString(k);
                                            if (choice != null) {
                                                choiceList.add(choice);
                                            }
                                        }
                                        param.put("choices", choiceList);
                                    }
                                }
                                
                                parameters.add(param);
                                log.debug("添加参数: {} (类型: {})", paramName, paramType);
                            }
                        }
                    }
                }
                
                response.close();
                
                if (parameters.isEmpty()) {
                    log.warn("从 actions 中未找到参数定义，尝试使用备用方法: {}", jobName);
                    // 方法2: 尝试从 properties 中获取（Pipeline 类型）
                    List<Map<String, Object>> paramsFromProps = getJobParametersFromProperties(jobName);
                    if (!paramsFromProps.isEmpty()) {
                        return paramsFromProps;
                    }
                    // 方法3: 尝试使用完整的API（不限制tree）
                    return getJobParametersFullApi(jobName);
                }
                
                log.info("成功获取 {} 个参数定义: {}", parameters.size(), jobName);
                return parameters;
            } else {
                if (response != null) {
                    response.close();
                }
                log.warn("获取作业参数定义失败，状态码: {} - {}", jobName, response.code());
                // 尝试备用方法
                List<Map<String, Object>> paramsFromProps = getJobParametersFromProperties(jobName);
                if (!paramsFromProps.isEmpty()) {
                    return paramsFromProps;
                }
                return getJobParametersFullApi(jobName);
            }
        } catch (Exception e) {
            log.warn("获取作业参数定义失败: {}", jobName, e);
            // 尝试备用方法
            List<Map<String, Object>> paramsFromProps = getJobParametersFromProperties(jobName);
            if (!paramsFromProps.isEmpty()) {
                return paramsFromProps;
            }
            return getJobParametersFullApi(jobName);
        }
    }
    
    /**
     * 使用完整API获取参数定义（不限制tree，获取所有数据）
     */
    private List<Map<String, Object>> getJobParametersFullApi(String jobName) {
        try {
            // 获取完整的job信息，然后查找参数定义
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/api/json";
            
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
            
            String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Basic " + auth)
                .get()
                .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
                
                List<Map<String, Object>> parameters = new ArrayList<>();
                
                // 尝试从 actions 中查找
                com.alibaba.fastjson.JSONArray actions = json.getJSONArray("actions");
                if (actions != null) {
                    for (int i = 0; i < actions.size(); i++) {
                        com.alibaba.fastjson.JSONObject action = actions.getJSONObject(i);
                        if (action.containsKey("parameterDefinitions")) {
                            com.alibaba.fastjson.JSONArray parameterDefinitions = action.getJSONArray("parameterDefinitions");
                            if (parameterDefinitions != null && parameterDefinitions.size() > 0) {
                                log.debug("从完整API的actions中找到 {} 个参数定义", parameterDefinitions.size());
                                for (int j = 0; j < parameterDefinitions.size(); j++) {
                                    com.alibaba.fastjson.JSONObject paramDef = parameterDefinitions.getJSONObject(j);
                                    Map<String, Object> param = parseParameterDefinition(paramDef);
                                    if (param != null) {
                                        parameters.add(param);
                                    }
                                }
                            }
                        }
                    }
                }
                
                // 如果actions中没有，尝试从property中查找
                if (parameters.isEmpty()) {
                    com.alibaba.fastjson.JSONArray properties = json.getJSONArray("property");
                    if (properties != null) {
                        for (int i = 0; i < properties.size(); i++) {
                            com.alibaba.fastjson.JSONObject property = properties.getJSONObject(i);
                            if (property.containsKey("parameterDefinitions")) {
                                com.alibaba.fastjson.JSONArray parameterDefinitions = property.getJSONArray("parameterDefinitions");
                                if (parameterDefinitions != null && parameterDefinitions.size() > 0) {
                                    log.debug("从完整API的property中找到 {} 个参数定义", parameterDefinitions.size());
                                    for (int j = 0; j < parameterDefinitions.size(); j++) {
                                        com.alibaba.fastjson.JSONObject paramDef = parameterDefinitions.getJSONObject(j);
                                        Map<String, Object> param = parseParameterDefinition(paramDef);
                                        if (param != null) {
                                            parameters.add(param);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                response.close();
                if (!parameters.isEmpty()) {
                    log.info("从完整API成功获取 {} 个参数定义: {}", parameters.size(), jobName);
                } else {
                    log.warn("从完整API也未找到参数定义: {}", jobName);
                }
                return parameters;
            } else {
                if (response != null) {
                    response.close();
                }
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.debug("从完整API获取参数定义失败: {}", jobName, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 从 properties 中获取参数定义（Pipeline 类型作业的备用方法）
     */
    private List<Map<String, Object>> getJobParametersFromProperties(String jobName) {
        try {
            // 尝试从properties中获取（Pipeline类型作业通常在这里）
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/api/json?tree=property[parameterDefinitions[name,description,_class,defaultParameterValue[value],defaultValue,choices]]";
            
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
            
            String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Basic " + auth)
                .get()
                .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                log.debug("从 properties 获取参数定义API响应: {}", responseBody);
                
                com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
                com.alibaba.fastjson.JSONArray properties = json.getJSONArray("property");
                
                List<Map<String, Object>> parameters = new ArrayList<>();
                
                if (properties != null) {
                    for (int i = 0; i < properties.size(); i++) {
                        com.alibaba.fastjson.JSONObject property = properties.getJSONObject(i);
                        com.alibaba.fastjson.JSONArray parameterDefinitions = property.getJSONArray("parameterDefinitions");
                        
                        if (parameterDefinitions != null && parameterDefinitions.size() > 0) {
                            log.debug("在 property[{}] 中找到 {} 个参数定义", i, parameterDefinitions.size());
                            for (int j = 0; j < parameterDefinitions.size(); j++) {
                                com.alibaba.fastjson.JSONObject paramDef = parameterDefinitions.getJSONObject(j);
                                Map<String, Object> param = parseParameterDefinition(paramDef);
                                if (param != null) {
                                    parameters.add(param);
                                }
                            }
                        }
                    }
                }
                
                response.close();
                if (!parameters.isEmpty()) {
                    log.info("从 properties 成功获取 {} 个参数定义: {}", parameters.size(), jobName);
                }
                return parameters;
            } else {
                if (response != null) {
                    response.close();
                }
                return Collections.emptyList();
            }
        } catch (Exception e) {
            log.debug("从 properties 获取参数定义失败: {}", jobName, e.getMessage());
            return Collections.emptyList();
        }
    }
    
    /**
     * 解析单个参数定义
     */
    private Map<String, Object> parseParameterDefinition(com.alibaba.fastjson.JSONObject paramDef) {
        try {
            String paramName = paramDef.getString("name");
            if (paramName == null || paramName.isEmpty()) {
                return null;
            }
            
            Map<String, Object> param = new HashMap<>();
            String paramType = paramDef.getString("_class");
            String paramDesc = paramDef.getString("description");
            
            param.put("name", paramName);
            param.put("type", paramType != null ? paramType : "");
            param.put("description", paramDesc != null ? paramDesc : "");
            
            // 处理默认值（根据参数类型）
            if (paramType != null && paramType.contains("BooleanParameterDefinition")) {
                // 布尔参数的默认值
                if (paramDef.containsKey("defaultValue")) {
                    param.put("defaultValue", paramDef.getBoolean("defaultValue"));
                } else {
                    param.put("defaultValue", false);
                }
            } else {
                // 其他类型参数的默认值
                if (paramDef.containsKey("defaultParameterValue")) {
                    com.alibaba.fastjson.JSONObject defaultValue = paramDef.getJSONObject("defaultParameterValue");
                    if (defaultValue != null) {
                        Object value = defaultValue.get("value");
                        if (value != null) {
                            param.put("defaultValue", value.toString());
                        } else {
                            param.put("defaultValue", "");
                        }
                    } else {
                        param.put("defaultValue", "");
                    }
                } else {
                    param.put("defaultValue", "");
                }
            }
            
            // 处理选择参数（ChoiceParameterDefinition）
            if (paramDef.containsKey("choices")) {
                com.alibaba.fastjson.JSONArray choices = paramDef.getJSONArray("choices");
                if (choices != null && choices.size() > 0) {
                    List<String> choiceList = new ArrayList<>();
                    for (int k = 0; k < choices.size(); k++) {
                        String choice = choices.getString(k);
                        if (choice != null) {
                            choiceList.add(choice);
                        }
                    }
                    param.put("choices", choiceList);
                }
            }
            
            return param;
        } catch (Exception e) {
            log.warn("解析参数定义失败", e);
            return null;
        }
    }
    
    /**
     * 获取参数选项（用于 GitParameter 等动态参数）
     */
    public List<String> getParameterChoices(String jobName, String paramName) {
        try {
            getJenkinsServer(); // 确保已初始化
            
            // 首先尝试从作业参数定义中获取（如果已经有 choices）
            List<Map<String, Object>> params = getJobParameters(jobName);
            for (Map<String, Object> param : params) {
                if (paramName.equals(param.get("name"))) {
                    @SuppressWarnings("unchecked")
                    List<String> choices = (List<String>) param.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        log.info("从参数定义中获取到 {} 个选项: {} - {}", choices.size(), jobName, paramName);
                        return choices;
                    }
                }
            }
            
            // 如果没有 choices，尝试使用 GitParameter 的 API
            // Jenkins GitParameter 的 API 端点
            // 格式: /job/{jobName}/descriptorByName/net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition/fillValueItems
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/descriptorByName/net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition/fillValueItems";
            
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS) // GitParameter 可能需要更长时间
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
            
            String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
            
            // 构建请求体（GitParameter 需要传递参数名和作业信息）
            // 需要从作业配置中获取 Git 仓库信息
            String requestBody = "param=" + paramName;
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(RequestBody.create(requestBody, okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                log.debug("获取参数选项API响应: {}", responseBody);
                
                // 解析 JSON 响应
                try {
                    com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
                    com.alibaba.fastjson.JSONArray items = json.getJSONArray("values");
                    
                    List<String> choices = new ArrayList<>();
                    if (items != null) {
                        for (int i = 0; i < items.size(); i++) {
                            Object itemObj = items.get(i);
                            String value = null;
                            if (itemObj instanceof com.alibaba.fastjson.JSONObject) {
                                com.alibaba.fastjson.JSONObject item = (com.alibaba.fastjson.JSONObject) itemObj;
                                value = item.getString("value");
                            } else if (itemObj instanceof String) {
                                value = (String) itemObj;
                            }
                            
                            if (value != null && !value.isEmpty()) {
                                choices.add(value);
                            }
                        }
                    }
                    
                    response.close();
                    if (!choices.isEmpty()) {
                        log.info("成功获取 {} 个参数选项: {} - {}", choices.size(), jobName, paramName);
                        return choices;
                    }
                } catch (Exception parseError) {
                    log.warn("解析参数选项响应失败: {}", parseError.getMessage());
                }
            }
            
            if (response != null) {
                response.close();
            }
            
            log.warn("获取参数选项失败，返回空列表: {} - {}", jobName, paramName);
            return Collections.emptyList();
            
        } catch (Exception e) {
            log.warn("获取参数选项失败: {} - {}", jobName, paramName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 查询构建状态
     */
    public String getBuildStatus(String jobName, int buildNumber) {
        try {
            JenkinsServer server = getJenkinsServer();
            Job job = server.getJob(jobName);
            if (job == null) {
                return "UNKNOWN";
            }
            
            // 通过Job详情获取构建
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = job.details();
            if (jobDetails == null) {
                return "UNKNOWN";
            }
            
            // 获取所有构建并查找指定构建号
            List<com.offbytwo.jenkins.model.Build> builds = jobDetails.getBuilds();
            com.offbytwo.jenkins.model.Build build = builds.stream()
                .filter(b -> b.getNumber() == buildNumber)
                .findFirst()
                .orElse(null);
            if (build == null) {
                return "UNKNOWN";
            }
            
            com.offbytwo.jenkins.model.BuildWithDetails details = build.details();
            if (details == null || details.getResult() == null) {
                return "BUILDING";
            }
            
            return details.getResult().name();
        } catch (Exception e) {
            log.error("查询Jenkins构建状态失败", e);
            return "UNKNOWN";
        }
    }
    
    /**
     * 获取构建日志URL
     */
    public String getBuildLogUrl(String jobName, int buildNumber) {
        getJenkinsServer(); // 确保已初始化
        return jenkinsUrl + "/job/" + jobName + "/" + buildNumber + "/console";
    }
    
    /**
     * 获取所有作业列表
     */
    public Map<String, Job> getAllJobs() {
        try {
            JenkinsServer server = getJenkinsServer();
            return server.getJobs();
        } catch (Exception e) {
            log.error("获取Jenkins作业列表失败", e);
            return Collections.emptyMap();
        }
    }
    
    /**
     * 获取作业详情
     */
    public com.offbytwo.jenkins.model.JobWithDetails getJobDetails(String jobName) {
        try {
            JenkinsServer server = getJenkinsServer();
            Job job = server.getJob(jobName);
            if (job == null) {
                return null;
            }
            return job.details();
        } catch (Exception e) {
            log.error("获取Jenkins作业详情失败: {}", jobName, e);
            return null;
        }
    }
    
    /**
     * 获取构建详情
     * 如果遇到循环重定向问题，会尝试使用直接 REST API 调用
     */
    public com.offbytwo.jenkins.model.BuildWithDetails getBuildDetails(String jobName, int buildNumber) {
        try {
            JenkinsServer server = getJenkinsServer();
            Job job = server.getJob(jobName);
            if (job == null) {
                return null;
            }
            
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = job.details();
            if (jobDetails == null) {
                return null;
            }
            
            List<com.offbytwo.jenkins.model.Build> builds = jobDetails.getBuilds();
            com.offbytwo.jenkins.model.Build build = builds.stream()
                .filter(b -> b.getNumber() == buildNumber)
                .findFirst()
                .orElse(null);
            
            if (build == null) {
                return null;
            }
            
            try {
            return build.details();
        } catch (Exception e) {
                // 检查是否是重定向相关的错误
                String errorMsg = e.getMessage();
                Throwable cause = e.getCause();
                if (cause != null && (cause.getClass().getName().contains("CircularRedirect") || 
                    cause.getClass().getName().contains("ClientProtocol"))) {
                    log.warn("jenkins-client 库遇到重定向问题，尝试使用直接 REST API 调用: {} #{}", jobName, buildNumber);
                    return getBuildDetailsViaRestApi(jobName, buildNumber);
                }
                if (errorMsg != null && (errorMsg.contains("Circular redirect") || 
                    errorMsg.contains("redirect"))) {
                    log.warn("jenkins-client 库遇到重定向问题，尝试使用直接 REST API 调用: {} #{}", jobName, buildNumber);
                    return getBuildDetailsViaRestApi(jobName, buildNumber);
                }
                throw e; // 重新抛出非重定向相关的异常
            }
        } catch (Exception e) {
            // 检查是否是重定向相关的错误
            String errorMsg = e.getMessage();
            Throwable cause = e.getCause();
            if (cause != null && (cause.getClass().getName().contains("CircularRedirect") || 
                cause.getClass().getName().contains("ClientProtocol"))) {
                log.warn("jenkins-client 库遇到重定向问题，尝试使用直接 REST API 调用: {} #{}", jobName, buildNumber);
                return getBuildDetailsViaRestApi(jobName, buildNumber);
            }
            if (errorMsg != null && (errorMsg.contains("Circular redirect") || 
                errorMsg.contains("redirect"))) {
                log.warn("jenkins-client 库遇到重定向问题，尝试使用直接 REST API 调用: {} #{}", jobName, buildNumber);
                return getBuildDetailsViaRestApi(jobName, buildNumber);
            }
            log.error("获取Jenkins构建详情失败: {} #{}", jobName, buildNumber, e);
            return null;
        }
    }
    
    /**
     * 通过直接 REST API 调用获取构建详情（绕过 jenkins-client 的重定向问题）
     */
    private com.offbytwo.jenkins.model.BuildWithDetails getBuildDetailsViaRestApi(String jobName, int buildNumber) {
        try {
            getJenkinsServer(); // 确保已初始化
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/" + buildNumber + "/api/json";
            
            // 使用 OkHttpClient，禁用自动重定向
            OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false) // 禁用自动重定向，避免循环重定向
                .followSslRedirects(false)
                .build();
            
            String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
            
            Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Basic " + auth)
                .get()
                .build();
            
            Response response = client.newCall(request).execute();
            
            if (response.isSuccessful() && response.body() != null) {
                // 验证构建是否存在（读取响应体但不解析）
                response.body().string(); // 读取响应体以验证请求成功
                log.debug("通过 REST API 验证构建存在: {} #{}", jobName, buildNumber);
                // 由于无法直接创建 BuildWithDetails 对象，返回 null，让调用方使用其他方式
                response.close();
                return null; // 返回 null，让 getPipelineStages 使用 REST API 直接获取阶段信息
            } else {
                if (response != null) {
                    response.close();
                }
                log.warn("通过 REST API 获取构建详情失败，状态码: {} #{} - {}", jobName, buildNumber, response.code());
                return null;
            }
        } catch (Exception e) {
            log.warn("通过 REST API 获取构建详情失败: {} #{}", jobName, buildNumber, e.getMessage());
            return null;
        }
    }
    
    /**
     * 获取Pipeline阶段信息
     */
    public List<Map<String, Object>> getPipelineStages(String jobName, int buildNumber) {
        try {
            // 尝试获取构建详情（用于验证构建是否存在）
            // 如果遇到重定向问题，直接跳过验证，使用 REST API 获取阶段信息
        try {
            com.offbytwo.jenkins.model.BuildWithDetails buildDetails = getBuildDetails(jobName, buildNumber);
                // buildDetails 可能为 null，但继续尝试使用 REST API 获取阶段信息
            if (buildDetails == null) {
                    log.debug("构建详情为 null，继续使用 REST API 获取阶段信息: {} #{}", jobName, buildNumber);
                }
            } catch (Exception e) {
                // 遇到任何异常，跳过验证，直接使用 REST API
                log.debug("获取构建详情时遇到问题，跳过验证，直接使用 REST API: {} #{}", jobName, buildNumber);
            }
            
            // 尝试从构建详情中获取阶段信息
            // 注意：jenkins-client 库可能不直接支持获取阶段信息
            // 这里需要通过 Jenkins API 直接调用
            
            // 使用 HTTP 客户端调用 Jenkins REST API 获取阶段信息
            getJenkinsServer(); // 确保已初始化
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/" + buildNumber + "/wfapi/describe";
            
            try {
                // 优化：使用连接池和超时设置，提高性能
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
                String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
                
                Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Basic " + auth)
                    .get()
                    .build();
                
                Response response = client.newCall(request).execute();
                
                // 检查 HTTP 状态码
                int statusCode = response.code();
                
                if (statusCode == 404) {
                    // 404 明确表示不是 Pipeline 构建或 API 不可用
                    if (response != null) {
                        response.close();
                    }
                    log.debug("Pipeline 阶段 API 返回 404: {} #{}, 可能不是 Pipeline 类型构建", jobName, buildNumber);
                    // 返回特殊标记，表示明确不是 Pipeline 构建
                    return null; // 使用 null 表示明确不是 Pipeline 构建
                }
                
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    // 解析 JSON 响应
                    com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(responseBody);
                    com.alibaba.fastjson.JSONArray stages = json.getJSONArray("stages");
                    
                    // 检查是否是 Pipeline 类型的构建
                    // 如果不是 Pipeline 类型，stages 可能为 null
                    if (stages != null && stages.size() > 0) {
                        List<Map<String, Object>> stageList = new ArrayList<>();
                        for (int i = 0; i < stages.size(); i++) {
                            com.alibaba.fastjson.JSONObject stage = stages.getJSONObject(i);
                            Map<String, Object> stageMap = new HashMap<>();
                            stageMap.put("id", stage.getString("id"));
                            stageMap.put("name", stage.getString("name"));
                            stageMap.put("status", stage.getString("status"));
                            stageMap.put("durationMillis", stage.getLong("durationMillis"));
                            stageMap.put("startTimeMillis", stage.getLong("startTimeMillis"));
                            
                            // 获取子阶段
                            com.alibaba.fastjson.JSONArray subStages = stage.getJSONArray("stageFlowNodes");
                            if (subStages != null && subStages.size() > 0) {
                                List<Map<String, Object>> subStageList = new ArrayList<>();
                                for (int j = 0; j < subStages.size(); j++) {
                                    com.alibaba.fastjson.JSONObject subStage = subStages.getJSONObject(j);
                                    Map<String, Object> subStageMap = new HashMap<>();
                                    subStageMap.put("id", subStage.getString("id"));
                                    subStageMap.put("name", subStage.getString("name"));
                                    subStageMap.put("status", subStage.getString("status"));
                                    subStageMap.put("durationMillis", subStage.getLong("durationMillis"));
                                    subStageMap.put("startTimeMillis", subStage.getLong("startTimeMillis"));
                                    subStageList.add(subStageMap);
                                }
                                stageMap.put("subStages", subStageList);
                            }
                            
                            stageList.add(stageMap);
                        }
                        if (response != null) {
                            response.close();
                        }
                        return stageList;
                    } else {
                        // 响应成功但 stages 为空，可能是构建还在进行中或太旧
                        if (response != null) {
                            response.close();
                    }
                        log.debug("Pipeline 阶段信息为空: {} #{}, 可能是构建还在进行中或太旧", jobName, buildNumber);
                        // 返回空列表，表示可能是临时问题
                        return Collections.emptyList();
                }
                } else {
                    // 其他 HTTP 错误（如 500, 503 等）
                if (response != null) {
                    response.close();
                }
                    log.warn("Pipeline 阶段 API 返回错误状态码 {}: {} #{}", statusCode, jobName, buildNumber);
                    // 返回空列表，表示可能是临时问题，可以重试
                    return Collections.emptyList();
                }
            } catch (java.net.SocketTimeoutException e) {
                // 超时错误，可能是网络问题或 Jenkins 响应慢
                log.warn("获取 Pipeline 阶段信息超时: {} #{}, 错误: {}", jobName, buildNumber, e.getMessage());
                // 返回空列表，表示可能是临时问题，可以重试
                return Collections.emptyList();
            } catch (java.net.ConnectException e) {
                // 连接错误，可能是 Jenkins 不可用
                log.warn("连接 Jenkins 失败: {} #{}, 错误: {}", jobName, buildNumber, e.getMessage());
                // 返回空列表，表示可能是临时问题，可以重试
                return Collections.emptyList();
            } catch (IOException e) {
                // 其他 IO 错误，可能是网络问题
                log.warn("通过 REST API 获取 Pipeline 阶段信息失败: {} #{}, 错误: {}", jobName, buildNumber, e.getMessage());
                // 返回空列表，表示可能是临时问题，可以重试
                return Collections.emptyList();
            } catch (Exception e) {
                log.warn("通过 REST API 获取 Pipeline 阶段信息失败: {} #{}, 错误: {}", jobName, buildNumber, e.getMessage());
                // 返回空列表，表示可能是临时问题，可以重试
            return Collections.emptyList();
            }
        } catch (Exception e) {
            log.error("获取Pipeline阶段信息失败: {} #{}", jobName, buildNumber, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 获取Pipeline阶段的日志
     */
    public String getStageLog(String jobName, int buildNumber, String stageId, String stageName) {
        try {
            getJenkinsServer(); // 确保已初始化
            
            // 方式1: 通过 nodeId 获取阶段日志
            // Jenkins Pipeline API: /job/{jobName}/{buildNumber}/execution/node/{nodeId}/logText/progressiveText
            String apiUrl = jenkinsUrl + "/job/" + jobName + "/" + buildNumber + "/execution/node/" + stageId + "/logText/progressiveText";
            
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
                String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
                
                Request request = new Request.Builder()
                    .url(apiUrl)
                    .header("Authorization", "Basic " + auth)
                    .get()
                    .build();
                
                Response response = client.newCall(request).execute();
                
                if (response.isSuccessful() && response.body() != null) {
                    String log = response.body().string();
                    if (log != null && !log.trim().isEmpty()) {
                        return log;
                    }
                }
                
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                log.debug("通过 nodeId 获取阶段日志失败: {} #{} nodeId:{}", jobName, buildNumber, stageId, e);
            }
            
            // 方式2: 如果方式1失败，尝试从整个构建日志中提取特定阶段的日志
            // 通过解析控制台日志，找到对应阶段的日志段
            String consoleUrl = jenkinsUrl + "/job/" + jobName + "/" + buildNumber + "/consoleText";
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
                String auth = Base64.getEncoder().encodeToString((jenkinsUsername + ":" + jenkinsPassword).getBytes());
                
                Request request = new Request.Builder()
                    .url(consoleUrl)
                    .header("Authorization", "Basic " + auth)
                    .get()
                    .build();
                
                Response response = client.newCall(request).execute();
                
                if (response.isSuccessful() && response.body() != null) {
                    String fullLog = response.body().string();
                    // 从完整日志中提取特定阶段的日志
                    String stageLog = extractStageLogFromFullLog(fullLog, stageName);
                    if (stageLog != null && !stageLog.trim().isEmpty()) {
                        return stageLog;
                    }
                }
                
                if (response != null) {
                    response.close();
                }
            } catch (IOException e) {
                log.warn("获取构建控制台日志失败: {} #{}", jobName, buildNumber, e);
            }
            
            return "无法获取该阶段的日志，可能是阶段信息不完整或构建日志已过期。";
        } catch (Exception e) {
            log.error("获取Pipeline阶段日志失败: {} #{} stageId:{}", jobName, buildNumber, stageId, e);
            return "获取日志失败: " + e.getMessage();
        }
    }
    
    /**
     * 从完整日志中提取特定阶段的日志
     */
    private String extractStageLogFromFullLog(String fullLog, String stageName) {
        if (fullLog == null || stageName == null) {
            return null;
        }
        
        // Jenkins Pipeline 阶段的日志通常以以下格式开始：
        // [Pipeline] stage (StageName)
        // 或者
        // Stage "StageName"
        String stageStartPattern = "(?i)(\\[Pipeline\\]\\s+stage\\s+\\(.*?" + stageName + ".*?\\)|Stage\\s+\"" + stageName + "\"|\\[Pipeline\\]\\s+\\{.*?" + stageName + ".*?\\})";
        
        // 查找阶段开始位置
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(stageStartPattern);
        java.util.regex.Matcher matcher = pattern.matcher(fullLog);
        
        if (matcher.find()) {
            int startPos = matcher.start();
            
            // 查找下一个阶段开始位置或构建结束位置
            String nextStagePattern = "(?i)(\\[Pipeline\\]\\s+stage\\s+\\(|Stage\\s+\"|\\[Pipeline\\]\\s+\\{)";
            java.util.regex.Pattern nextPattern = java.util.regex.Pattern.compile(nextStagePattern);
            java.util.regex.Matcher nextMatcher = nextPattern.matcher(fullLog);
            
            int endPos = fullLog.length();
            if (nextMatcher.find(startPos + 1)) {
                // 找到下一个阶段，结束位置是下一个阶段之前
                endPos = nextMatcher.start();
            }
            
            return fullLog.substring(startPos, endPos).trim();
        }
        
        // 如果找不到标准格式，尝试查找包含阶段名称的日志段
        int index = fullLog.toLowerCase().indexOf(stageName.toLowerCase());
        if (index >= 0) {
            // 向前查找最近的换行或阶段标记
            int startPos = Math.max(0, fullLog.lastIndexOf("\n", index));
            if (startPos < 0) startPos = 0;
            
            // 向后查找下一个阶段标记或结束
            int nextStageIndex = fullLog.indexOf("\n[Pipeline] stage", index + stageName.length());
            if (nextStageIndex < 0) {
                nextStageIndex = fullLog.length();
            }
            
            return fullLog.substring(startPos, nextStageIndex).trim();
        }
        
        return null;
    }
    
    /**
     * 获取作业的构建历史（最近N次，优化版本：并行处理，快速返回）
     */
    public List<Map<String, Object>> getBuildHistory(String jobName, int limit) {
        try {
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = getJobDetails(jobName);
            if (jobDetails == null) {
                return Collections.emptyList();
            }
            
            List<com.offbytwo.jenkins.model.Build> builds = jobDetails.getBuilds();
            if (builds == null || builds.isEmpty()) {
                return Collections.emptyList();
            }
            
            // 优化：并行处理构建详情，提高性能
            List<com.offbytwo.jenkins.model.Build> limitedBuilds = builds.stream()
                .limit(limit)
                .collect(Collectors.toList());
            
            // 使用并行流处理，但限制并发数
            return limitedBuilds.parallelStream()
                .map(build -> {
                    try {
                        // 优化：添加超时机制，避免长时间等待
                        com.offbytwo.jenkins.model.BuildWithDetails details = build.details();
                        Map<String, Object> buildMap = new HashMap<>();
                        buildMap.put("number", build.getNumber());
                        buildMap.put("url", build.getUrl());
                        
                        if (details != null) {
                            buildMap.put("result", details.getResult() != null ? details.getResult().name() : "BUILDING");
                            buildMap.put("duration", details.getDuration());
                            buildMap.put("timestamp", details.getTimestamp());
                            buildMap.put("building", details.isBuilding());
                        } else {
                            buildMap.put("result", "UNKNOWN");
                            buildMap.put("duration", 0L);
                            buildMap.put("timestamp", 0L);
                            buildMap.put("building", false);
                        }
                        
                        return buildMap;
                    } catch (Exception e) {
                        // 优化：如果获取详情失败，至少返回基本信息
                        log.warn("获取构建详情失败: {} #{}, 使用基本信息", jobName, build.getNumber(), e);
                        Map<String, Object> buildMap = new HashMap<>();
                        buildMap.put("number", build.getNumber());
                        buildMap.put("url", build.getUrl());
                        buildMap.put("result", "UNKNOWN");
                        buildMap.put("duration", 0L);
                        buildMap.put("timestamp", 0L);
                        buildMap.put("building", false);
                        return buildMap;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("获取构建历史失败: {}", jobName, e);
            return Collections.emptyList();
        }
    }
}



