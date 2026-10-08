package com.zkteco.commands;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Portable template: device UID belongs to the associated user, not these bytes. */
public final class FingerprintTemplate {
    private final int fingerIndex;
    private final byte[] template;

    @JsonCreator
    public FingerprintTemplate(@JsonProperty("fingerIndex") int fingerIndex,
            @JsonProperty("template") byte[] template) {
        if (fingerIndex < 0 || fingerIndex > 9) {
            throw new IllegalArgumentException("fingerIndex must be between 0 and 9");
        }
        if (template == null || template.length == 0 || template.length > 65535) {
            throw new IllegalArgumentException("template must contain 1..65535 bytes");
        }
        this.fingerIndex = fingerIndex;
        this.template = template.clone();
    }

    public int getFingerIndex() { return fingerIndex; }
    public byte[] getTemplate() { return template.clone(); }
    @JsonIgnore
    public int getSize() { return template.length; }
}
