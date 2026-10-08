package com.zkteco.commands;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.zkteco.Enum.UserRoleEnum;

/** Encodes and decodes the modern ZKTeco 72-byte user record. */
public final class UserRecordCodec {
    public static final int RECORD_SIZE = 72;

    private final Charset nameCharset;

    public UserRecordCodec() {
        this(StandardCharsets.UTF_8);
    }

    public UserRecordCodec(Charset nameCharset) {
        if (nameCharset == null) {
            throw new IllegalArgumentException("nameCharset must not be null");
        }
        this.nameCharset = nameCharset;
    }

    /**
     * Encode un profil moderne après validation de ses champs.
     * @param user profil avec un UID de 1 à 65535
     * @return enregistrement de 72 octets en little-endian
     * @throws IllegalArgumentException si les champs dépassent les limites du format
     */
    public byte[] encode(UserInfo user) {
        validate(user, true);
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(0, (short) user.getUid());
        buffer.put(2, (byte) (user.getRole().getRole() | (user.isEnabled() ? 0 : 1)));
        putFixed(buffer, 3, 8, ascii(user.getPassword(), "password"));
        putFixed(buffer, 11, 24, bytes(user.getName()), true);
        buffer.putInt(35, (int) user.getCardno());
        buffer.put(39, (byte) user.getGroupId());
        buffer.putShort(40, (short) user.getUserTimeZoneFlag());
        buffer.putShort(42, (short) user.getTimeZone1());
        buffer.putShort(44, (short) user.getTimeZone2());
        buffer.putShort(46, (short) user.getTimeZone3());
        putFixed(buffer, 48, 9, ascii(user.getUserid(), "userid"));
        return buffer.array();
    }

    /**
     * Décode le prochain profil et avance la position du buffer de 72 octets.
     * @param source buffer contenant au moins un enregistrement complet
     * @return profil décodé
     * @throws IllegalArgumentException si le buffer est null ou trop court
     */
    public UserInfo decode(ByteBuffer source) {
        if (source == null || source.remaining() < RECORD_SIZE) {
            throw new IllegalArgumentException("A complete 72-byte user record is required");
        }
        ByteBuffer buffer = source.slice().order(ByteOrder.LITTLE_ENDIAN);
        UserInfo user = new UserInfo();
        user.setUid(Short.toUnsignedInt(buffer.getShort(0)));
        int permission = Byte.toUnsignedInt(buffer.get(2));
        user.setRole(decodeRole(permission & 0x0E));
        user.setEnabled((permission & 0x01) == 0);
        user.setPassword(readString(buffer, 3, 8, StandardCharsets.US_ASCII));
        user.setName(readString(buffer, 11, 24, nameCharset));
        user.setCardno(Integer.toUnsignedLong(buffer.getInt(35)));
        user.setGroupId(Byte.toUnsignedInt(buffer.get(39)));
        user.setUserTimeZoneFlag(Short.toUnsignedInt(buffer.getShort(40)));
        user.setTimeZone1(Short.toUnsignedInt(buffer.getShort(42)));
        user.setTimeZone2(Short.toUnsignedInt(buffer.getShort(44)));
        user.setTimeZone3(Short.toUnsignedInt(buffer.getShort(46)));
        user.setUserid(readString(buffer, 48, 9, StandardCharsets.US_ASCII));
        source.position(source.position() + RECORD_SIZE);
        return user;
    }

    /**
     * Vérifie les limites : userid ASCII 1..9 octets, mot de passe ASCII 0..8,
     * nom 0..23 octets encodés, carte non signée sur 32 bits et zones sur 16 bits.
     * @param user profil non null avec un rôle défini
     * @param requireUid true pour vérifier aussi que l'UID est entre 1 et 65535
     * @throws IllegalArgumentException si un champ ne respecte pas ces limites
     * @throws IllegalStateException si la représentation du groupe est invalide
     */
    public void validate(UserInfo user, boolean requireUid) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        if (requireUid && (user.getUid() < 1 || user.getUid() > 65535)) {
            throw new IllegalArgumentException("uid must be between 1 and 65535");
        }
        if (user.getRole() == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (user.getCardno() < 0 || user.getCardno() > 0xFFFFFFFFL) {
            throw new IllegalArgumentException("cardno must be an unsigned 32-bit value");
        }
        checkUnsignedShort(user.getUserTimeZoneFlag(), "userTimeZoneFlag");
        checkUnsignedShort(user.getTimeZone1(), "timeZone1");
        checkUnsignedShort(user.getTimeZone2(), "timeZone2");
        checkUnsignedShort(user.getTimeZone3(), "timeZone3");
        user.getGroupId();

        byte[] userId = ascii(user.getUserid(), "userid");
        if (userId.length == 0 || userId.length > 9) {
            throw new IllegalArgumentException("userid must contain between 1 and 9 ASCII bytes");
        }
        byte[] password = ascii(user.getPassword(), "password");
        if (password.length > 8) {
            throw new IllegalArgumentException("password must not exceed 8 ASCII bytes");
        }
        byte[] name = bytes(user.getName());
        if (name.length > 23) {
            throw new IllegalArgumentException("name must not exceed 23 encoded bytes");
        }
    }

    private byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(nameCharset);
    }

    private static byte[] ascii(String value, String field) {
        if (value == null) {
            return new byte[0];
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                throw new IllegalArgumentException(field + " must contain ASCII characters only");
            }
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void checkUnsignedShort(int value, String field) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException(field + " must be between 0 and 65535");
        }
    }

    private static void putFixed(ByteBuffer buffer, int offset, int size, byte[] value) {
        putFixed(buffer, offset, size, value, false);
    }

    private static void putFixed(ByteBuffer buffer, int offset, int size, byte[] value, boolean terminate) {
        int max = terminate ? size - 1 : size;
        if (value.length > max) {
            throw new IllegalArgumentException("Encoded value exceeds its protocol field");
        }
        for (int i = 0; i < value.length; i++) {
            buffer.put(offset + i, value[i]);
        }
    }

    private static String readString(ByteBuffer buffer, int offset, int size, Charset charset) {
        byte[] value = new byte[size];
        ByteBuffer copy = buffer.duplicate();
        copy.position(offset);
        copy.get(value);
        int end = 0;
        while (end < value.length && value[end] != 0) {
            end++;
        }
        return new String(value, 0, end, charset);
    }

    private static UserRoleEnum decodeRole(int token) {
        for (UserRoleEnum role : UserRoleEnum.values()) {
            if (role.getRole() == token) {
                return role;
            }
        }
        return UserRoleEnum.USER_DEFAULT;
    }
}
