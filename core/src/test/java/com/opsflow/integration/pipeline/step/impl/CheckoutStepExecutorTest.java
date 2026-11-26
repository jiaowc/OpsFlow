package com.opsflow.integration.pipeline.step.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutStepExecutorTest {

    @Test
    void noTempFileWithoutCredential() {
        assertEquals("", CheckoutStepExecutor.credentialSetup(null));
    }

    @Test
    void writesCredentialViaQuotedHeredoc() {
        String setup = CheckoutStepExecutor.credentialSetup("https://oauth2:a%40b@git.example.com");
        assertTrue(setup.startsWith("CRED=$(umask 077; mktemp); trap 'rm -f \"$CRED\"' EXIT; "));
        assertTrue(setup.contains("cat > \"$CRED\" <<'__OPSFLOW_GIT_CRED_EOF__'\nhttps://oauth2:a%40b@git.example.com\n__OPSFLOW_GIT_CRED_EOF__\n"));
    }
}
