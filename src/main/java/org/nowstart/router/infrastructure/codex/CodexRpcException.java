package org.nowstart.router.infrastructure.codex;

public class CodexRpcException extends RuntimeException {

    private final Integer rpcCode;

    public CodexRpcException(Integer rpcCode, String message) {
        super(message);
        this.rpcCode = rpcCode;
    }

    public Integer getRpcCode() {
        return rpcCode;
    }
}
