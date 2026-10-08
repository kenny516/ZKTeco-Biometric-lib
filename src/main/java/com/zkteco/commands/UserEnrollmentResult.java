package com.zkteco.commands;

/** Résultat d'un profil suivi d'une capture physique ; ne contient pas les octets du template. */
public final class UserEnrollmentResult {
    private final UserWriteResult profile;
    private final FingerprintEnrollmentResult fingerprint;

    /**
     * @param profile résultat d'écriture du profil
     * @param fingerprint résultat de capture, null si l'écriture du profil a échoué
     */
    public UserEnrollmentResult(UserWriteResult profile, FingerprintEnrollmentResult fingerprint) {
        this.profile = profile;
        this.fingerprint = fingerprint;
    }

    /** @return résultat d'écriture du profil */
    public UserWriteResult getProfile() { return profile; }
    /** @return résultat de capture, ou null si la capture n'a pas été lancée */
    public FingerprintEnrollmentResult getFingerprint() { return fingerprint; }
    /** @return UID destination attribué au profil */
    public int getAssignedUid() { return profile.getAssignedUid(); }
    /** @return statut de capture si présent et profil réussi, sinon statut du profil */
    public UserOperationStatus getStatus() {
        return profile.isSuccess() && fingerprint != null ? fingerprint.getStatus() : profile.getStatus();
    }
    /** @return true si le statut global est SUCCESS */
    public boolean isSuccess() { return getStatus() == UserOperationStatus.SUCCESS; }
}
