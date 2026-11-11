package com.bidding.common;

import lombok.Getter;

/**
 * 业务异常。Service 层遇到业务规则不满足时直接抛它，交给全局异常处理器统一转成响应。
 * 相比每个方法都 try/catch 再手动拼响应，这样代码更干净，错误处理也统一。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(ResultCode resultCode) {
        this(resultCode.getCode(), resultCode.getMessage());
    }

    public BusinessException(ResultCode resultCode, String message) {
        this(resultCode.getCode(), message);
    }
}
