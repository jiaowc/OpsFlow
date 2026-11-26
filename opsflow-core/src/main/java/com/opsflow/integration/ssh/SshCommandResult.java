package com.opsflow.integration.ssh;

import lombok.Data;

/**
 * SSH 命令执行结果
 */
@Data
public class SshCommandResult {

    private boolean success;
    private int exitCode;
    private String output;
    private String errorMessage;

    public static SshCommandResult fail(String message) {
        SshCommandResult result = new SshCommandResult();
        result.setSuccess(false);
        result.setExitCode(-1);
        result.setErrorMessage(message);
        return result;
    }

    public static SshCommandResult ok(String output) {
        SshCommandResult result = new SshCommandResult();
        result.setSuccess(true);
        result.setExitCode(0);
        result.setOutput(output);
        return result;
    }

    public static SshCommandResult of(int exitCode, String output) {
        SshCommandResult result = new SshCommandResult();
        result.setExitCode(exitCode);
        result.setSuccess(exitCode == 0);
        result.setOutput(output);
        if (exitCode != 0) {
            result.setErrorMessage(output);
        }
        return result;
    }
}
