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

/** Snapshot suitable for JSON storage or reimport on a compatible reader. */
public final class UserBiometricData {
    private final UserInfo user;
    private final List<FingerprintTemplate> fingerprints;
    private final Map<Integer, String> fingerprintReadErrors;

    public UserBiometricData(UserInfo user, List<FingerprintTemplate> fingerprints) {
        this(user, fingerprints, Collections.<Integer, String>emptyMap());
    }

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

    public UserInfo getUser() { return new UserInfo(user); }
    public List<FingerprintTemplate> getFingerprints() { return fingerprints; }
    public Map<Integer, String> getFingerprintReadErrors() { return fingerprintReadErrors; }
    /** True only if all requested reads succeeded; does not imply all ten fingers were requested. */
    @JsonIgnore
    public boolean isComplete() { return fingerprintReadErrors.isEmpty(); }
}
