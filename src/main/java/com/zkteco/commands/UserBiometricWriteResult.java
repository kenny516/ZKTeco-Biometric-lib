package com.zkteco.commands;

/** Profile can remain saved when the template upload fails; there is no rollback. */
public final class UserBiometricWriteResult {
    private final UserWriteResult profile;
    private final boolean fingerprintsWritten;
    private final String message;
    private final boolean deviceRestored;

    public UserBiometricWriteResult(UserWriteResult profile, boolean fingerprintsWritten, String message) {
        this(profile, fingerprintsWritten, message, true);
    }

    public UserBiometricWriteResult(UserWriteResult profile, boolean fingerprintsWritten, String message,
            boolean deviceRestored) {
        this.profile = profile;
        this.fingerprintsWritten = fingerprintsWritten;
        this.message = message;
        this.deviceRestored = deviceRestored;
    }

    public UserWriteResult getProfile() { return profile; }
    public int getAssignedUid() { return profile.getAssignedUid(); }
    public boolean isFingerprintsWritten() { return fingerprintsWritten; }
    public String getMessage() { return message; }
    public boolean isDeviceRestored() { return deviceRestored; }
    public boolean isSuccess() { return profile.isSuccess() && fingerprintsWritten && deviceRestored; }
}
