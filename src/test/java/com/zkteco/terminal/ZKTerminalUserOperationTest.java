package com.zkteco.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.zkteco.Enum.CommandCodeEnum;
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.Enum.UserRoleEnum;
import com.zkteco.command.events.EventCode;
import com.zkteco.commands.FingerprintEnrollmentResult;
import com.zkteco.commands.UserInfo;
import com.zkteco.commands.UserOperationStatus;
import com.zkteco.commands.UserWriteResult;
import com.zkteco.commands.ZKCommand;
import com.zkteco.commands.ZKCommandReply;

public class ZKTerminalUserOperationTest {
    @Test
    public void allocatesLowestFreeUid() {
        assertEquals(2, ZKTerminal.firstFreeUid(new HashSet<Integer>(Arrays.asList(1, 3, 4))));
        assertEquals(1, ZKTerminal.firstFreeUid(Collections.<Integer>emptySet()));
    }

    @Test
    public void recognizesBothRealtimePacketCommandForms() {
        int[] direct = eventPacket(CommandCodeEnum.CMD_REG_EVENT.getCode(), EventCode.EF_FPFTR.getCode(), 100);
        int[] retry = eventPacket(2003, EventCode.EF_FPFTR.getCode(), 100);
        assertTrue(ZKTerminal.isRealtimeEvent(direct));
        assertTrue(ZKTerminal.isRealtimeEvent(retry));
        assertFalse(ZKTerminal.isRealtimeEvent(new int[] { 0, 0 }));
    }

    @Test
    public void parsesSuccessfulEnrollmentEvent() {
        ZKTerminal terminal = new ZKTerminal("127.0.0.1", 4370);
        int[] event = new int[22];
        event[0] = CommandCodeEnum.CMD_REG_EVENT.getCode() & 0xFF;
        event[1] = CommandCodeEnum.CMD_REG_EVENT.getCode() >> 8;
        event[4] = EventCode.EF_ENROLLFINGER.getCode();
        event[10] = 0x34;
        event[11] = 0x02;
        byte[] userId = "USR42".getBytes();
        for (int i = 0; i < userId.length; i++) {
            event[12 + i] = userId[i];
        }
        event[21] = 3;

        FingerprintEnrollmentResult result = terminal.parseEnrollmentResult(
                event, "USR42", 3, Arrays.asList(100, 100, 100));

        assertEquals(UserOperationStatus.SUCCESS, result.getStatus());
        assertEquals(564, result.getTemplateSize());
        assertEquals(Arrays.asList(100, 100, 100), result.getSampleScores());
        assertEquals("USR42", result.getUserId());
        assertEquals(3, result.getFingerIndex());
    }

    @Test
    public void rejectsMismatchedEnrollmentEvent() {
        ZKTerminal terminal = new ZKTerminal("127.0.0.1", 4370);
        int[] event = new int[22];
        byte[] userId = "OTHER".getBytes();
        for (int i = 0; i < userId.length; i++) {
            event[12 + i] = userId[i];
        }
        event[21] = 1;

        FingerprintEnrollmentResult result = terminal.parseEnrollmentResult(
                event, "USR42", 3, Collections.<Integer>emptyList());
        assertEquals(UserOperationStatus.ENROLLMENT_FAILED, result.getStatus());
    }

    @Test(timeout = 5000)
    public void writesProfileBetweenDisableRefreshAndEnable() throws Exception {
        DatagramSocket deviceSocket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        DatagramSocket clientSocket = new DatagramSocket();
        List<String> sequence = Collections.synchronizedList(new ArrayList<String>());
        AtomicReference<Throwable> serverFailure = new AtomicReference<Throwable>();
        AtomicReference<byte[]> receivedRecord = new AtomicReference<byte[]>();

        Thread device = new Thread(() -> {
            try {
                byte[] bytes = new byte[256];
                DatagramPacket request = new DatagramPacket(bytes, bytes.length);
                deviceSocket.receive(request);
                sequence.add("write");
                assertEquals(CommandCodeEnum.CMD_USER_WRQ.getCode(),
                        (bytes[0] & 0xFF) + ((bytes[1] & 0xFF) << 8));
                receivedRecord.set(Arrays.copyOfRange(bytes, 8, request.getLength()));

                int[] ack = ZKCommand.getPacket(CommandReplyCodeEnum.CMD_ACK_OK.getCode(), 10, 1, null);
                byte[] ackBytes = new byte[ack.length];
                for (int i = 0; i < ack.length; i++) {
                    ackBytes[i] = (byte) ack[i];
                }
                deviceSocket.send(new DatagramPacket(ackBytes, ackBytes.length,
                        request.getAddress(), request.getPort()));
            } catch (Throwable t) {
                serverFailure.set(t);
            }
        });
        device.start();

        MockTerminal terminal = new MockTerminal(deviceSocket.getLocalPort(), sequence);
        setField(terminal, "socket", clientSocket);
        setField(terminal, "address", InetAddress.getByName("127.0.0.1"));
        setField(terminal, "sessionId", 10);

        UserInfo requested = new UserInfo("USR2", "Alice", "1234",
                UserRoleEnum.USER_DEFAULT, 70000L);
        UserWriteResult result;
        try {
            result = terminal.addUser(requested);
        } finally {
            device.join();
            clientSocket.close();
            deviceSocket.close();
        }

        if (serverFailure.get() != null) {
            throw new AssertionError(serverFailure.get());
        }
        assertTrue(result.isSuccess());
        assertTrue(result.isCreated());
        assertEquals(2, result.getAssignedUid());
        assertEquals(Arrays.asList("disable", "write", "refresh", "enable"), sequence);
        assertEquals(2, (receivedRecord.get()[0] & 0xFF) | ((receivedRecord.get()[1] & 0xFF) << 8));
    }

    private static int[] eventPacket(int command, int event, int value) {
        return new int[] {
                command & 0xFF, (command >> 8) & 0xFF, 0, 0,
                event & 0xFF, (event >> 8) & 0xFF, 0, 0, value
        };
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ZKTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ZKCommandReply okReply() {
        return new ZKCommandReply(CommandReplyCodeEnum.CMD_ACK_OK, 10, 1, new int[0]);
    }

    private static final class MockTerminal extends ZKTerminal {
        private final List<String> sequence;

        private MockTerminal(int port, List<String> sequence) {
            super("127.0.0.1", port);
            this.sequence = sequence;
        }

        @Override
        public List<UserInfo> getAllUsers() {
            UserInfo first = new UserInfo(1, "USR1", "Existing", "",
                    UserRoleEnum.USER_DEFAULT, 0);
            UserInfo third = new UserInfo(3, "USR3", "Existing", "",
                    UserRoleEnum.USER_DEFAULT, 0);
            return Arrays.asList(first, third);
        }

        @Override
        public Map<String, Integer> getDeviceStatus() {
            Map<String, Integer> status = new HashMap<String, Integer>();
            status.put("remainingUser", 10);
            return status;
        }

        @Override
        public ZKCommandReply disableDevice() {
            sequence.add("disable");
            return okReply();
        }

        @Override
        public ZKCommandReply RefreshData() throws ParseException {
            sequence.add("refresh");
            return okReply();
        }

        @Override
        public ZKCommandReply enableDevice() {
            sequence.add("enable");
            return okReply();
        }
    }
}
