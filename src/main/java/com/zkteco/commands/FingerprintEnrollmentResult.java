package com.zkteco.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Réponse d'une capture physique, avec scores et métadonnées du template.
 * Pour obtenir les octets de l'empreinte, utiliser la lecture du terminal après succès.
 * @see com.zkteco.terminal.ZKTerminal#getUserWithFingerprints(String, int...)
 */
public final class FingerprintEnrollmentResult {
    private final UserOperationStatus status;
    private final Integer resultCode;
    private final int templateSize;
    private final String userId;
    private final int fingerIndex;
    private final List<Integer> sampleScores;
    private final int[] rawEvent;
    private final String message;

    /**
     * @param status succès, échec, timeout ou annulation de capture
     * @param resultCode code de l'événement final, null si aucun événement final reçu
     * @param templateSize taille annoncée en octets, 0 si inconnue
     * @param userId identifiant retourné par le lecteur, éventuellement null
     * @param fingerIndex index de doigt demandé ou retourné
     * @param sampleScores scores de capture reçus, non null, copiés
     * @param rawEvent événement brut, éventuellement null, copié
     * @param message description du résultat
     */
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

    /** @return statut de la capture */
    public UserOperationStatus getStatus() { return status; }
    /** @return code final du lecteur, ou null en l'absence d'événement final */
    public Integer getResultCode() { return resultCode; }
    /** @return taille annoncée en octets ; ne contient pas le template lui-même */
    public int getTemplateSize() { return templateSize; }
    /** @return userid retourné par le lecteur, éventuellement null */
    public String getUserId() { return userId; }
    /** @return index du doigt associé au résultat */
    public int getFingerIndex() { return fingerIndex; }
    /** @return liste non modifiable des scores reçus */
    public List<Integer> getSampleScores() { return sampleScores; }
    /** @return copie de l'événement brut, ou null si aucun événement n'est attaché */
    public int[] getRawEvent() { return rawEvent == null ? null : rawEvent.clone(); }
    /** @return description du résultat */
    public String getMessage() { return message; }
    /** @return true si le lecteur a signalé une capture réussie */
    public boolean isSuccess() { return status == UserOperationStatus.SUCCESS; }
}
