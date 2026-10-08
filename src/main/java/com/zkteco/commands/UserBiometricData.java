package com.zkteco.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot d'un profil et de ses templates pour stockage JSON/BLOB ou réimport.
 * Le profil est copié ; les listes et erreurs exposées ne sont pas modifiables.
 * <pre>{@code
 * UserBiometricData data = terminal.getUserWithFingerprints("EMP0001");
 * String json = new ObjectMapper().writeValueAsString(data);
 * }</pre>
 * @see com.zkteco.terminal.ZKTerminal#getUserWithFingerprints(String)
 */
public final class UserBiometricData {
    private final UserInfo user;
    private final List<FingerprintTemplate> fingerprints;
    private final Map<Integer, String> fingerprintReadErrors;

    /**
     * Construit un snapshot sans erreur de lecture.
     * @param user profil non null, copié
     * @param fingerprints templates non null, avec des indices distincts ; liste vide autorisée
     * @throws IllegalArgumentException si le profil, la liste ou les templates sont invalides
     */
    public UserBiometricData(UserInfo user, List<FingerprintTemplate> fingerprints) {
        this(user, fingerprints, Collections.<Integer, String>emptyMap());
    }

    /**
     * Construit ou désérialise un snapshot avec les erreurs des doigts non lus.
     * @param user profil non null, copié
     * @param fingerprints templates récupérés, avec des indices distincts
     * @param fingerprintReadErrors messages par indice de 0 à 9 ; null signifie aucune erreur
     * @throws IllegalArgumentException si une entrée est invalide ou si un indice a un template et une erreur
     */
    @JsonCreator
    public UserBiometricData(@JsonProperty("user") UserInfo user,
            @JsonProperty("fingerprints") List<FingerprintTemplate> fingerprints,
            @JsonProperty("fingerprintReadErrors") Map<Integer, String> fingerprintReadErrors) {
        if (user == null || fingerprints == null) {
            throw new IllegalArgumentException("user and fingerprints must not be null");
        }
        Set<Integer> indices = new HashSet<Integer>();
        for (FingerprintTemplate fingerprint : fingerprints) {
            if (fingerprint == null || !indices.add(fingerprint.getFingerIndex())) {
                throw new IllegalArgumentException("Fingerprint indices must be unique and entries non-null");
            }
        }
        Map<Integer, String> errors = new LinkedHashMap<Integer, String>();
        if (fingerprintReadErrors != null) {
            for (Map.Entry<Integer, String> error : fingerprintReadErrors.entrySet()) {
                if (error.getKey() == null || error.getKey() < 0 || error.getKey() > 9
                        || error.getValue() == null || indices.contains(error.getKey())) {
                    throw new IllegalArgumentException("Invalid fingerprint read error");
                }
                errors.put(error.getKey(), error.getValue());
            }
        }
        this.user = new UserInfo(user);
        this.fingerprints = Collections.unmodifiableList(new ArrayList<FingerprintTemplate>(fingerprints));
        this.fingerprintReadErrors = Collections.unmodifiableMap(errors);
    }

    /** @return copie du profil ; ses modifications n'altèrent pas ce snapshot */
    public UserInfo getUser() {
        return new UserInfo(user);
    }

    /** @return liste non modifiable des templates effectivement récupérés */
    public List<FingerprintTemplate> getFingerprints() {
        return fingerprints;
    }

    /**
     * @return erreurs non modifiables par indice ; le code 4993 ne prouve pas l'absence du doigt
     */
    public Map<Integer, String> getFingerprintReadErrors() {
        return fingerprintReadErrors;
    }

    /**
     * Indique si toutes les lectures demandées ont réussi.
     * Ne signifie ni que dix doigts ont été demandés ni que la liste contient un template.
     * @return true si aucune erreur de lecture n'est stockée
     */
    @JsonIgnore
    public boolean isComplete() {
        return fingerprintReadErrors.isEmpty();
    }
}
