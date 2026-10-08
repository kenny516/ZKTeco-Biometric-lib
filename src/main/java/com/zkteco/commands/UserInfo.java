package com.zkteco.commands;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import com.zkteco.Enum.UserRoleEnum;

/**
 * Profil utilisateur modifiable, sans les octets d'empreinte.
 * <p>{@code userid} est l'identifiant métier externe. {@code uid} est l'identifiant
 * interne d'un lecteur ; il peut changer après suppression ou transfert.
 * Les champs sont validés par {@link UserRecordCodec} lors de l'écriture.
 * @see UserBiometricData
 */
public class UserInfo {

    private int uid;
    private UserRoleEnum role;
    private String password;
    private String name;
    private long cardno;
    private String userid;
    private String groupNumber;
    private int userTimeZoneFlag;
    private int timeZone1;
    private int timeZone2;
    private int timeZone3;
    private boolean enabled = true;

    /** @return true si le profil est activé pour la vérification sur le lecteur */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled état d'activation du profil */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return UID interne du lecteur ; 0 peut signifier qu'aucun UID n'est encore attribué */
    public int getUid() {
        return uid;
    }

    /** @param uid UID interne ; {@code addUser} détermine l'UID destination par userid */
    public void setUid(int uid) {
        this.uid = uid;
    }

    /** @return rôle/privilège du profil */
    public UserRoleEnum getRole() {
        return role;
    }

    public void setRole(UserRoleEnum userDefault) {
        this.role = userDefault;
    }

    /** @return mot de passe du profil, présent aussi lors d'une sérialisation JSON */
    public String getPassword() {
        return password;
    }

    /** @param password mot de passe, au maximum 8 octets ASCII à l'écriture */
    public void setPassword(String password) {
        this.password = password;
    }

    /** @return nom affiché de l'utilisateur */
    public String getName() {
        return name;
    }

    /** @param name nom, au maximum 23 octets dans l'encodage du lecteur à l'écriture */
    public void setName(String name) {
        this.name = name;
    }

    /** @return numéro de carte non signé sur 32 bits, porté par un long Java */
    public long getCardno() {
        return cardno;
    }

    /** @param cardno numéro entre 0 et 4294967295, contrôlé à l'écriture */
    public void setCardno(long cardno) {
        this.cardno = cardno;
    }

    /** @return identifiant métier externe servant à rechercher/créer/mettre à jour le profil */
    public String getUserid() {
        return userid;
    }

    /** @param userid identifiant métier ; le format 72 octets accepte 1 à 9 octets ASCII */
    public void setUserid(String userid) {
        this.userid = userid;
    }

    public String getGroupNumber() {
        return groupNumber;
    }

    public void setGroupNumber(String groupNumber) {
        this.groupNumber = groupNumber;
    }

    /**
     * @return numéro de groupe de 0 à 255, 0 si non défini
     * @throws IllegalStateException si la représentation textuelle du groupe est invalide
     */
    public int getGroupId() {
        if (groupNumber == null || groupNumber.trim().isEmpty()) {
            return 0;
        }
        try {
            int groupId = Integer.parseInt(groupNumber);
            if (groupId < 0 || groupId > 255) {
                throw new IllegalStateException("groupNumber must be between 0 and 255");
            }
            return groupId;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("groupNumber must be numeric", e);
        }
    }

    /**
     * @param groupId numéro de groupe de 0 à 255
     * @throws IllegalArgumentException si le groupe est hors plage
     */
    public void setGroupId(int groupId) {
        if (groupId < 0 || groupId > 255) {
            throw new IllegalArgumentException("groupId must be between 0 and 255");
        }
        this.groupNumber = String.valueOf(groupId);
    }

    public int getUserTimeZoneFlag() {
        return userTimeZoneFlag;
    }

    public void setUserTimeZoneFlag(int userTimeZoneFlag) {
        this.userTimeZoneFlag = userTimeZoneFlag;
    }

    public int getTimeZone1() {
        return timeZone1;
    }

    public void setTimeZone1(int timeZone1) {
        this.timeZone1 = timeZone1;
    }

    public int getTimeZone2() {
        return timeZone2;
    }

    public void setTimeZone2(int timeZone2) {
        this.timeZone2 = timeZone2;
    }

    public int getTimeZone3() {
        return timeZone3;
    }

    public void setTimeZone3(int timeZone3) {
        this.timeZone3 = timeZone3;
    }

    /**
     * Construit un profil avec un UID connu, activé et dans le groupe 0.
     * @param uid UID interne du lecteur
     * @param user_id identifiant métier externe
     * @param name nom affiché
     * @param password mot de passe
     * @param privilege rôle du profil
     * @param cardno numéro de carte non signé sur 32 bits
     */
    public UserInfo(int uid, String user_id, String name, String password, UserRoleEnum privilege, long cardno) {
        this.userid = user_id;
        this.name = name;
        this.role = privilege;
        this.cardno = cardno;
        this.password = password;
        this.groupNumber = "0";
        this.uid = uid;
    }

    /**
     * Construit un profil à créer, avec UID 0, activé et dans le groupe 0.
     * @param user_id identifiant métier ; l'UID sera déterminé par le terminal
     * @param name nom affiché
     * @param password mot de passe
     * @param privilege rôle du profil
     * @param cardno numéro de carte non signé sur 32 bits
     */
    public UserInfo(String user_id, String name, String password, UserRoleEnum privilege, long cardno) {
        this.userid = user_id;
        this.name = name;
        this.role = privilege;
        this.cardno = cardno;
        this.password = password;
        this.groupNumber = "0";
    }

    /**
     * Copie tous les champs d'un profil, sans partager un objet modifiable.
     * @param other profil non null
     * @throws IllegalArgumentException si le profil est null
     */
    public UserInfo(UserInfo other) {
        if (other == null) {
            throw new IllegalArgumentException("other must not be null");
        }
        this.uid = other.uid;
        this.role = other.role;
        this.password = other.password;
        this.name = other.name;
        this.cardno = other.cardno;
        this.userid = other.userid;
        this.groupNumber = other.groupNumber;
        this.userTimeZoneFlag = other.userTimeZoneFlag;
        this.timeZone1 = other.timeZone1;
        this.timeZone2 = other.timeZone2;
        this.timeZone3 = other.timeZone3;
        this.enabled = other.enabled;
    }

    /** Crée un profil vide pour construction progressive ou désérialisation Jackson. */
    public UserInfo() {

    }

    public static UserInfo encodeUser(ByteBuffer buffer, int userPacketSize) {
        if (userPacketSize != UserRecordCodec.RECORD_SIZE) {
            throw new IllegalArgumentException("Only 72-byte user records are supported");
        }
        return new UserRecordCodec(StandardCharsets.UTF_8).decode(buffer);
    }

    /**
     * @deprecated Retained for source compatibility; not needed by the user codec.
     */
    @Deprecated
    public static List<byte[]> split(byte[] pattern, byte[] input) {
        if (pattern == null || pattern.length == 0 || input == null) {
            throw new IllegalArgumentException("pattern and input must be non-null, and pattern must not be empty");
        }
        List<byte[]> result = new LinkedList<byte[]>();
        int blockStart = 0;
        for (int i = 0; i <= input.length - pattern.length; i++) {
            if (isMatch(pattern, input, i)) {
                result.add(Arrays.copyOfRange(input, blockStart, i));
                blockStart = i + pattern.length;
                i = blockStart - 1;
            }
        }
        result.add(Arrays.copyOfRange(input, blockStart, input.length));
        return result;
    }

    /**
     * @deprecated Retained for source compatibility; not needed by the user codec.
     */
    @Deprecated
    public static boolean isMatch(byte[] pattern, byte[] input, int pos) {
        if (pattern == null || input == null || pos < 0 || pos + pattern.length > input.length) {
            return false;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i] != input[pos + i]) {
                return false;
            }
        }
        return true;
    }
}
