package com.zkteco.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Facial export only; restoration is not implemented. Source UID remains in each record. */
public final class UserFaceData {
    private final UserInfo user;
    private final List<FaceTemplate> faces;

    @JsonCreator
    public UserFaceData(@JsonProperty("user") UserInfo user,
            @JsonProperty("faces") List<FaceTemplate> faces) {
        if (user == null || faces == null) {
            throw new IllegalArgumentException("user and faces are required");
        }
        Set<Integer> indices = new HashSet<Integer>();
        for (FaceTemplate face : faces) {
            if (face == null || !indices.add(face.getFaceIndex())) {
                throw new IllegalArgumentException("Face indices must be unique and entries non-null");
            }
        }
        this.user = new UserInfo(user);
        this.faces = Collections.unmodifiableList(new ArrayList<FaceTemplate>(faces));
    }

    public UserInfo getUser() { return new UserInfo(user); }
    public List<FaceTemplate> getFaces() { return faces; }
}
