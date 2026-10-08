package com.zkteco.commands;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zkteco.Enum.UserRoleEnum;

public class UserBiometricDataTest {
    @Test
    public void jsonRoundTripPreservesProfileTemplatesAndReadErrors() throws Exception {
        UserInfo user = new UserInfo(64, "32002262", "José", "1234", UserRoleEnum.USER_MANAGER, 0xFEDCBA98L);
        user.setEnabled(false);
        user.setGroupId(7);
        user.setTimeZone1(123);
        user.setTimeZone2(456);
        user.setTimeZone3(789);
        user.setUserTimeZoneFlag(3);
        UserBiometricData original = new UserBiometricData(user,
                Arrays.asList(new FingerprintTemplate(1, new byte[] { 0, 1, (byte) 255 })),
                Collections.singletonMap(0, "4993: read failed"));
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"template\":\"AAH/\""));
        assertFalse(json.contains("\"complete\""));
        UserBiometricData restored = mapper.readValue(json, UserBiometricData.class);
        assertArrayEquals(new UserRecordCodec(StandardCharsets.UTF_8).encode(user),
                new UserRecordCodec(StandardCharsets.UTF_8).encode(restored.getUser()));
        assertArrayEquals(original.getFingerprints().get(0).getTemplate(), restored.getFingerprints().get(0).getTemplate());
        assertEquals(original.getFingerprintReadErrors(), restored.getFingerprintReadErrors());
        assertFalse(restored.isComplete());
    }

    @Test
    public void protectsSnapshotFromCallerMutations() {
        UserInfo user = user();
        byte[] bytes = { 1, 2, 3 };
        FingerprintTemplate fingerprint = new FingerprintTemplate(1, bytes);
        UserBiometricData data = new UserBiometricData(user, Arrays.asList(fingerprint));
        user.setUserid("OTHER");
        data.getUser().setUserid("CHANGED");
        bytes[0] = 99;
        fingerprint.getTemplate()[0] = 88;
        assertEquals("EMP1", data.getUser().getUserid());
        assertArrayEquals(new byte[] { 1, 2, 3 }, data.getFingerprints().get(0).getTemplate());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateFingerIndices() {
        new UserBiometricData(user(), Arrays.asList(new FingerprintTemplate(1, new byte[] { 1 }),
                new FingerprintTemplate(1, new byte[] { 2 })));
    }

    @Test
    public void encodesUserTemplateTableAndOffsetsForDestinationUid() {
        byte[] packet = new UserBiometricCodec(StandardCharsets.UTF_8).encode(user(), Arrays.asList(
                new FingerprintTemplate(1, new byte[] { 11, 22, 33 }),
                new FingerprintTemplate(4, new byte[] { 44, 55 })));
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(73, buffer.getInt());
        assertEquals(16, buffer.getInt());
        assertEquals(9, buffer.getInt());
        assertEquals(2, buffer.get());
        assertEquals(42, Short.toUnsignedInt(buffer.getShort()));
        buffer.position(85);
        assertEquals(2, buffer.get());
        assertEquals(42, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(0x11, buffer.get());
        assertEquals(0, buffer.getInt());
        assertEquals(2, buffer.get());
        assertEquals(42, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(0x14, buffer.get());
        assertEquals(5, buffer.getInt());
        assertEquals(3, buffer.getShort());
        byte[] first = new byte[3];
        buffer.get(first);
        assertArrayEquals(new byte[] { 11, 22, 33 }, first);
        assertEquals(2, buffer.getShort());
        assertEquals(44, buffer.get());
        assertEquals(55, buffer.get());
        assertEquals(0, buffer.remaining());
    }

    private static UserInfo user() {
        return new UserInfo(42, "EMP1", "Alice", "", UserRoleEnum.USER_DEFAULT, 0);
    }
}
