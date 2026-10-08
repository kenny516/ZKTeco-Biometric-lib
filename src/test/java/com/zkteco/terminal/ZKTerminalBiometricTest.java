package com.zkteco.terminal;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.Enum.UserRoleEnum;
import com.zkteco.commands.*;

public class ZKTerminalBiometricTest {
    @Test
    public void snapshotKeepsDatabaseErrorsAndSuccessfulFinger() throws Exception {
        MockTerminal terminal = new MockTerminal(4370) {
            @Override public byte[] getUserFingerprint(int uid, int index) throws IOException {
                assertEquals(2, uid);
                if (index == 0) { throw new FingerprintReadException(4993, "Database read failed"); }
                return new byte[] { 11, 22 };
            }
        };
        UserBiometricData data = terminal.getUserWithFingerprints("EMP1", 0, 1);
        assertEquals(1, data.getFingerprints().size());
        assertEquals(1, data.getFingerprints().get(0).getFingerIndex());
        assertEquals("Database read failed", data.getFingerprintReadErrors().get(0));
        assertFalse(data.isComplete());
    }

    @Test(expected = IOException.class)
    public void snapshotAbortsOnTransportFailure() throws Exception {
        new MockTerminal(4370) {
            @Override public byte[] getUserFingerprint(int uid, int index) throws IOException {
                throw new IOException("Connection lost");
            }
        }.getUserWithFingerprints("EMP1", 1);
    }

    @Test(timeout = 10000)
    public void uploadsChunkedTemplatesUsingDestinationUid() throws Exception {
        verifyUpload(false, false);
    }

    @Test(timeout = 10000)
    public void reportsPartialResultAndReenablesReaderAfterUploadRejection() throws Exception {
        verifyUpload(true, false);
    }

    @Test(timeout = 10000)
    public void preservesUploadResultWhenBufferCleanupFails() throws Exception {
        verifyUpload(false, true);
    }

    private void verifyUpload(boolean rejectPreparation, boolean rejectCleanup) throws Exception {
        try (DatagramSocket device = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                DatagramSocket client = new DatagramSocket()) {
            device.setSoTimeout(3000);
            client.setSoTimeout(1234);
            List<Integer> commands = Collections.synchronizedList(new ArrayList<Integer>());
            ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
            AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            Thread server = new Thread(() -> {
                try {
                    boolean last = false;
                    int freeCount = 0;
                    while (!last) {
                        byte[] bytes = new byte[2048];
                        DatagramPacket request = new DatagramPacket(bytes, bytes.length);
                        device.receive(request);
                        int command = (bytes[0] & 255) | ((bytes[1] & 255) << 8);
                        int replyId = (bytes[6] & 255) | ((bytes[7] & 255) << 8);
                        commands.add(command);
                        if (command == 1501) { uploaded.write(bytes, 8, request.getLength() - 8); }
                        if (command == 1502 && ++freeCount == 2) { last = true; }
                        int code = (rejectPreparation && command == 1500) || (rejectCleanup && last) ? 2001 : 2000;
                        int[] ack = ZKCommand.getPacket(code, 10, replyId, null);
                        byte[] ackBytes = new byte[ack.length];
                        for (int i = 0; i < ack.length; i++) { ackBytes[i] = (byte) ack[i]; }
                        device.send(new DatagramPacket(ackBytes, ackBytes.length, request.getAddress(), request.getPort()));
                    }
                } catch (Throwable e) { failure.set(e); }
            });
            server.start();
            MockTerminal terminal = new MockTerminal(device.getLocalPort());
            setField(terminal, "socket", client);
            setField(terminal, "address", InetAddress.getByName("127.0.0.1"));
            setField(terminal, "sessionId", 10);
            byte[] template = new byte[1060];
            Arrays.fill(template, (byte) 17);
            UserInfo source = new UserInfo(64, "EMP1", "Alice", "", UserRoleEnum.USER_DEFAULT, 0);
            UserBiometricData data = new UserBiometricData(source, Arrays.asList(new FingerprintTemplate(1, template)));
            UserBiometricWriteResult result = terminal.addUserWithFingerprints(data);
            server.join(4000);
            assertFalse(server.isAlive());
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            assertTrue(result.getProfile().isSuccess());
            assertEquals(2, result.getAssignedUid());
            assertEquals(64, data.getUser().getUid());
            assertEquals(2, terminal.enableCount);
            assertEquals(1234, client.getSoTimeout());
            if (rejectPreparation) {
                assertFalse(result.isSuccess());
                assertFalse(result.isFingerprintsWritten());
                assertEquals(Arrays.asList(8, 1502, 1500, 1502), commands);
            } else {
                assertEquals(!rejectCleanup, result.isSuccess());
                assertTrue(result.isFingerprintsWritten());
                assertEquals(!rejectCleanup, result.isDeviceRestored());
                assertEquals(Arrays.asList(8, 1502, 1500, 1501, 1501, 110, 1502), commands);
                ByteBuffer buffer = ByteBuffer.wrap(uploaded.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(2, Short.toUnsignedInt(buffer.getShort(13)));
                assertEquals(2, Short.toUnsignedInt(buffer.getShort(86)));
                assertEquals(1060, Short.toUnsignedInt(buffer.getShort(93)));
                assertArrayEquals(template, Arrays.copyOfRange(buffer.array(), 95, buffer.array().length));
            }
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ZKTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class MockTerminal extends ZKTerminal {
        int enableCount;
        MockTerminal(int port) { super("127.0.0.1", port); }
        @Override public List<UserInfo> getAllUsers() {
            return Arrays.asList(new UserInfo(2, "EMP1", "Alice", "", UserRoleEnum.USER_DEFAULT, 0));
        }
        @Override public Map<String, Integer> getDeviceStatus() { return Collections.singletonMap("remainingUser", 10); }
        @Override public ZKCommandReply disableDevice() { return ok(); }
        @Override public ZKCommandReply enableDevice() { enableCount++; return ok(); }
        @Override public ZKCommandReply RefreshData() throws ParseException { return ok(); }
        private ZKCommandReply ok() { return new ZKCommandReply(CommandReplyCodeEnum.CMD_ACK_OK, 10, 1, new int[0]); }
    }
}
