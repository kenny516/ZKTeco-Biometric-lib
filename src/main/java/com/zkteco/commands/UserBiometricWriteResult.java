package com.zkteco.commands;

/**
 * Résultat du profil, de l'import des templates et du nettoyage du lecteur.
 * Le profil peut rester enregistré après un échec des templates : aucun rollback.
 * Le succès reflète les réponses du lecteur, pas une vérification physique du doigt.
 */
public final class UserBiometricWriteResult {
    private final UserWriteResult profile;
    private final boolean fingerprintsWritten;
    private final String message;
    private final boolean deviceRestored;

    /**
     * Construit un résultat sans erreur de nettoyage signalée.
     * @param profile résultat du profil
     * @param fingerprintsWritten succès de la phase templates
     * @param message détails de l'opération
     */
    public UserBiometricWriteResult(UserWriteResult profile, boolean fingerprintsWritten, String message) {
        this(profile, fingerprintsWritten, message, true);
    }

    /**
     * @param profile résultat du profil, non null
     * @param fingerprintsWritten succès de l'import/actualisation, ou true si aucun template n'était fourni
     * @param message détails des réponses et erreurs
     * @param deviceRestored false si le nettoyage ou la réactivation a signalé une erreur
     */
    public UserBiometricWriteResult(UserWriteResult profile, boolean fingerprintsWritten, String message,
            boolean deviceRestored) {
        this.profile = profile;
        this.fingerprintsWritten = fingerprintsWritten;
        this.message = message;
        this.deviceRestored = deviceRestored;
    }

    /** @return résultat du profil, à examiner même si les templates ont échoué */
    public UserWriteResult getProfile() { return profile; }
    /** @return UID destination choisi pour le profil */
    public int getAssignedUid() { return profile.getAssignedUid(); }
    /** @return succès de la phase templates ; true aussi pour un import sans templates */
    public boolean isFingerprintsWritten() { return fingerprintsWritten; }
    /** @return détails de l'opération et de son nettoyage */
    public String getMessage() { return message; }
    /** @return true si aucune erreur de nettoyage/réactivation n'a été signalée */
    public boolean isDeviceRestored() { return deviceRestored; }
    /** @return true si le profil, la phase templates et le nettoyage ont réussi */
    public boolean isSuccess() { return profile.isSuccess() && fingerprintsWritten && deviceRestored; }
}
