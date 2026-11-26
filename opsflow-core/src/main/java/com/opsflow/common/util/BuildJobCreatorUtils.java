package com.opsflow.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.dao.mapper.UserMapper;
import com.opsflow.dao.model.BuildJob;
import com.opsflow.dao.model.User;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 构建任务创建人写入与展示解析。
 * <p>
 * admin 等未入库账号只有 session 用户名、没有 user.id 时，将 creatorUsername 写入 buildParameters，
 * 列表展示时优先读取，避免回退到操作系统用户名。
 * </p>
 */
public final class BuildJobCreatorUtils {

    public static final String CREATOR_USERNAME_KEY = "creatorUsername";

    private BuildJobCreatorUtils() {
    }

    public static void stampCreator(ObjectMapper mapper, BuildJob job, Long creatorId, String creatorUsername) {
        if (job == null) {
            return;
        }
        if (creatorId != null) {
            job.setCreatorId(creatorId);
        }
        if (!StringUtils.hasText(creatorUsername)) {
            return;
        }
        Map<String, Object> params = parseParams(mapper, job.getBuildParameters());
        params.put(CREATOR_USERNAME_KEY, creatorUsername.trim());
        job.setBuildParameters(writeParams(mapper, params));
    }

    public static String resolveCreatorName(ObjectMapper mapper, UserMapper userMapper, BuildJob job) {
        if (job == null) {
            return "-";
        }
        String fromParams = readCreatorUsername(mapper, job.getBuildParameters());
        if (StringUtils.hasText(fromParams)) {
            return fromParams.trim();
        }
        if (job.getCreatorId() != null) {
            User user = userMapper.selectById(job.getCreatorId());
            if (user != null) {
                if (StringUtils.hasText(user.getUsername())) {
                    return user.getUsername().trim();
                }
                if (StringUtils.hasText(user.getRealName())) {
                    return user.getRealName().trim();
                }
            }
            return "用户#" + job.getCreatorId();
        }
        return "-";
    }

    private static String readCreatorUsername(ObjectMapper mapper, String json) {
        Object value = parseParams(mapper, json).get(CREATOR_USERNAME_KEY);
        return value != null ? String.valueOf(value).trim() : null;
    }

    private static Map<String, Object> parseParams(ObjectMapper mapper, String json) {
        if (!StringUtils.hasText(json)) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            return map != null ? new HashMap<>(map) : new HashMap<>();
        } catch (Exception ignored) {
            return new HashMap<>();
        }
    }

    private static String writeParams(ObjectMapper mapper, Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException("序列化构建参数失败", e);
        }
    }
}
