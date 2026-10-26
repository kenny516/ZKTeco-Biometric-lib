package com.zkteco.commands;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Complete legacy 2576-byte facial record, including its device header and padding.
 * Jackson stores the record in Base64. This is not a photograph.
 */
public final class FaceTemplate {
    private final int faceIndex;
    private final byte[] template;

    @JsonCreator
    public FaceTemplate(@JsonProperty("faceIndex") int faceIndex,
            @JsonProperty("template") byte[] template) {
        if (faceIndex < 0 || template == null || template.length != 2576) {
            throw new IllegalArgumentException("Expected a nonnegative faceIndex and a 2576-byte facial record");
        }
        this.faceIndex = faceIndex;
        this.template = template.clone();
    }

    public int getFaceIndex() { return faceIndex; }
    public byte[] getTemplate() { return template.clone(); }
    @JsonIgnore
    public int getSize() { return template.length; }
}
