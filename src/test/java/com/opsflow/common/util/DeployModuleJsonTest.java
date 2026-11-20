package com.opsflow.common.util;

import com.opsflow.api.dto.DeployModuleItemDTO;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上线模块 JSON：既要读新格式（带归属人的对象数组），也要读历史数据（纯镜像字符串数组）。
 */
class DeployModuleJsonTest {

    @Test
    void parsesLegacyStringArray() {
        List<DeployModuleItemDTO> items = DeployModuleJson.parseItems("[\"prod/order:1.0\", \"  prod/pay:2.0  \"]");
        assertEquals(2, items.size());
        assertEquals("prod/order:1.0", items.get(0).getImage());
        assertEquals("prod/pay:2.0", items.get(1).getImage(), "镜像地址去掉首尾空格");
        assertNull(items.get(0).getOwnerName());
        assertNull(items.get(0).getOwnerId());
    }

    @Test
    void parsesObjectArrayWithOwner() {
        List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(
                "[{\"image\":\"prod/order:1.0\",\"ownerName\":\" alice \",\"ownerId\":7}]");
        assertEquals(1, items.size());
        assertEquals("prod/order:1.0", items.get(0).getImage());
        assertEquals("alice", items.get(0).getOwnerName());
        assertEquals(7L, items.get(0).getOwnerId());
    }

    @Test
    void fallsBackToImageFullNameField() {
        List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(
                "[{\"imageFullName\":\"harbor/prod/order:1.0\"},{\"image\":\"\",\"imageFullName\":\"harbor/prod/pay:1.0\"}]");
        assertEquals(Arrays.asList("harbor/prod/order:1.0", "harbor/prod/pay:1.0"), DeployModuleJson.toImageList(items));
    }

    @Test
    void skipsEntriesWithoutImageAndUnsupportedNodes() {
        List<DeployModuleItemDTO> items = DeployModuleJson.parseItems(
                "[{\"ownerName\":\"alice\"}, \"\", 123, null, [\"x\"], \"prod/order:1.0\"]");
        assertEquals(Collections.singletonList("prod/order:1.0"), DeployModuleJson.toImageList(items));
    }

    @Test
    void brokenOrNonArrayJsonYieldsEmptyListInsteadOfThrowing() {
        assertTrue(DeployModuleJson.parseItems(null).isEmpty());
        assertTrue(DeployModuleJson.parseItems("   ").isEmpty());
        assertTrue(DeployModuleJson.parseItems("{broken").isEmpty());
        assertTrue(DeployModuleJson.parseItems("{\"image\":\"prod/order:1.0\"}").isEmpty(), "顶层不是数组");
        assertTrue(DeployModuleJson.parseImages("not json").isEmpty());
    }

    @Test
    void writeThenParseRoundTrips() {
        List<DeployModuleItemDTO> original = DeployModuleJson.fromImages(
                Arrays.asList("prod/order:1.0", " ", null, " prod/pay:2.0 "), "alice", 7L);
        assertEquals(2, original.size(), "空白和 null 的镜像被丢弃");

        List<DeployModuleItemDTO> parsed = DeployModuleJson.parseItems(DeployModuleJson.writeItems(original));

        assertEquals(original, parsed);
        assertEquals(Arrays.asList("prod/order:1.0", "prod/pay:2.0"), DeployModuleJson.parseImages(DeployModuleJson.writeItems(original)));
    }

    @Test
    void nullInputsAreTreatedAsEmpty() {
        assertEquals("[]", DeployModuleJson.writeItems(null));
        assertTrue(DeployModuleJson.fromImages(null, "alice", 7L).isEmpty());
        assertTrue(DeployModuleJson.toImageList(null).isEmpty());
        assertTrue(DeployModuleJson.toImageList(Arrays.asList(null, new DeployModuleItemDTO())).isEmpty());
    }
}
