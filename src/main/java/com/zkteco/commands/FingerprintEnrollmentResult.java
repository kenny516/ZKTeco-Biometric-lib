package com.zkteco.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FingerprintEnrollmentResult {
    private final UserOperationStatus status;
    private final Integer resultCode;
    private final int templateSize;
    private final String userId;
    private final int fingerIndex;
    private final List<Integer> sampleScores;
    private final int[] rawEvent;
    private final String message;

    public FingerprintEnrollmentResult(UserOperationStatus status, Integer resultCode, int templateSize,
            String userId, int fingerIndex, List<Integer> sampleScores, int[] rawEvent, String message) {
        this.status = status;
        this.resultCode = resultCode;
        this.templateSize = templateSize;
        this.userId = userId;
        this.fingerIndex = fingerIndex;
        this.sampleScores = Collections.unmodifiableList(new ArrayList<Integer>(sampleScores));
        this.rawEvent = rawEvent == null ? null : rawEvent.clone();
        this.message = message;
    }

    public UserOperationStatus getStatus() { return status; }
    public Integer getResultCode() { return resultCode; }
    public int getTemplateSize() { return templateSize; }
    public String getUserId() { return userId; }
    public int getFingerIndex() { return fingerIndex; }
    public List<Integer> getSampleScores() { return sampleScores; }
    public int[] getRawEvent() { return rawEvent == null ? null : rawEvent.clone(); }
    public String getMessage() { return message; }
    public boolean isSuccess() { return status == UserOperationStatus.SUCCESS; }
}
