package com.zkteco.commands;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Template biométrique immuable associé à un index de doigt, sans UID de lecteur.
 * <p>Les octets représentent un template de reconnaissance, pas une image.
 * Jackson les encode en Base64 dans le JSON et les redécode en {@code byte[]}.
 * La compatibilité du format dépend du lecteur et de son algorithme biométrique.
 */
public final class FingerprintTemplate {
    private final int fingerIndex;
    private final byte[] template;

    /**
     * Copie les octets pour protéger le template des modifications de l'appelant.
     * @param fingerIndex index de doigt de 0 à 9
     * @param template template non vide, de 1 à 65535 octets
     * @throws IllegalArgumentException si l'indice ou les octets sont invalides
     */
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

    /** @return index du doigt, entre 0 et 9 */
    public int getFingerIndex() { return fingerIndex; }
    /** @return copie des octets, utilisable pour stockage BLOB ou import */
    public byte[] getTemplate() { return template.clone(); }
    /** @return nombre d'octets binaires, et non longueur du texte Base64 */
    @JsonIgnore
    public int getSize() { return template.length; }
}
