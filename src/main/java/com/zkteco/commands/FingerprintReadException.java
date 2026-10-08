package com.zkteco.commands;

import java.io.IOException;

/** Keeps the device response code accessible without parsing an error message. */
public final class FingerprintReadException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int replyCode;

    /**
     * @param replyCode code brut retourné par le lecteur
     * @param message description de l'erreur et du doigt demandé
     */
    public FingerprintReadException(int replyCode, String message) {
        super(message);
        this.replyCode = replyCode;
    }

    /** @return code du lecteur, par exemple 4991 ou 4993 pour une erreur de lecture de base */
    public int getReplyCode() { return replyCode; }
}
