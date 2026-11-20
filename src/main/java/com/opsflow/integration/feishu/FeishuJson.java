package com.opsflow.integration.feishu;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

/**
 * 飞书接口 JSON 读写工具（Jackson）。
 * 取值方法在字段缺失或为 JSON null 时返回 null，与业务代码的判空约定一致。
 */
public final class FeishuJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeishuJson() {
    }

    public static ObjectNode newObject() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode newArray() {
        return MAPPER.createArrayNode();
    }

    /**
     * 解析 JSON 对象；空串返回 null，非对象（如数组）抛出 IOException
     */
    public static ObjectNode parseObject(String json) throws IOException {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        JsonNode node = MAPPER.readTree(json);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IOException("JSON 不是对象: " + node.getNodeType());
        }
        return (ObjectNode) node;
    }

    public static List<String> parseStringList(String json) throws IOException {
        return MAPPER.readValue(json, new TypeReference<List<String>>() { });
    }

    public static String toJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /**
     * 文本字段；数字等标量转为字符串，对象/数组返回其 JSON 文本
     */
    public static String getString(JsonNode node, String field) {
        JsonNode value = valueOf(node, field);
        if (value == null) {
            return null;
        }
        return value.isValueNode() ? value.asText() : toJson(value);
    }

    public static Integer getInteger(JsonNode node, String field) {
        JsonNode value = valueOf(node, field);
        if (value == null) {
            return null;
        }
        if (value.isNumber()) {
            return value.intValue();
        }
        if (value.isTextual()) {
            try {
                return Integer.valueOf(value.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static ObjectNode getObject(JsonNode node, String field) {
        JsonNode value = valueOf(node, field);
        return value != null && value.isObject() ? (ObjectNode) value : null;
    }

    public static ArrayNode getArray(JsonNode node, String field) {
        JsonNode value = valueOf(node, field);
        return value != null && value.isArray() ? (ArrayNode) value : null;
    }

    private static JsonNode valueOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value;
    }
}
