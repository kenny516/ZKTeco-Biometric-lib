package com.zkteco.terminal;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import org.junit.Test;

import com.zkteco.commands.ZKCommand;
import com.zkteco.commands.UserInfo;
import com.zkteco.commands.UserRecordCodec;
import com.zkteco.Enum.UserRoleEnum;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.List;

public class ZKTerminalFingerprintReadTest {
    @Test
    public void consumesFinalUserTransferAcknowledgementBeforeReadingFingerprint() throws Exception {
        byte[] userBytes = new UserRecordCodec(StandardCharsets.UTF_8).encode(
                new UserInfo(64, "EMP1", "Alice", "", UserRoleEnum.USER_DEFAULT, 0));
        int[] payload = new int[76];
        payload[0] = 72;
        for (int i = 0; i < userBytes.length; i++) { payload[4 + i] = userBytes[i] & 255; }
        try (Fixture fixture = new Fixture(packet(1500, 76, 0, 0, 0), packet(1501, payload),
                packet(2000), packet(1501, 11, 22, 33, 0))) {
            List<UserInfo> users = fixture.terminal.getAllUsers();
            assertEquals(1, users.size());
            assertEquals(64, users.get(0).getUid());
            assertEquals(1, fixture.terminal.responses.size());
            assertArrayEquals(new byte[] { 11, 22, 33 }, fixture.terminal.getUserFingerprint(64, 1));
        }
    }

    @Test
    public void acceptsEmptyAcknowledgementBeforeDirectData() throws Exception {
        try (Fixture fixture = new Fixture(packet(2000), packet(1501, 11, 22, 33, 0))) {
            assertArrayEquals(new byte[] { 11, 22, 33 }, fixture.terminal.getUserFingerprint(1, 0));
            assertTrue(fixture.terminal.responses.isEmpty());
        }
    }

    @Test
    public void acceptsAcknowledgementBeforePreparedTransfer() throws Exception {
        try (Fixture fixture = new Fixture(packet(2000), packet(1500, 4, 0, 0, 0),
                packet(1501, 11, 22, 33, 0), packet(2000))) {
            assertArrayEquals(new byte[] { 11, 22, 33 }, fixture.terminal.getUserFingerprint(1, 0));
            assertTrue(fixture.terminal.responses.isEmpty());
        }
    }

    @Test
    public void reportsAcknowledgementWithoutDataAsTimeout() throws Exception {
        try (Fixture fixture = new Fixture(packet(2000))) {
            try {
                fixture.terminal.getUserFingerprint(1, 0);
                fail("Expected timeout");
            } catch (SocketTimeoutException e) {
                assertTrue(e.getMessage().contains("CMD_ACK_OK"));
                assertTrue(e.getMessage().contains("no fingerprint data"));
            }
            assertEquals(1234, fixture.client.getSoTimeout());
        }
    }

    @Test(timeout = 5000)
    public void readsDirectTemplateAndSendsUidAndFinger() throws Exception {
        try (Fixture fixture = new Fixture(packet(1501, 11, 22, 33, 0))) {
            assertArrayEquals(new byte[] { 11, 22, 33 }, fixture.terminal.getUserFingerprint(513, 4));
            byte[] bytes = new byte[128];
            DatagramPacket request = new DatagramPacket(bytes, bytes.length);
            fixture.device.receive(request);
            assertEquals(88, (bytes[0] & 0xFF) | ((bytes[1] & 0xFF) << 8));
            assertArrayEquals(new byte[] { 1, 2, 4 }, Arrays.copyOfRange(bytes, 8, request.getLength()));
            assertEquals(1234, fixture.client.getSoTimeout());
        }
    }

    @Test
    public void assemblesTransferAndRemovesTrailerAndPadding() throws Exception {
        try (Fixture fixture = new Fixture(packet(1500, 10, 0, 0, 0),
                packet(1501, 11, 22), packet(1501, 33, 0, 0, 0, 0, 0, 0, 0), packet(2000))) {
            assertArrayEquals(new byte[] { 11, 22, 33 }, fixture.terminal.getUserFingerprint(1, 0));
            assertTrue(fixture.terminal.responses.isEmpty());
        }
    }

    @Test
    public void rejectsIncompleteTransferAndRestoresTimeout() throws Exception {
        try (Fixture fixture = new Fixture(packet(1500, 4, 0, 0, 0),
                packet(1501, 11, 22), packet(2000))) {
            try {
                fixture.terminal.getUserFingerprint(1, 0);
                fail("Expected incomplete transfer error");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("Incomplete"));
            }
            assertEquals(1234, fixture.client.getSoTimeout());
        }
    }

    @Test
    public void reportsDeviceRejectionWithoutClaimingFingerIsAbsent() throws Exception {
        try (Fixture fixture = new Fixture(packet(2001))) {
            try {
                fixture.terminal.getUserFingerprint(1, 0);
                fail("Expected rejection");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("2001"));
            }
        }
    }

    @Test
    public void explainsDatabaseReadFailureAfterPendingAcknowledgement() throws Exception {
        try (Fixture fixture = new Fixture(packet(2000), packet(4993))) {
            try {
                fixture.terminal.getUserFingerprint(42, 0);
                fail("Expected database error");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("4993"));
                assertTrue(e.getMessage().contains("Failed to read data from the database"));
                assertTrue(e.getMessage().contains("uid=42"));
            }
        }
    }

    @Test
    public void refusesReadWhileRealtimeListenerIsActive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            setField(fixture.terminal, "realtimeRunning", true);
            try {
                fixture.terminal.getUserFingerprint(1, 0);
                fail("Expected realtime guard");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("realtime"));
            }
        }
    }

    private static int[] packet(int command, int... data) {
        return ZKCommand.getPacket(command, 10, 1, data);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ZKTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class ScriptedTerminal extends ZKTerminal {
        private final Queue<int[]> responses = new ArrayDeque<int[]>();

        ScriptedTerminal(int port, int[][] packets) {
            super("127.0.0.1", port);
            responses.addAll(Arrays.asList(packets));
        }

        @Override
        public Map<String, Integer> getDeviceStatus() {
            return Collections.singletonMap("userCount", 1);
        }

        @Override
        public int[] readResponse() throws IOException {
            if (responses.isEmpty()) {
                throw new SocketTimeoutException("No scripted response");
            }
            return responses.remove();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final DatagramSocket device = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        private final DatagramSocket client = new DatagramSocket();
        private final ScriptedTerminal terminal;

        Fixture(int[]... packets) throws Exception {
            device.setSoTimeout(1000);
            client.setSoTimeout(1234);
            terminal = new ScriptedTerminal(device.getLocalPort(), packets);
            setField(terminal, "socket", client);
            setField(terminal, "address", InetAddress.getByName("127.0.0.1"));
            setField(terminal, "sessionId", 10);
        }

        @Override
        public void close() {
            client.close();
            device.close();
        }
    }
}
