package com.opsflow.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.api.dto.DeployModuleItemDTO;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 上线模块 JSON 解析：兼容旧版纯字符串数组与带归属的对象数组。
 */
public final class DeployModuleJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeployModuleJson() {
    }

    public static List<DeployModuleItemDTO> parseItems(String json) {
        List<DeployModuleItemDTO> items = new ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return items;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) {
                return items;
            }
            for (JsonNode node : root) {
                DeployModuleItemDTO item = new DeployModuleItemDTO();
                if (node.isTextual()) {
                    item.setImage(node.asText());
                } else if (node.isObject()) {
                    String image = text(node, "image");
                    if (!StringUtils.hasText(image)) {
                        image = text(node, "imageFullName");
                    }
                    item.setImage(image);
                    item.setOwnerName(text(node, "ownerName"));
                    if (node.hasNonNull("ownerId")) {
                        item.setOwnerId(node.get("ownerId").asLong());
                    }
                } else {
                    continue;
                }
                if (StringUtils.hasText(item.getImage())) {
                    item.setImage(item.getImage().trim());
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    public static List<String> parseImages(String json) {
        List<String> images = new ArrayList<>();
        for (DeployModuleItemDTO item : parseItems(json)) {
            if (StringUtils.hasText(item.getImage())) {
                images.add(item.getImage().trim());
            }
        }
        return images;
    }

    public static String writeItems(List<DeployModuleItemDTO> items) {
        try {
            return MAPPER.writeValueAsString(items != null ? items : new ArrayList<>());
        } catch (Exception e) {
            throw new IllegalArgumentException("模块列表序列化失败", e);
        }
    }

    public static List<DeployModuleItemDTO> fromImages(List<String> images, String ownerName, Long ownerId) {
        List<DeployModuleItemDTO> items = new ArrayList<>();
        if (images == null) {
            return items;
        }
        for (String image : images) {
            if (!StringUtils.hasText(image)) {
                continue;
            }
            DeployModuleItemDTO item = new DeployModuleItemDTO();
            item.setImage(image.trim());
            item.setOwnerName(ownerName);
            item.setOwnerId(ownerId);
            items.add(item);
        }
        return items;
    }

    /** 兼容旧 DTO：仅传镜像字符串时写入 */
    public static List<String> toImageList(List<DeployModuleItemDTO> items) {
        List<String> images = new ArrayList<>();
        if (items == null) {
            return images;
        }
        for (DeployModuleItemDTO item : items) {
            if (item != null && StringUtils.hasText(item.getImage())) {
                images.add(item.getImage().trim());
            }
        }
        return images;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText();
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
