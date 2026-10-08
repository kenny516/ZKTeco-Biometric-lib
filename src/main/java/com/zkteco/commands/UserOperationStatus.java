package com.zkteco.commands;

/** Statut d'une écriture de profil ou d'une capture physique d'empreinte. */
public enum UserOperationStatus {
    /** Opération reconnue comme réussie. */
    SUCCESS,
    /** Écriture du profil refusée ou actualisation non confirmée. */
    PROFILE_FAILED,
    /** Capture refusée, résultat malformé ou empreinte non validée. */
    ENROLLMENT_FAILED,
    /** Délai d'attente de la capture écoulé. */
    TIMEOUT,
    /** Capture interrompue par l'appelant. */
    CANCELLED
}
