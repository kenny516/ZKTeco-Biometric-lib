package com.zkteco.commands;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.zkteco.Enum.UserRoleEnum;

public class UserRecordCodecTest {
    private final UserRecordCodec codec = new UserRecordCodec(StandardCharsets.UTF_8);

    @Test
    public void roundTripsEverySupportedField() {
        UserInfo user = fullUser();
        byte[] encoded = codec.encode(user);

        assertEquals(72, encoded.length);
        assertEquals(42, Short.toUnsignedInt(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getShort(0)));
        assertEquals(UserRoleEnum.USER_MANAGER.getRole() | 1, Byte.toUnsignedInt(encoded[2]));
        assertEquals(0xFEDCBA98L,
                Integer.toUnsignedLong(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(35)));
        assertEquals(7, Byte.toUnsignedInt(encoded[39]));
        assertEquals(0, encoded[11 + "José".getBytes(StandardCharsets.UTF_8).length]);

        UserInfo decoded = codec.decode(ByteBuffer.wrap(encoded));
        assertEquals(42, decoded.getUid());
        assertEquals(UserRoleEnum.USER_MANAGER, decoded.getRole());
        assertFalse(decoded.isEnabled());
        assertEquals("12345678", decoded.getPassword());
        assertEquals("José", decoded.getName());
        assertEquals(0xFEDCBA98L, decoded.getCardno());
        assertEquals(7, decoded.getGroupId());
        assertEquals(3, decoded.getUserTimeZoneFlag());
        assertEquals(100, decoded.getTimeZone1());
        assertEquals(200, decoded.getTimeZone2());
        assertEquals(65535, decoded.getTimeZone3());
        assertEquals("EMP000042", decoded.getUserid());
    }

    @Test
    public void generatedRecordMatchesExpectedOffsets() {
        UserInfo user = fullUser();
        byte[] encoded = codec.encode(user);

        assertArrayEquals("12345678".getBytes(StandardCharsets.US_ASCII), slice(encoded, 3, 8));
        assertArrayEquals("EMP000042".getBytes(StandardCharsets.US_ASCII), slice(encoded, 48, 9));
        assertTrue(allZero(encoded, 57, 15));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonAsciiUserId() {
        UserInfo user = fullUser();
        user.setUserid("EMP-é");
        codec.encode(user);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNameThatDoesNotLeaveRoomForTerminator() {
        UserInfo user = fullUser();
        user.setName("123456789012345678901234");
        codec.encode(user);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCardOutsideUnsignedIntRange() {
        UserInfo user = fullUser();
        user.setCardno(0x1_0000_0000L);
        codec.encode(user);
    }

    private static UserInfo fullUser() {
        UserInfo user = new UserInfo(42, "EMP000042", "José", "12345678",
                UserRoleEnum.USER_MANAGER, 0xFEDCBA98L);
        user.setEnabled(false);
        user.setGroupId(7);
        user.setUserTimeZoneFlag(3);
        user.setTimeZone1(100);
        user.setTimeZone2(200);
        user.setTimeZone3(65535);
        return user;
    }

    private static byte[] slice(byte[] bytes, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(bytes, offset, result, 0, length);
        return result;
    }

    private static boolean allZero(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
