package com.opsflow.common.util;

import com.opsflow.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequiredFieldsTest {

    @Test
    void requireTextRejectsNullEmptyAndBlankAndTrims() {
        for (String empty : new String[]{null, "", "   ", "\t\n"}) {
            assertEquals("服务名称不能为空", assertThrows(BusinessException.class,
                    () -> RequiredFields.requireText(empty, "服务名称")).getMessage());
        }
        assertEquals("order", RequiredFields.requireText("  order ", "服务名称"));
    }

    @Test
    void optionalTextTreatsNullAsUnchangedButRejectsBlank() {
        assertNull(RequiredFields.optionalText(null, "名称"));
        assertThrows(BusinessException.class, () -> RequiredFields.optionalText(" ", "名称"));
        assertEquals("a", RequiredFields.optionalText(" a ", "名称"));
    }

    @Test
    void requireSecretRejectsBlankButNeverTrims() {
        assertThrows(BusinessException.class, () -> RequiredFields.requireSecret("  ", "密码"));
        assertThrows(BusinessException.class, () -> RequiredFields.requireSecret(null, "密码"));
        assertEquals(" p@ss ", RequiredFields.requireSecret(" p@ss ", "密码"));
    }

    @Test
    void requireValueRejectsNull() {
        assertEquals("并发数不能为空", assertThrows(BusinessException.class,
                () -> RequiredFields.requireValue(null, "并发数")).getMessage());
        assertEquals(3, RequiredFields.requireValue(3, "并发数"));
    }
}
