package com.zkteco.commands;

import java.io.IOException;

/** Keeps the device response code accessible without parsing an error message. */
public final class FingerprintReadException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int replyCode;

    public FingerprintReadException(int replyCode, String message) {
        super(message);
        this.replyCode = replyCode;
    }

    public int getReplyCode() { return replyCode; }
}
