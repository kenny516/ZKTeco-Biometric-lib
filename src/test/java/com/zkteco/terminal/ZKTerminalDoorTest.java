package com.zkteco.terminal;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.commands.ZKCommandReply;

public class ZKTerminalDoorTest {
    @Test
    public void sendsUnlockAndReturnsDeviceReply() throws Exception {
        try (DatagramSocket device = new DatagramSocket(0);
             DatagramSocket client = new DatagramSocket()) {
            device.setSoTimeout(2000);
            client.setSoTimeout(2000);
            ZKTerminal terminal = new ZKTerminal("127.0.0.1", device.getLocalPort());
            set(terminal, "socket", client);
            set(terminal, "address", InetAddress.getLoopbackAddress());
            set(terminal, "sessionId", 42);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                int[] seconds = {5, 30, 429496729};
                for (int i = 0; i < seconds.length; i++) {
                    final int duration = seconds[i];
                    Future<ZKCommandReply> result = executor.submit(() -> terminal.unlockDoor(duration));
                    DatagramPacket request = new DatagramPacket(new byte[64], 64);
                    device.receive(request);
                    assertEquals(12, request.getLength());
                    ByteBuffer bytes = ByteBuffer.wrap(request.getData()).order(ByteOrder.LITTLE_ENDIAN);
                    assertEquals(31, bytes.getShort(0) & 0xffff);
                    assertEquals(42, bytes.getShort(4) & 0xffff);
                    assertEquals(i, bytes.getShort(6) & 0xffff);
                    assertEquals(duration * 10L, Integer.toUnsignedLong(bytes.getInt(8)));
                    int code = i == 1 ? 2001 : 2000;
                    byte[] response = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort((short) code).putShort((short) 0).putShort((short) 42)
                            .putShort((short) (i + 1)).put((byte) 7).array();
                    device.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                    assertEquals(CommandReplyCodeEnum.decode(code), result.get(3, TimeUnit.SECONDS).getCode());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void rejectsInvalidDurationsBeforeSending() throws Exception {
        ZKTerminal terminal = new ZKTerminal("127.0.0.1", 4370);
        for (int seconds : new int[] {0, -1, 429496730, Integer.MAX_VALUE}) {
            try {
                terminal.unlockDoor(seconds);
                fail("Expected invalid duration");
            } catch (IllegalArgumentException expected) {
                // Validation precedes connection checks.
            }
        }
    }

    @Test(expected = IOException.class)
    public void requiresConnection() throws Exception {
        new ZKTerminal("127.0.0.1", 4370).unlockDoor(5);
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedReply() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            ZKTerminal terminal = new ZKTerminal("127.0.0.1", 4370) {
                @Override public int[] readResponse() { return new int[2]; }
            };
            set(terminal, "socket", client);
            set(terminal, "address", InetAddress.getLoopbackAddress());
            terminal.unlockDoor(5);
        }
    }

    @Test
    public void doesNotRetryAfterTimeout() throws Exception {
        try (DatagramSocket device = new DatagramSocket(0);
             DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(100);
            device.setSoTimeout(100);
            ZKTerminal terminal = new ZKTerminal("127.0.0.1", device.getLocalPort());
            set(terminal, "socket", client);
            set(terminal, "address", InetAddress.getLoopbackAddress());
            try {
                terminal.unlockDoor(5);
                fail("Expected timeout");
            } catch (SocketTimeoutException expected) { }
            device.receive(new DatagramPacket(new byte[64], 64));
            try {
                device.receive(new DatagramPacket(new byte[64], 64));
                fail("Unexpected retry");
            } catch (SocketTimeoutException expected) { }
        }
    }

    private static void set(ZKTerminal terminal, String name, Object value) throws Exception {
        Field field = ZKTerminal.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(terminal, value);
    }
}
