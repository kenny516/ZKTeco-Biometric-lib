package com.zkteco.commands;

/** Résultat d'écriture d'un profil seul ; aucun template n'est capturé ou importé. */
public final class UserWriteResult {
    private final int assignedUid;
    private final boolean created;
    private final UserOperationStatus status;
    private final ZKCommandReply reply;
    private final String message;

    /**
     * @param assignedUid UID destination choisi, ou 0 si aucun UID n'a été attribué
     * @param created true si l'opération ciblait un nouvel utilisateur, même en cas d'échec
     * @param status statut d'écriture
     * @param reply réponse du lecteur, éventuellement null
     * @param message description de l'opération
     */
    public UserWriteResult(int assignedUid, boolean created, UserOperationStatus status,
            ZKCommandReply reply, String message) {
        this.assignedUid = assignedUid;
        this.created = created;
        this.status = status;
        this.reply = reply;
        this.message = message;
    }

    /** @return UID destination choisi ; vérifier aussi {@link #isSuccess()} */
    public int getAssignedUid() { return assignedUid; }
    /** @return true si le profil était nouveau, sans garantie que l'écriture a réussi */
    public boolean isCreated() { return created; }
    /** @return statut du profil */
    public UserOperationStatus getStatus() { return status; }
    /** @return réponse du lecteur, ou null si aucune réponse n'est attachée */
    public ZKCommandReply getReply() { return reply; }
    /** @return description du résultat */
    public String getMessage() { return message; }
    /** @return true lorsque le statut est SUCCESS */
    public boolean isSuccess() { return status == UserOperationStatus.SUCCESS; }
}
