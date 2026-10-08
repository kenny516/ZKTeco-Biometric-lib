package com.zkteco.commands;

public final class UserEnrollmentResult {
    private final UserWriteResult profile;
    private final FingerprintEnrollmentResult fingerprint;

    public UserEnrollmentResult(UserWriteResult profile, FingerprintEnrollmentResult fingerprint) {
        this.profile = profile;
        this.fingerprint = fingerprint;
    }

    public UserWriteResult getProfile() { return profile; }
    public FingerprintEnrollmentResult getFingerprint() { return fingerprint; }
    public int getAssignedUid() { return profile.getAssignedUid(); }
    public UserOperationStatus getStatus() {
        return profile.isSuccess() && fingerprint != null ? fingerprint.getStatus() : profile.getStatus();
    }
    public boolean isSuccess() { return getStatus() == UserOperationStatus.SUCCESS; }
}
