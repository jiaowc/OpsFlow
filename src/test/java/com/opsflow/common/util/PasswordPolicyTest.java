package com.opsflow.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordPolicyTest {

    @Test
    void commonWeakPasswords() {
        assertTrue(PasswordPolicy.isWeak("admin", "123456"));
        assertTrue(PasswordPolicy.isWeak("alice", "admin"));
        assertTrue(PasswordPolicy.isWeak("alice", "password"));
        assertTrue(PasswordPolicy.isWeak("alice", "PASSWORD"));
    }

    @Test
    void sameAsUsername() {
        assertTrue(PasswordPolicy.isWeak("operator01", "operator01"));
        assertTrue(PasswordPolicy.isWeak("Operator01", "operator01"));
    }

    @Test
    void tooShortOrNull() {
        assertTrue(PasswordPolicy.isWeak("alice", null));
        assertTrue(PasswordPolicy.isWeak("alice", ""));
        assertTrue(PasswordPolicy.isWeak("alice", "Ab3$xyz"));
    }

    @Test
    void strongEnough() {
        assertFalse(PasswordPolicy.isWeak("admin", "Ops#2026flow"));
        assertFalse(PasswordPolicy.isWeak(null, "abcdefgh"));
        assertFalse(PasswordPolicy.isWeak("alice", "alice-2026"));
    }
}
