package com.zkteco.terminal;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.FileWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.apache.commons.lang3.StringUtils;

import com.zkteco.Enum.OnOffenum;
import com.zkteco.Exception.DeviceNotConnectException;
import com.zkteco.command.events.EventCode;
import com.zkteco.command.events.ErrorCode;
import com.zkteco.commands.AttendanceRecord;
import com.zkteco.Enum.AttendanceStateEnum;
import com.zkteco.Enum.AttendanceTypeEnum;
import com.zkteco.Enum.CommandCodeEnum;
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.commands.GetTimeReply;
import com.zkteco.commands.SmsInfo;
import com.zkteco.commands.UserInfo;
import com.zkteco.commands.UserRecordCodec;
import com.zkteco.commands.UserWriteResult;
import com.zkteco.commands.UserEnrollmentResult;
import com.zkteco.commands.UserOperationStatus;
import com.zkteco.commands.FingerprintEnrollmentResult;
import com.zkteco.commands.FingerprintTemplate;
import com.zkteco.commands.FingerprintReadException;
import com.zkteco.commands.UserBiometricData;
import com.zkteco.commands.UserBiometricWriteResult;
import com.zkteco.commands.UserBiometricCodec;
import com.zkteco.commands.ZKCommand;
import com.zkteco.commands.ZKCommandReply;
import com.zkteco.utils.HexUtils;
import com.zkteco.utils.SecurityUtils;

/**
 * Client UDP pour un lecteur ZKTeco : profils, empreintes et pointages.
 * <p>Ouvrir une session avec {@link #connect()}, vérifier sa réponse et, si le lecteur
 * demande une authentification, appeler {@link #connectAuth(int)}. Fermer la session
 * avec {@link #disconnect()} dans un bloc {@code finally}.
 * <p>Le socket est partagé par les commandes et le listener temps réel. Ne pas lancer
 * de commandes depuis le callback de {@link #startRealTimeLogs(java.util.function.Consumer)}.
 * Les opérations biométriques sont sérialisées entre elles, mais ce client ne garantit
 * pas la sécurité de toutes les commandes lors d'appels concurrents.
 * @see UserBiometricData
 */
public class ZKTerminal {

    private DatagramSocket socket;
    private InetAddress address;

    private final String ip;
    private final int port;

    private int sessionId;
    private int replyNo;

    private static final Duration DEFAULT_ENROLLMENT_TIMEOUT = Duration.ofSeconds(60);
    private final Object userOperationLock = new Object();
    private final UserRecordCodec userRecordCodec;
    private final UserBiometricCodec userBiometricCodec;
    private int registeredEventMask;

    private volatile boolean realtimeRunning = false;
    private Thread realtimeThread;

    /**
     * Prépare un client, sans ouvrir de connexion, avec des noms encodés en UTF-8.
     * @param ip adresse IP ou nom d'hôte du lecteur
     * @param port port UDP du lecteur, généralement 4370
     */
    public ZKTerminal(String ip, int port) {
        this(ip, port, StandardCharsets.UTF_8);
    }

    /**
     * Prépare un client avec l'encodage des noms utilisé par le firmware.
     * Les identifiants et mots de passe restent en ASCII.
     * @param ip adresse du lecteur
     * @param port port UDP
     * @param userCharset encodage des noms, non null
     * @throws IllegalArgumentException si l'encodage est null
     */
    public ZKTerminal(String ip, int port, Charset userCharset) {
        if (userCharset == null) {
            throw new IllegalArgumentException("userCharset must not be null");
        }
        this.ip = ip;
        this.port = port;
        this.userRecordCodec = new UserRecordCodec(userCharset);
        this.userBiometricCodec = new UserBiometricCodec(userCharset);
    }

    /**
     * Vérifie la joignabilité par ping, ouvre le socket et demande une session.
     * @return réponse du lecteur ; {@code CMD_ACK_UNAUTH} demande une authentification
     * @throws IOException si l'ouverture ou l'échange UDP échoue
     * @throws DeviceNotConnectException si le ping échoue
     * @see #connectAuth(int)
     */
    public ZKCommandReply connect() throws IOException, DeviceNotConnectException {
        if (!testPing()) {
            throw new DeviceNotConnectException("Device Not connect...!");
        }
        sessionId = 0;
        replyNo = 0;
        socket = new DatagramSocket(port);
        address = InetAddress.getByName(ip);
        socket.setSoTimeout(5000);

        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CONNECT, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        sessionId = response[4] + (response[5] * 0x100);
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Test devices connect or not
    public boolean testPing() {
        try {
            Process process;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                process = Runtime.getRuntime().exec("ping -n 1 " + ip);
            } else {
                process = Runtime.getRuntime().exec("ping -c 1 -W 5 " + ip);
            }
            int returnCode = process.waitFor();
            return returnCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Envoie la commande de fin de session et ferme le socket.
     * @throws IOException si l'envoi échoue
     * @see #stopRealTimeLogs()
     */
    public void disconnect() throws IOException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_EXIT, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        socket.close();
    }

    public void socketClose() {
        if (!socket.isClosed()) {
            socket.close();
        }
    }

    // Enable devices
    /**
     * Demande la réactivation du lecteur pour les vérifications normales.
     * @return réponse du lecteur ; vérifier {@code CMD_ACK_OK}
     * @throws IOException si l'échange UDP échoue
     */
    public ZKCommandReply enableDevice() throws IOException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_ENABLEDEVICE, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // disable devices
    /**
     * Demande la désactivation des vérifications pendant une modification de données.
     * Réactiver le lecteur dans un bloc {@code finally} après une réponse positive.
     * @return réponse du lecteur ; vérifier {@code CMD_ACK_OK}
     * @throws IOException si l'échange UDP échoue
     */
    public ZKCommandReply disableDevice() throws IOException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_DISABLEDEVICE, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    /**
     * Authentifie la session avec la clé de communication configurée sur le lecteur.
     * @param comKey clé de communication, différente du Device ID
     * @return réponse à vérifier : {@code CMD_ACK_OK} indique le succès
     * @throws IOException si l'échange UDP échoue
     */
    public ZKCommandReply connectAuth(int comKey) throws IOException {
        int[] key = SecurityUtils.authKey(comKey, sessionId);
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_AUTH, sessionId, replyNo, key);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Set Realtime
    public ZKCommandReply enableRealtime(EventCode... events) throws IOException {
        int allEvents = 0;
        for (EventCode event : events) {
            allEvents = allEvents | event.getCode();
        }
        return registerRealtimeEvents(allEvents);
    }

    private ZKCommandReply registerRealtimeEvents(int allEvents) throws IOException {
        String hex = StringUtils.leftPad(Integer.toHexString(allEvents), 8, "0");
        int[] eventReg = new int[4];
        int index = 3;
        while (hex.length() > 0) {
            eventReg[index] = (int) Long.parseLong(hex.substring(0, 2), 16);
            index--;
            hex = hex.substring(2);
        }
        // System.out.println(HexUtils.bytesToHex(eventReg));
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_REG_EVENT, sessionId, replyNo, eventReg);
        byte[] buf = new byte[toSend.length];
        index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            registeredEventMask = allEvents;
        }
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    /**
     * Abonne le lecteur aux pointages et démarre un listener en arrière-plan.
     * Retourne immédiatement ; un appel pendant une écoute active ne la redémarre pas.
     * <p>Charger les profils avant l'écoute pour associer le userid à un nom. Le callback
     * s'exécute sur le thread du listener et ne doit pas utiliser le socket du terminal.
     * <pre>{@code
     * terminal.startRealTimeLogs(record -> System.out.println(record.getUserID()));
     * // Plus tard : terminal.stopRealTimeLogs();
     * }</pre>
     * @param callback consommateur des pointages décodés, non null
     * @throws IOException si l'abonnement UDP échoue
     * @see #stopRealTimeLogs()
     */
    public void startRealTimeLogs(java.util.function.Consumer<AttendanceRecord> callback) throws IOException {
        if (realtimeRunning) {
            return;
        }
        enableRealtime(EventCode.EF_ATTLOG);
        realtimeRunning = true;
        realtimeThread = new Thread(() -> {
            while (realtimeRunning) {
                int[] response;
                try {
                    response = readResponse();
                } catch (java.net.SocketTimeoutException e) {
                    // No event during timeout window — keep waiting
                    continue;
                } catch (IOException e) {
                    if (realtimeRunning) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }

                // The device pushes CMD_REG_EVENT (500) packets for realtime events
                int cmdCode = response[0] + (response[1] * 0x100);
                if (cmdCode != CommandCodeEnum.CMD_REG_EVENT.getCode()) {
                    continue;
                }
                // EF_ATTLOG realtime payload: 8-byte header + 30 bytes (24 userID + 1
                // type + 4 time + 1 state)
                if (response.length < 8 + 30) {
                    continue;
                }

                try {
                    AttendanceRecord record = parseRealtimeAttendance(response);
                    if (record != null) {
                        callback.accept(record);
                    }
                } catch (java.text.ParseException e) {
                    // Malformed packet — skip
                }
            }
        }, "zkteco-realtime-listener");
        realtimeThread.setDaemon(true);
        realtimeThread.start();
    }

    /**
     * Demande l'arrêt du listener, sans fermer la session ni attendre la fin du thread.
     * Une réception UDP déjà bloquée peut se terminer au prochain paquet ou timeout.
     * @see #disconnect()
     */
    public void stopRealTimeLogs() {
        realtimeRunning = false;
        if (realtimeThread != null) {
            realtimeThread.interrupt();
            realtimeThread = null;
        }
    }

    /**
     * Parses a raw realtime EF_ATTLOG UDP packet into an AttendanceRecord.
     * Returns null if the packet is not a valid attendance event.
     *
     * Realtime EF_ATTLOG payload layout (after the 8-byte UDP header):
     * [0-23] userID (24 bytes, ASCII null-padded) — starts directly, NO binary
     * userSN prefix
     * [24] verifyType (1 byte)
     * [25-28] encodedTime (4 bytes little-endian uint32)
     * [29] verifyState (1 byte)
     */
    private AttendanceRecord parseRealtimeAttendance(int[] response) throws ParseException {
        // Payload starts after the 8-byte header
        int offset = 8;

        // userID: 24 bytes ASCII, null-padded — starts immediately (no binary userSN
        // prefix)
        StringBuilder userIdBuilder = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            int c = response[offset + i];
            if (c == 0)
                break;
            userIdBuilder.append((char) c);
        }
        String userId = userIdBuilder.toString().trim();
        offset += 24;

        // verifyType: 1 byte
        int verifyTypeOrdinal = response[offset];
        offset += 1;
        AttendanceTypeEnum verifyType;
        try {
            verifyType = AttendanceTypeEnum.values()[verifyTypeOrdinal];
        } catch (ArrayIndexOutOfBoundsException e) {
            verifyType = AttendanceTypeEnum.values()[0];
        }

        // encodedTime: 4 bytes little-endian
        long encDate = response[offset]
                + (response[offset + 1] * 0x100L)
                + (response[offset + 2] * 0x10000L)
                + (response[offset + 3] * 0x1000000L);
        offset += 4;
        java.util.Date recordTime = HexUtils.extractDate(encDate);

        // verifyState: 1 byte
        int verifyStateOrdinal = response[offset];
        AttendanceStateEnum verifyState;
        try {
            verifyState = AttendanceStateEnum.values()[verifyStateOrdinal];
        } catch (ArrayIndexOutOfBoundsException e) {
            verifyState = AttendanceStateEnum.values()[0];
        }

        // userSN not available in realtime packet (0 used as placeholder)
        return new AttendanceRecord(0, userId, verifyType, recordTime, verifyState);
    }

    // Get realtime Attendance log But other method not able to access(Address
    // already in use)
    public ZKCommandReply enableRealtimeAtt() throws IOException, ParseException {
        int allEvents = EventCode.EF_ATTLOG.getCode();
        String hex = StringUtils.leftPad(Integer.toHexString(allEvents), 8, "0");
        int[] eventReg = new int[4];
        int index = 3;
        while (hex.length() > 0) {
            eventReg[index] = (int) Long.parseLong(hex.substring(0, 2), 16);
            index--;
            hex = hex.substring(2);
        }
        // System.out.println(HexUtils.bytesToHex(eventReg));
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_REG_EVENT, sessionId, replyNo, eventReg);
        byte[] buf = new byte[toSend.length];
        index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Devices Power down
    public ZKCommandReply Poweroff() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_POWEROFF, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }
        socket.close();
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Restart Devices
    public ZKCommandReply restart() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_RESTART, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }

        socket.close();
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Get all devices Attendance Data
    /**
     * Télécharge les pointages enregistrés, sans démarrer de listener temps réel.
     * @return liste des pointages décodés
     * @throws IOException si le transfert UDP échoue
     * @throws ParseException si une date de pointage ne peut être décodée
     */
    public List<AttendanceRecord> getAttendanceRecords() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_ATTLOG_RRQ, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        List<AttendanceRecord> attendanceRecords = new ArrayList<>();
        StringBuilder attendanceBuffer = new StringBuilder();

        if (replyCode == CommandReplyCodeEnum.CMD_PREPARE_DATA) {
            boolean first = true;

            int lastDataRead;

            do {
                int[] readData = readResponse();

                lastDataRead = readData.length;

                String readPacket = HexUtils.bytesToHex(readData);

                attendanceBuffer.append(readPacket.substring(first ? 24 : 16));

                first = false;
            } while (lastDataRead == 1032);
        } else {
            attendanceBuffer.append(HexUtils.bytesToHex(response).substring(24));
        }

        String attendance = attendanceBuffer.toString();

        while (attendance.length() > 0) {
            String record = attendance.substring(0, 80);

            int seq = Integer.valueOf(record.substring(2, 4) + record.substring(0, 2), 16);

            record = record.substring(4);

            String userId = Character.toString((char) Integer.valueOf(record.substring(0, 2), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(2, 4), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(4, 6), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(6, 8), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(8, 10), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(10, 12), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(12, 14), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(14, 16), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(16, 18), 16).intValue());

            record = record.substring(48);

            int method = Integer.valueOf(record.substring(0, 2), 16);
            AttendanceTypeEnum attendanceType = AttendanceTypeEnum.values()[method];

            record = record.substring(2);

            long encDate = Integer.valueOf(record.substring(6, 8), 16) * 0x1000000L
                    + (Integer.valueOf(record.substring(4, 6), 16) * 0x10000L)
                    + (Integer.valueOf(record.substring(2, 4), 16) * 0x100L)
                    + (Integer.valueOf(record.substring(0, 2), 16));

            Date attendanceDate = HexUtils.extractDate(encDate);

            record = record.substring(8);

            int operation = Integer.valueOf(record.substring(0, 2), 16);
            AttendanceStateEnum attendanceState = AttendanceStateEnum.values()[operation];

            attendance = attendance.substring(80);
            AttendanceRecord attendanceRecord = new AttendanceRecord(seq, userId.trim(), attendanceType, attendanceDate,
                    attendanceState);
            attendanceRecords.add(attendanceRecord);
        }
        return attendanceRecords;
    }

    public List<AttendanceRecord> getAttendanceRecordsForDateRange(String startTime, String endTime)
            throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_ATTLOG_RRQ, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        List<AttendanceRecord> attendanceRecords = new ArrayList<>();
        StringBuilder attendanceBuffer = new StringBuilder();

        if (replyCode == CommandReplyCodeEnum.CMD_PREPARE_DATA) {
            boolean first = true;

            int lastDataRead;

            do {
                int[] readData = readResponse();

                lastDataRead = readData.length;

                String readPacket = HexUtils.bytesToHex(readData);

                attendanceBuffer.append(readPacket.substring(first ? 24 : 16));

                first = false;
            } while (lastDataRead == 1032);
        } else {
            attendanceBuffer.append(HexUtils.bytesToHex(response).substring(24));
        }

        String attendance = attendanceBuffer.toString();

        while (attendance.length() > 0) {
            String record = attendance.substring(0, 80);

            int seq = Integer.valueOf(record.substring(2, 4) + record.substring(0, 2), 16);

            record = record.substring(4);

            String userId = Character.toString((char) Integer.valueOf(record.substring(0, 2), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(2, 4), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(4, 6), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(6, 8), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(8, 10), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(10, 12), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(12, 14), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(14, 16), 16).intValue())
                    + Character.toString((char) Integer.valueOf(record.substring(16, 18), 16).intValue());

            record = record.substring(48);

            int method = Integer.valueOf(record.substring(0, 2), 16);
            AttendanceTypeEnum attendanceType = AttendanceTypeEnum.values()[method];

            record = record.substring(2);

            long encDate = Integer.valueOf(record.substring(6, 8), 16) * 0x1000000L
                    + (Integer.valueOf(record.substring(4, 6), 16) * 0x10000L)
                    + (Integer.valueOf(record.substring(2, 4), 16) * 0x100L)
                    + (Integer.valueOf(record.substring(0, 2), 16));

            Date attendanceDate = HexUtils.extractDate(encDate);

            record = record.substring(8);

            int operation = Integer.valueOf(record.substring(0, 2), 16);
            AttendanceStateEnum attendanceState = AttendanceStateEnum.values()[operation];

            attendance = attendance.substring(80);
            AttendanceRecord attendanceRecord = new AttendanceRecord(seq, userId.trim(), attendanceType, attendanceDate,
                    attendanceState);
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date startDate = dateFormat.parse(startTime);
            Date endDate = dateFormat.parse(endTime);

            if (attendanceDate.after(startDate) && attendanceDate.before(endDate)) {
                attendanceRecords.add(attendanceRecord);
            }
        }
        return attendanceRecords;
    }

    // Clear All Admin from device
    public ZKCommandReply clearAdminData() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CLEAR_ADMIN, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }

        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    public ZKCommandReply clearOpLogData() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CLEAR_OPLOG, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }
        socket.close();
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    public ZKCommandReply resetDevice() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CLEAR_DATA, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }
        socket.close();
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Clear All AttLog Data
    public ZKCommandReply clearAttLogData() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CLEAR_ATTLOG, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }

        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // ~IsOnlyRFMachine
    public String IsOnlyRFMachine() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~IsOnlyRFMachine".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get device FirmVerion
    public String getFirmwareVersion() throws IOException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_GET_VERSION, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            return responseString.trim();
        }
        return "";
    }

    public String getProductTime() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~ProductTime".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get Device Name
    public String getDeviceName() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~DeviceName".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String getPIN2Width() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~PIN2Width".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    //
    public String getShowState() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~ShowState".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    //
    public String getDeviceIP() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "IPAddress".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get TCP port from device
    public String getDevicePORT() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "UDPPort".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get Communication key from device
    public String getCommKey() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "COMKey".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get device Id from device
    public String getDeviceId() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "DeviceID".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String iSDHCP() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo, "DHCP".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String getDNS() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo, "DNS".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String isEnableProxyServer() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "EnableProxyServer".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String getProxyServerIP() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "ProxyServerIP".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String getProxyServerPort() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "ProxyServerPort".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // (wrong working i think)
    public String isDaylightSavingTime() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "DaylightSavingTime".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // 69 english
    public String getLanguage() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "Language".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    public String isLockPowerKey() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "LockPowerKey".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // Get voice on/off status
    public String isVoiceOn() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "VoiceOn".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);
            // SecurityUtils.printHexDump(byteArray);
            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // set Device IP Address
    public ZKCommandReply setIPAddress(String ipaddress) throws IOException {
        byte[] ipaddressbyte = ("IPAddress=" + ipaddress).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, ipaddressbyte);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // set Communication key
    public ZKCommandReply setCommKey(int key) throws IOException {
        byte[] COMKeybyte = ("COMKey=" + key).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, COMKeybyte);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // set on off device voice
    public ZKCommandReply setVoiceOnOff(OnOffenum state) throws IOException {
        byte[] voiceOn = ("VoiceOn=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, voiceOn);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // set on off device voice
    public ZKCommandReply setShowStateOnOff(OnOffenum state) throws IOException {
        byte[] ShowState = ("~ShowState=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, ShowState);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // get platform name
    public String getPlatform() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~Platform".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // lock power down key by device
    public ZKCommandReply setLockPowerKey(OnOffenum state) throws IOException {
        byte[] LockPowerKey = ("LockPowerKey=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, LockPowerKey);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // wrong working i think
    public ZKCommandReply setDaylightSavingTime(OnOffenum state) throws IOException {
        byte[] DaylightSavingTime = ("DaylightSavingTime=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, DaylightSavingTime);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Set cloud server proxy port
    public ZKCommandReply setProxyServerPort(int devport) throws IOException {
        byte[] ProxyServerPort = ("ProxyServerPort=" + devport).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, ProxyServerPort);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Set device cloud Proxy Server IP
    public ZKCommandReply setProxyServerIP(String devIP) throws IOException {
        byte[] ProxyServerIP = ("ProxyServerIP=" + devIP).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, ProxyServerIP);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // on, off proxy server cloud server
    public ZKCommandReply setEnableProxyServer(OnOffenum state) throws IOException {
        byte[] EnableProxyServer = ("EnableProxyServer=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, EnableProxyServer);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // set dns to device
    public ZKCommandReply setDNS(String DNS) throws IOException {
        byte[] DNSbyte = ("DNS=" + DNS).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, DNSbyte);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // on and off Ethernet DHCP
    public ZKCommandReply setDHCP(OnOffenum state) throws IOException {
        byte[] DHCPbyte = ("DHCP=" + state.getOnOffState()).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, DHCPbyte);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Set device id PC connection
    public ZKCommandReply setDeviceID(int DeviceID) throws IOException {
        byte[] DeviceIDbyte = ("DeviceID=" + DeviceID).getBytes();
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_WRQ, sessionId, replyNo, DeviceIDbyte);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Get Device Serial Number
    public String getSerialNumber() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~SerialNumber".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    // get Device MAC address
    public String getMAC() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo, "MAC".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {

            // Convert the array of integers to a byte array
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }

        return "";
    }

    // Device Fingerprint Version
    public String getFaceVersion() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "ZKFaceVersion".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {

            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }

        return "";
    }

    // get fingerprint version
    public int getFPVersion() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~ZKFPVersion".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteResponse = new byte[response.length - 8];
            for (int i = 0; i < byteResponse.length; i++) {
                byteResponse[i] = (byte) response[i + 8];
            }

            String responseString = new String(byteResponse, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                try {
                    return Integer.parseInt(responseParts[1].split("\0")[0]);
                } catch (NumberFormatException e) {

                }
            }
        }
        return 0;
    }

    // Device OEM name
    public String getOEMVendor() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "~OEMVendor".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteArray = new byte[response.length - 8];
            for (int i = 0; i < byteArray.length; i++) {
                byteArray[i] = (byte) (response[8 + i] & 0xFF);
            }

            String responseString = new String(byteArray, StandardCharsets.US_ASCII);

            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return responseParts[1].split("\0")[0];
            }
        }
        return "";
    }

    /**
     * Lit tous les profils sans récupérer les templates d'empreinte.
     * @return profils dans l'ordre du lecteur, ou liste vide si aucun utilisateur
     * @throws IOException si l'échange échoue ou si les données sont incomplètes
     * @throws ParseException si une opération de lecture sous-jacente ne peut être décodée
     * @see #getAllUserWithFingerprints()
     */
    public List<UserInfo> getAllUsers() throws IOException, ParseException {
        if (getDeviceStatus().get("userCount") == 0) {
            return Collections.emptyList();
        }
        sendPacket(ZKCommand.getPacket(CommandCodeEnum.CMD_USERTEMP_RRQ, sessionId, replyNo, null));
        replyNo++;
        int[] response = readResponse();
        if (response.length < 8) {
            throw new IOException("Malformed user transfer packet");
        }
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] | (response[1] << 8));
        if (replyCode != CommandReplyCodeEnum.CMD_PREPARE_DATA) {
            throw new IOException("User read failed: " + replyCode);
        }
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean first = true;
        while (true) {
            int[] packet = readResponse();
            if (packet.length < 8) {
                throw new IOException("Malformed user transfer packet");
            }
            int code = packet[0] | (packet[1] << 8);
            if (code == CommandReplyCodeEnum.CMD_ACK_OK.getCode()) {
                break; // Consume the final ACK before the next command uses the socket.
            }
            if (code != CommandReplyCodeEnum.CMD_DATA.getCode() || (first && packet.length < 12)) {
                throw new IOException("Unexpected user transfer packet: " + code);
            }
            // The first payload starts with a four-byte length, followed by user records.
            for (int i = first ? 12 : 8; i < packet.length; i++) {
                data.write(packet[i]);
            }
            first = false;
        }
        if (data.size() % UserRecordCodec.RECORD_SIZE != 0) {
            throw new IOException("Incomplete user records");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data.toByteArray());
        List<UserInfo> users = new ArrayList<UserInfo>();
        while (buffer.hasRemaining()) {
            users.add(userRecordCodec.decode(buffer));
        }
        return users;
    }

    // Get work code
    public boolean getWorkCode() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_OPTIONS_RRQ, sessionId, replyNo,
                "WorkCode".getBytes());
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteResponse = new byte[response.length - 8];
            for (int i = 0; i < byteResponse.length; i++) {
                byteResponse[i] = (byte) response[i + 8];
            }

            String responseString = new String(byteResponse, StandardCharsets.US_ASCII);
            String[] responseParts = responseString.split("=", 2);

            if (responseParts.length == 2) {
                return "1".equals(responseParts[1].split("\0")[0]);
            }
        }

        return false;
    }

    // Get devices capacity
    public Map<String, Integer> getDeviceStatus() throws IOException {

        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_GET_FREE_SIZES, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            byte[] byteResponse = new byte[response.length - 8];
            for (int i = 0; i < byteResponse.length; i++) {
                byteResponse[i] = (byte) response[i + 8];
            }

            ByteBuffer buffer = ByteBuffer.wrap(byteResponse);

            if (buffer.remaining() >= 92) {
                // Process the device status data
                Map<String, Integer> statusMap = new HashMap<>();

                statusMap.put("adminCount", Integer.reverseBytes(buffer.getInt(48)));
                statusMap.put("userCount", Integer.reverseBytes(buffer.getInt(16)));
                statusMap.put("fpCount", Integer.reverseBytes(buffer.getInt(24)));
                statusMap.put("pwdCount", Integer.reverseBytes(buffer.getInt(52)));
                statusMap.put("oplogCount", Integer.reverseBytes(buffer.getInt(40)));
                statusMap.put("attlogCount", Integer.reverseBytes(buffer.getInt(32)));
                statusMap.put("fpCapacity", Integer.reverseBytes(buffer.getInt(56)));
                statusMap.put("userCapacity", Integer.reverseBytes(buffer.getInt(60)));
                statusMap.put("attlogCapacity", Integer.reverseBytes(buffer.getInt(64)));
                statusMap.put("remainingFp", Integer.reverseBytes(buffer.getInt(68)));
                statusMap.put("remainingUser", Integer.reverseBytes(buffer.getInt(72)));
                statusMap.put("remainingAttlog", Integer.reverseBytes(buffer.getInt(76)));
                statusMap.put("faceCount", Integer.reverseBytes(buffer.getInt(80)));
                statusMap.put("faceCapacity", Integer.reverseBytes(buffer.getInt(88)));

                return statusMap;

            }

        }
        return Collections.emptyMap();
    }

    // Ensure the machine to be at the authentication (Not verify)
    public ZKCommandReply setStartVerify() throws IOException {
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_STARTVERIFY, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);


        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Get Devices Date And Time
    public Date getDeviceTime() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_GET_TIME, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        GetTimeReply gettime = new GetTimeReply(replyCode, sessionId, replyId, payloads);
        return gettime.getDeviceDate();
    }

    public int getState() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_STATE_RRQ, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        String payloadsStr = HexUtils.bytesToHex(payloads);
        System.out.println(payloadsStr);
        return 0;
    }

    //
    public ZKCommandReply cancelEnrollment() throws IOException {
        // Create and send the packet
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_CANCELCAPTURE, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);

        // Increment reply number
        replyNo++;

        // Read response from the device
        int[] response = readResponse();

        // Decode response
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);

        // TODO: Add specific logic for CMD_ACK_OK response
        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // Log success or perform additional actions if needed
        }

        // Return ZKCommandReply
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    //

    // Set current system time to device time
    public ZKCommandReply syncTime() throws IOException {
        // long encodedTime = HexUtils.encodeTime(new Date());

        long encodedTime = HexUtils.convertToSeconds();

        int[] timeBytes = HexUtils.convertLongToLittleEndian(encodedTime);
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_SET_TIME, sessionId, replyNo, timeBytes);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();

        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);

        try {
            System.out.println(HexUtils.extractDate(encodedTime));

        } catch (ParseException e) {
            throw new RuntimeException(e);
        }

        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    /**
     * Supprime un utilisateur par son UID interne sur ce lecteur.
     * Cette opération ne crée aucune sauvegarde ; exporter le profil/templates avant.
     * @param delUId UID interne, et non l'identifiant métier {@code userid}
     * @return réponse positive, ou null si le lecteur refuse la suppression
     * @throws IOException si l'échange UDP échoue
     * @see #getUserWithFingerprints(String)
     */
    public ZKCommandReply delUser(int delUId) throws IOException {
        int[] delUIdArray = new int[] { delUId & 0xFF, (delUId >> 8) & 0xFF };

        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_DELETE_USER, sessionId, replyNo, delUIdArray);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        // System.out.println("Sending CMD_DELETE_USER packet. Payload: " +
        // Arrays.toString(buf));
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
        } else {
            System.out.println("User Not found...!");
            return null;
        }
    }

    /**
     * Adds a new user or updates the user having the same external user ID.
     * The device UID is allocated automatically for new users.
     * @param user profil à écrire ; son userid détermine création ou mise à jour
     * @return UID attribué, statut et réponse du lecteur ; aucun template n'est capturé
     * @throws IOException si un échange UDP échoue
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si le profil ne respecte pas le format du lecteur
     * @throws IllegalStateException si le listener est actif ou si le userid est ambigu
     */
    public UserWriteResult addUser(UserInfo user) throws IOException, ParseException {
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("User writes cannot run while realtime logs are active");
            }
            return addUserLocked(user);
        }
    }

    /**
     * Reads all ten finger slots. Device database errors are retained in the
     * snapshot.
     * @param userId identifiant métier externe, non vide ; ce n'est pas l'UID interne
     * @return profil et templates récupérés, avec les erreurs des indices non lus
     * @throws IOException si le transport ou une commande autre qu'une erreur de base échoue
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si l'identifiant est invalide ou introuvable
     * @throws IllegalStateException si le listener est actif ou si plusieurs profils ont ce userid
     * @see UserBiometricData#isComplete()
     */
    public UserBiometricData getUserWithFingerprints(String userId) throws IOException, ParseException {
        return getUserWithFingerprints(userId, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    /**
     * Reads selected finger slots; transport/authentication errors abort the
     * snapshot.
     * <pre>{@code
     * UserBiometricData data = terminal.getUserWithFingerprints("EMP0001", 1, 6);
     * }</pre>
     * @param userId identifiant métier externe
     * @param fingerIndices indices distincts de 0 à 9 ; un tableau vide lit seulement le profil
     * @return profil et templates récupérés ; les codes 4991/4993 sont conservés par indice
     * @throws IOException si le transport ou une autre réponse d'erreur empêche la lecture
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si les paramètres sont invalides ou le profil introuvable
     * @throws IllegalStateException si le listener est actif ou le userid est ambigu
     */
    public UserBiometricData getUserWithFingerprints(String userId, int... fingerIndices)
            throws IOException, ParseException {
        if (userId == null || userId.isEmpty() || fingerIndices == null) {
            throw new IllegalArgumentException("userId and fingerIndices are required");
        }
        Set<Integer> indices = new HashSet<Integer>();
        for (int index : fingerIndices) {
            if (index < 0 || index > 9 || !indices.add(index)) {
                throw new IllegalArgumentException("fingerIndices must be unique and between 0 and 9");
            }
        }
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("Stop realtime logs before reading user biometrics");
            }
            UserInfo user = null;
            for (UserInfo candidate : getAllUsers()) {
                if (userId.equals(candidate.getUserid())) {
                    if (user != null) {
                        throw new IllegalStateException("Multiple users have userid " + userId);
                    }
                    user = candidate;
                }
            }
            if (user == null) {
                throw new IllegalArgumentException("User not found: " + userId);
            }
            return readUserFingerprintsLocked(user, fingerIndices);
        }
    }

    /**
     * Reads every user profile and attempts all ten finger slots for each user.
     * Fetches the profile list once; database errors are retained per user/finger.
     * Transport and other device errors abort the operation. Stop realtime first.
     * @return profils enrichis, ou liste vide ; jusqu'à dix lectures d'empreinte par profil
     * @throws IOException si un échange échoue ; aucune liste partielle n'est retournée
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalStateException si le listener temps réel est actif
     */
    public List<UserBiometricData> getAllUserWithFingerprints() throws IOException, ParseException {
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("Stop realtime logs before reading user biometrics");
            }
            List<UserBiometricData> result = new ArrayList<UserBiometricData>();
            int[] fingerIndices = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
            for (UserInfo user : getAllUsers()) {
                result.add(readUserFingerprintsLocked(user, fingerIndices));
            }
            return result;
        }
    }

    private UserBiometricData readUserFingerprintsLocked(UserInfo user, int[] fingerIndices) throws IOException {
        List<FingerprintTemplate> fingerprints = new ArrayList<FingerprintTemplate>();
        Map<Integer, String> errors = new java.util.LinkedHashMap<Integer, String>();
        for (int index : fingerIndices) {
            try {
                fingerprints.add(new FingerprintTemplate(index, getUserFingerprint(user.getUid(), index)));
            } catch (FingerprintReadException e) {
                if (e.getReplyCode() != 4991 && e.getReplyCode() != 4993) {
                    throw e;
                }
                // 4993 does not prove absence. Preserve it instead of silently losing a finger.
                errors.put(index, e.getMessage());
            }
        }
        return new UserBiometricData(user, fingerprints, errors);
    }

    /**
     * Adds/updates the profile by external userid and uploads supplied templates
     * with command 110.
     * The source UID is ignored. Unspecified fingers are not deleted. This is not
     * transactional.
     * <p>Le userid détermine l'utilisateur destination. L'UID de la sauvegarde est ignoré.
     * Les erreurs de lecture du snapshot ne sont pas envoyées. Un profil peut rester
     * enregistré après un échec d'import ; le succès ne garantit pas la reconnaissance physique.
     * @param data profil et templates déjà capturés, non null ; liste vide autorisée
     * @return résultat du profil, de l'import et du nettoyage du lecteur
     * @throws IOException si un échange hors de la phase d'import gérée par le résultat échoue
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si les données ne respectent pas le format du lecteur
     * @throws IllegalStateException si le listener est actif ou le userid est ambigu
     * @see #addUserWithFingerprint(UserInfo, int, Duration)
     */
    public UserBiometricWriteResult addUserWithFingerprints(UserBiometricData data)
            throws IOException, ParseException {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("Stop realtime logs before writing user biometrics");
            }
            UserInfo user = data.getUser();
            // Validate the buffered representation before any device write.
            user.setUid(1);
            userBiometricCodec.encode(user, data.getFingerprints());
            UserWriteResult profile = addUserLocked(user);
            if (!profile.isSuccess()) {
                return new UserBiometricWriteResult(profile, false, profile.getMessage());
            }
            if (data.getFingerprints().isEmpty()) {
                return new UserBiometricWriteResult(profile, true, "Profile saved; no fingerprints supplied");
            }
            user.setUid(profile.getAssignedUid());
            byte[] buffer = userBiometricCodec.encode(user, data.getFingerprints());
            ZKCommandReply disableReply = disableDevice();
            if (!isSuccess(disableReply)) {
                return new UserBiometricWriteResult(profile, false,
                        "Profile saved; device refused template write mode");
            }
            UserBiometricWriteResult result;
            List<String> cleanupErrors = new ArrayList<String>();
            try {
                uploadFingerprintBuffer(buffer);
                sendBiometricCommand(CommandCodeEnum._CMD_SAVE_USERTEMPS,
                        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(12).putShort((short) 0).putShort((short) 8).array());
                ZKCommandReply refresh = RefreshData();
                if (!isSuccess(refresh)) {
                    result = new UserBiometricWriteResult(profile, false,
                            "Template upload acknowledged but refresh failed; verify device contents");
                } else {
                    result = new UserBiometricWriteResult(profile, true,
                            "Profile and supplied templates acknowledged by device; verify recognition on reader");
                }
            } catch (IOException e) {
                result = new UserBiometricWriteResult(profile, false,
                        "Profile saved; template upload failed or is incomplete: " + e.getMessage());
            } finally {
                try {
                    sendBiometricCommand(CommandCodeEnum.CMD_FREE_DATA, null);
                } catch (IOException e) {
                    cleanupErrors.add("Buffer cleanup failed: " + e.getMessage());
                }
                try {
                    if (!isSuccess(enableDevice())) {
                        cleanupErrors.add("Device re-enable refused");
                    }
                } catch (IOException e) {
                    cleanupErrors.add("Device re-enable failed: " + e.getMessage());
                }
            }
            if (!cleanupErrors.isEmpty()) {
                return new UserBiometricWriteResult(profile, result.isFingerprintsWritten(),
                        result.getMessage() + "; " + String.join("; ", cleanupErrors), false);
            }
            return result;
        }
    }

    private void uploadFingerprintBuffer(byte[] buffer) throws IOException {
        sendBiometricCommand(CommandCodeEnum.CMD_FREE_DATA, null);
        sendBiometricCommand(CommandCodeEnum.CMD_PREPARE_DATA,
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(buffer.length).array());
        for (int offset = 0; offset < buffer.length; offset += 1024) {
            sendBiometricCommand(CommandCodeEnum.CMD_DATA,
                    Arrays.copyOfRange(buffer, offset, Math.min(buffer.length, offset + 1024)));
        }
    }

    private void sendBiometricCommand(CommandCodeEnum command, byte[] payload) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        try {
            sendPacket(ZKCommand.getPacketByte(command, sessionId, replyNo, payload));
            int expectedReply = replyNo++;
            int[] response = readFingerprintPacket(System.nanoTime() + Duration.ofSeconds(10).toNanos());
            int code = response[0] | (response[1] << 8);
            int incomingReply = response[6] | (response[7] << 8);
            if (code != CommandReplyCodeEnum.CMD_ACK_OK.getCode() || incomingReply != (expectedReply & 0xFFFF)) {
                throw new IOException("Command " + command + " failed: code=" + code + ", reply=" + incomingReply);
            }
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    /**
     * Adds or updates a user and waits up to 60 seconds for fingerprint enrollment.
     * @param user profil de l'utilisateur
     * @param fingerIndex index de 0 à 9 dont le template existant sera remplacé
     * @return résultat du profil puis de la capture physique
     * @throws IOException si un échange UDP échoue
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si le profil ou l'indice est invalide
     * @throws IllegalStateException si le listener est actif ou le userid est ambigu
     */
    public UserEnrollmentResult addUserWithFingerprint(UserInfo user, int fingerIndex)
            throws IOException, ParseException {
        return addUserWithFingerprint(user, fingerIndex, DEFAULT_ENROLLMENT_TIMEOUT);
    }

    /**
     * Écrit le profil puis attend une capture physique sur le lecteur.
     * Le profil reste enregistré si la capture échoue. Les octets du template ne sont
     * pas inclus dans le résultat ; les lire ensuite avec {@link #getUserWithFingerprints(String, int...)}.
     * @param user profil à créer ou mettre à jour
     * @param fingerIndex index de 0 à 9 ; le template existant à cet index est remplacé
     * @param timeout délai positif d'attente des événements de capture, hors préparation des commandes
     * @return résultat du profil et capture, timeout ou annulation
     * @throws IOException si un échange UDP échoue
     * @throws ParseException si une lecture sous-jacente ne peut être décodée
     * @throws IllegalArgumentException si le profil, l'indice ou le délai est invalide
     * @throws IllegalStateException si le listener est actif ou le userid est ambigu
     */
    public UserEnrollmentResult addUserWithFingerprint(UserInfo user, int fingerIndex, Duration timeout)
            throws IOException, ParseException {
        if (fingerIndex < 0 || fingerIndex > 9) {
            throw new IllegalArgumentException("fingerIndex must be between 0 and 9");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("Fingerprint enrollment cannot run while realtime logs are active");
            }
            UserWriteResult profile = addUserLocked(user);
            if (!profile.isSuccess()) {
                return new UserEnrollmentResult(profile, null);
            }
            FingerprintEnrollmentResult fingerprint = enrollFingerprintLocked(
                    profile.getAssignedUid(), user.getUserid(), fingerIndex, timeout);
            return new UserEnrollmentResult(profile, fingerprint);
        }
    }

    private UserWriteResult addUserLocked(UserInfo requestedUser) throws IOException, ParseException {
        userRecordCodec.validate(requestedUser, false);
        List<UserInfo> users = getAllUsers();
        UserInfo existingUser = null;
        Set<Integer> usedUids = new HashSet<Integer>();
        for (UserInfo existing : users) {
            usedUids.add(existing.getUid());
            if (requestedUser.getUserid().equals(existing.getUserid())) {
                if (existingUser != null) {
                    throw new IllegalStateException("Multiple users have userid " + requestedUser.getUserid());
                }
                existingUser = existing;
            }
        }
        boolean created = existingUser == null;
        int assignedUid;
        if (created) {
            Map<String, Integer> status = getDeviceStatus();
            Integer remaining = status.get("remainingUser");
            if (remaining != null && remaining <= 0) {
                return new UserWriteResult(0, true, UserOperationStatus.PROFILE_FAILED, null,
                        "The device has no remaining user capacity");
            }
            assignedUid = firstFreeUid(usedUids);
        } else {
            assignedUid = existingUser.getUid();
        }

        UserInfo normalizedUser = new UserInfo(requestedUser);
        normalizedUser.setUid(assignedUid);
        userRecordCodec.validate(normalizedUser, true);

        ZKCommandReply disableReply = disableDevice();
        if (!isSuccess(disableReply)) {
            return new UserWriteResult(assignedUid, created, UserOperationStatus.PROFILE_FAILED,
                    disableReply, "The device refused to enter write mode");
        }

        try {
            ZKCommandReply writeReply = writeUserRecord(normalizedUser);
            if (!isSuccess(writeReply)) {
                return new UserWriteResult(assignedUid, created, UserOperationStatus.PROFILE_FAILED,
                        writeReply, "The device rejected the user profile");
            }
            ZKCommandReply refreshReply = RefreshData();
            if (!isSuccess(refreshReply)) {
                return new UserWriteResult(assignedUid, created, UserOperationStatus.PROFILE_FAILED,
                        refreshReply, "The user was written but the device did not refresh its data");
            }
            return new UserWriteResult(assignedUid, created, UserOperationStatus.SUCCESS,
                    writeReply, created ? "User created" : "User updated");
        } finally {
            enableDevice();
        }
    }

    static int firstFreeUid(Set<Integer> usedUids) {
        for (int uid = 1; uid <= 65535; uid++) {
            if (!usedUids.contains(uid)) {
                return uid;
            }
        }
        throw new IllegalStateException("No free UID is available on the device");
    }

    private static boolean isSuccess(ZKCommandReply reply) {
        return reply != null && reply.getCode() == CommandReplyCodeEnum.CMD_ACK_OK;
    }

    private ZKCommandReply writeUserRecord(UserInfo user) throws IOException {
        byte[] record = userRecordCodec.encode(user);
        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_USER_WRQ, sessionId, replyNo, record);
        sendPacket(toSend);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = Arrays.copyOfRange(response, 8, response.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    /**
     * @deprecated Use {@link #addUser(UserInfo)} to obtain automatic UID
     *             allocation,
     *             validation, refresh and a structured result.
     */
    @Deprecated
    public ZKCommandReply modifyUserInfo(UserInfo newUser) throws IOException {
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("User writes cannot run while realtime logs are active");
            }
            return writeUserRecord(newUser);
        }
    }

    // wrong work
    public ZKCommandReply setSms(int tagp, int IDp, int validMinutesp, long startTimep, String contentp)
            throws IOException {
        SmsInfo newSms = new SmsInfo(tagp, IDp, validMinutesp, 0, startTimep, contentp);

        ByteBuffer commandBuffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);

        commandBuffer.put((byte) newSms.getTag());
        commandBuffer.putShort((short) newSms.getId());
        commandBuffer.putShort((short) newSms.getValidMinutes());
        commandBuffer.putShort((short) newSms.getReserved());

        if (commandBuffer.capacity() > 0) {
            System.out.println(commandBuffer.capacity());
            return null;
        }
        // Convert startTimep to LocalDateTime
        LocalDateTime localDateTime = Instant.ofEpochMilli(startTimep).atZone(ZoneId.systemDefault()).toLocalDateTime();

        // Pack the LocalDateTime into the buffer
        commandBuffer
                .putInt((int) localDateTime.toEpochSecond(ZoneId.systemDefault().getRules().getOffset(localDateTime)));

        byte[] contentBytes = newSms.getContent().getBytes(StandardCharsets.UTF_8);
        commandBuffer.put(contentBytes, 0, Math.min(contentBytes.length, 60));

        // Print the hex dump (you can remove this in your actual code)
        SecurityUtils.printHexDump(commandBuffer.array());

        int[] toSend = ZKCommand.getPacketByte(CommandCodeEnum.CMD_SMS_WRQ, sessionId, replyNo, commandBuffer.array());
        byte[] buf = new byte[toSend.length];

        for (int i = 0; i < toSend.length; i++) {
            buf[i] = (byte) toSend[i];
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        return new ZKCommandReply(replyCode, sessionId, replyNo, null);
    }

    // Working wrong(content work well)
    public SmsInfo getSms(int smsUId) throws IOException, ParseException {
        int[] smsIdArray = new int[] { smsUId & 0xFF, (smsUId >> 8) & 0xFF };

        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_SMS_RRQ, sessionId, replyNo, smsIdArray);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        if ((response[0] + (response[1] * 0x100)) == 4993) {
            return null;
        }
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);

        byte[] byteResponse = SecurityUtils.convertIntArrayToByteArray(response);
        SecurityUtils.printHexDump(byteResponse);

        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        // System.out.println(response.length);
        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            int tag = response[8];
            int id = (response[9] & 0xFF) + ((response[10] & 0xFF) << 8);
            int reserved = ((response[13] & 0xFF) << 8) | (response[12] & 0xFF);
            long startTime = (response[15] & 0xFFL) | ((response[14] & 0xFFL) << 8) | ((response[13] & 0xFFL) << 16)
                    | ((response[12] & 0xFFL) << 24);
            int validMinutes = Short.reverseBytes((short) ((response[11] << 8) | (response[10] & 0xFF))) & 0xFFFF; // on
                                                                                                                   // issue
                                                                                                                   // on
                                                                                                                   // 256

            System.out.println("Raw response[11]: " + response[12]);
            System.out.println("Raw response[10]: " + response[13]);
            System.out.println("Raw response[10]: " + response[14]);
            System.out.println("Raw response[10]: " + response[15]);
            System.out.println("Raw response[10]: " + response[16]);
            System.out.println("Raw response[10]: " + response[17]);
            System.out.println("Raw response[10]: " + response[18]);

            long encDate = ((response[15] & 0xFFL) << 24) | ((response[14] & 0xFFL) << 16)
                    | ((response[13] & 0xFFL) << 8) | (response[12] & 0xFFL);

            System.out.println("Decoded Date: " + encDate);

            Date startDate = HexUtils.extractDate(encDate);
            // System.out.println("------ daete " + startDate);
            int contentOffset = 19;
            byte[] contentBytes = new byte[321];
            for (int i = 0; i < 321; i++) {
                contentBytes[i] = (byte) (response[i + contentOffset] & 0xFF);
            }

            String content = new String(contentBytes, StandardCharsets.UTF_8).trim();
            // SecurityUtils.printUnsignedBytes(contentBytes);

            // Print decoded values
            // System.out.println("Tag: " + tag);
            // System.out.println("ID: " + id);
            // System.out.println("Valid Minutes: " + validMinutes);
            // System.out.println("Reserved: " + reserved);
            // System.out.println("Start Time: " + startTime + " " + startDate);

            return new SmsInfo(tag, id, validMinutes, reserved, startTime, content);
        }
        return null;
    }

    // Detect SMS
    public ZKCommandReply delSMS(int smsId) throws IOException {
        int[] delsmsIdArray = new int[] { smsId & 0xFF, (smsId >> 8) & 0xFF };

        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_DELETE_SMS, sessionId, replyNo, delsmsIdArray);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);

        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
        }
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);

    }

    private FingerprintEnrollmentResult enrollFingerprintLocked(int uid, String userId, int fingerIndex,
            Duration timeout) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        int previousEventMask = registeredEventMask;
        List<Integer> scores = new ArrayList<Integer>();
        boolean enrollmentStarted = false;
        try {
            int enrollmentEvents = EventCode.EF_FINGER.getCode()
                    | EventCode.EF_FPFTR.getCode()
                    | EventCode.EF_ENROLLFINGER.getCode();
            ZKCommandReply registration = registerRealtimeEvents(enrollmentEvents);
            if (!isSuccess(registration)) {
                return fingerprintFailure(UserOperationStatus.ENROLLMENT_FAILED, fingerIndex, scores,
                        "The device refused enrollment events");
            }

            // A missing template may be reported as an error. Replacement can still
            // continue.
            cancelEnrollment();
            deleteUserTemplate(uid, fingerIndex);

            ZKCommandReply startReply = enrollFinger(uid, fingerIndex, userId);
            if (!isSuccess(startReply)) {
                return fingerprintFailure(UserOperationStatus.ENROLLMENT_FAILED, fingerIndex, scores,
                        "The device refused to start fingerprint enrollment");
            }
            enrollmentStarted = true;
            ZKCommandReply verifyReply = setStartVerify();
            if (!isSuccess(verifyReply)) {
                return fingerprintFailure(UserOperationStatus.ENROLLMENT_FAILED, fingerIndex, scores,
                        "The device refused fingerprint capture mode");
            }

            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    return fingerprintFailure(UserOperationStatus.CANCELLED, fingerIndex, scores,
                            "Fingerprint enrollment was cancelled");
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return fingerprintFailure(UserOperationStatus.TIMEOUT, fingerIndex, scores,
                            "Fingerprint enrollment timed out");
                }
                long remainingMillis = Math.max(1L, remainingNanos / 1_000_000L);
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));

                int[] response;
                try {
                    response = readResponse();
                } catch (SocketTimeoutException e) {
                    return fingerprintFailure(UserOperationStatus.TIMEOUT, fingerIndex, scores,
                            "Fingerprint enrollment timed out");
                }
                if (!isRealtimeEvent(response)) {
                    continue;
                }

                int eventCode = response[4] + (response[5] << 8);
                acknowledgeRealtimeEvent(response);
                if (eventCode == EventCode.EF_FPFTR.getCode() && response.length > 8) {
                    scores.add(response[8]);
                } else if (eventCode == EventCode.EF_ENROLLFINGER.getCode()) {
                    return parseEnrollmentResult(response, userId, fingerIndex, scores);
                }
            }
        } finally {
            try {
                socket.setSoTimeout(previousTimeout);
                if (enrollmentStarted) {
                    cancelEnrollment();
                }
            } catch (IOException | RuntimeException ignored) {
                // Preserve the actual enrollment result; the device timeout is restored below.
            }
            if (enrollmentStarted) {
                try {
                    RefreshData();
                } catch (IOException | ParseException | RuntimeException ignored) {
                    // The profile remains valid and the enrollment result stays available.
                }
            }
            try {
                if (registeredEventMask != previousEventMask) {
                    registerRealtimeEvents(previousEventMask);
                }
            } catch (IOException | RuntimeException ignored) {
                // Best effort restoration of the caller's previous event registration.
            } finally {
                socket.setSoTimeout(previousTimeout);
            }
        }
    }

    private static FingerprintEnrollmentResult fingerprintFailure(UserOperationStatus status, int fingerIndex,
            List<Integer> scores, String message) {
        return new FingerprintEnrollmentResult(status, null, 0, null, fingerIndex, scores, null, message);
    }

    FingerprintEnrollmentResult parseEnrollmentResult(int[] response, String expectedUserId,
            int expectedFingerIndex, List<Integer> scores) {
        if (response.length < 10) {
            return new FingerprintEnrollmentResult(UserOperationStatus.ENROLLMENT_FAILED, null, 0, null,
                    expectedFingerIndex, scores, response, "Malformed enrollment result");
        }
        int resultCode = response[8] + (response[9] << 8);
        if (resultCode != 0) {
            return new FingerprintEnrollmentResult(UserOperationStatus.ENROLLMENT_FAILED, resultCode, 0, null,
                    expectedFingerIndex, scores, response, "The device rejected the fingerprint samples");
        }
        if (response.length < 22) {
            return new FingerprintEnrollmentResult(UserOperationStatus.ENROLLMENT_FAILED, resultCode, 0, null,
                    expectedFingerIndex, scores, response, "Incomplete successful enrollment result");
        }
        int templateSize = response[10] + (response[11] << 8);
        int end = 12;
        while (end < 21 && response[end] != 0) {
            end++;
        }
        byte[] userIdBytes = new byte[end - 12];
        for (int i = 0; i < userIdBytes.length; i++) {
            userIdBytes[i] = (byte) response[12 + i];
        }
        String enrolledUserId = new String(userIdBytes, StandardCharsets.US_ASCII);
        int enrolledFingerIndex = response[21];
        if (!expectedUserId.equals(enrolledUserId) || expectedFingerIndex != enrolledFingerIndex) {
            return new FingerprintEnrollmentResult(UserOperationStatus.ENROLLMENT_FAILED, resultCode, templateSize,
                    enrolledUserId, enrolledFingerIndex, scores, response,
                    "Enrollment result does not match the requested user and finger");
        }
        return new FingerprintEnrollmentResult(UserOperationStatus.SUCCESS, resultCode, templateSize,
                enrolledUserId, enrolledFingerIndex, scores, response, "Fingerprint enrolled");
    }

    static boolean isRealtimeEvent(int[] response) {
        if (response == null || response.length < 8) {
            return false;
        }
        int command = response[0] + (response[1] << 8);
        return command == CommandCodeEnum.CMD_REG_EVENT.getCode()
                || command == CommandReplyCodeEnum.CMD_ACK_RETRY.getCode();
    }

    private void acknowledgeRealtimeEvent(int[] response) throws IOException {
        int incomingReplyId = response[6] + (response[7] << 8);
        int[] acknowledgement = ZKCommand.getPacket(
                CommandReplyCodeEnum.CMD_ACK_OK.getCode(), sessionId, incomingReplyId, null);
        sendPacket(acknowledgement);
    }

    /**
     * Reads a fingerprint using experimental command 88 (pyzk get_user_template).
     * Returns template bytes after removing the device trailer and optional
     * padding.
     * A rejected command raises IOException; it does not prove that the finger is
     * absent.
     * @param uid UID interne du profil sur ce lecteur, de 1 à 65535
     * @param fingerIndex index du doigt de 0 à 9
     * @return copie des octets du template, sans trailer/padding de transfert
     * @throws IOException si le transfert échoue, dépasse dix secondes ou est incomplet
     * @throws FingerprintReadException si le lecteur retourne une réponse d'erreur
     * @throws IllegalArgumentException si un indice est hors plage
     * @throws IllegalStateException si le listener est actif ou le socket n'est pas ouvert
     */
    public byte[] getUserFingerprint(int uid, int fingerIndex) throws IOException {
        if (uid < 1 || uid > 65535 || fingerIndex < 0 || fingerIndex > 9) {
            throw new IllegalArgumentException("uid must be 1..65535 and fingerIndex must be 0..9");
        }
        synchronized (userOperationLock) {
            if (realtimeRunning) {
                throw new IllegalStateException("Fingerprint reads cannot run while realtime logs are active");
            }
            if (socket == null || socket.isClosed()) {
                throw new IllegalStateException("Connect to the device before reading a fingerprint");
            }
            int previousTimeout = socket.getSoTimeout();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            try {
                sendPacket(ZKCommand.getPacket(CommandCodeEnum._CMD_GET_USERTEMP, sessionId, replyNo,
                        new int[] { uid & 0xFF, (uid >> 8) & 0xFF, fingerIndex }));
                replyNo++;
                int[] response = readFingerprintPacket(deadline);
                int code = response[0] | (response[1] << 8);
                // An empty ACK may precede the transfer, or remain from a previous read.
                // It confirms a command; it contains no fingerprint bytes.
                while (code == CommandReplyCodeEnum.CMD_ACK_OK.getCode() && response.length == 8) {
                    try {
                        response = readFingerprintPacket(deadline);
                    } catch (SocketTimeoutException e) {
                        throw new SocketTimeoutException(
                                "CMD_ACK_OK received but no fingerprint data arrived within 10 seconds");
                    }
                    code = response[0] | (response[1] << 8);
                }
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                if (code == CommandReplyCodeEnum.CMD_DATA.getCode()) {
                    appendFingerprintPayload(data, response);
                } else if (code == CommandReplyCodeEnum.CMD_PREPARE_DATA.getCode()) {
                    if (response.length < 12) {
                        throw new IOException("Missing fingerprint transfer size");
                    }
                    long size = (response[8] | (response[9] << 8) | (response[10] << 16)
                            | ((long) response[11] << 24));
                    if (size < 1 || size > 1024 * 1024) {
                        throw new IOException("Invalid fingerprint transfer size: " + size);
                    }
                    while (true) {
                        response = readFingerprintPacket(deadline);
                        code = response[0] | (response[1] << 8);
                        if (code == CommandReplyCodeEnum.CMD_ACK_OK.getCode()) {
                            if (data.size() != size) {
                                throw new IOException("Incomplete fingerprint transfer: " + data.size() + "/" + size);
                            }
                            break;
                        }
                        if (code != CommandReplyCodeEnum.CMD_DATA.getCode()) {
                            throw new IOException("Unexpected fingerprint packet: " + code);
                        }
                        if ((long) data.size() + response.length - 8 > size) {
                            throw new IOException("Fingerprint transfer exceeds announced size");
                        }
                        appendFingerprintPayload(data, response);
                    }
                } else if (code == CommandReplyCodeEnum.CMD_ACK_OK.getCode()) {
                    throw new IOException("CMD_ACK_OK received with " + (response.length - 8)
                            + " payload bytes; this fingerprint response format is not supported yet");
                } else {
                    ErrorCode error = ErrorCode.getByCode(code);
                    String description = error == null ? "Unknown device response" : error.getErrorMessage();
                    throw new FingerprintReadException(code,
                            "Fingerprint read failed (code " + code + "): " + description
                                    + " [uid=" + uid + ", fingerIndex=" + fingerIndex + "]");
                }
                byte[] raw = data.toByteArray();
                if (raw.length <= 1) {
                    throw new IOException("Empty fingerprint template");
                }
                // Matches pyzk: one trailer byte, optionally preceded by six zero bytes.
                int length = raw.length - 1;
                if (length >= 6) {
                    boolean padding = true;
                    for (int i = length - 6; i < length; i++) {
                        padding &= raw[i] == 0;
                    }
                    if (padding) {
                        length -= 6;
                    }
                }
                if (length == 0) {
                    throw new IOException("Empty fingerprint template after removing padding");
                }
                return Arrays.copyOf(raw, length);
            } finally {
                socket.setSoTimeout(previousTimeout);
            }
        }
    }

    private int[] readFingerprintPacket(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new SocketTimeoutException("Fingerprint read timed out");
        }
        socket.setSoTimeout((int) Math.max(1, remaining / 1_000_000L));
        int[] response = readResponse();
        if (response.length < 8) {
            throw new IOException("Malformed fingerprint packet");
        }
        return response;
    }

    private static void appendFingerprintPayload(ByteArrayOutputStream data, int[] response) {
        for (int i = 8; i < response.length; i++) {
            data.write(response[i]);
        }
    }

    private ZKCommandReply deleteUserTemplate(int uid, int fingerIndex) throws IOException {
        int[] data = new int[] { uid & 0xFF, (uid >> 8) & 0xFF, fingerIndex };
        int[] packet = ZKCommand.getPacket(CommandCodeEnum.CMD_DELETE_USERTEMP, sessionId, replyNo, data);
        sendPacket(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum code = CommandReplyCodeEnum.decode(response[0] + (response[1] << 8));
        int responseReplyId = response[6] + (response[7] << 8);
        return new ZKCommandReply(code, sessionId, responseReplyId,
                Arrays.copyOfRange(response, 8, response.length));
    }

    /**
     * Envoie la commande de démarrage d'une capture, sans attendre son résultat final.
     * Préférer {@link #addUserWithFingerprint(UserInfo, int, Duration)} pour gérer le cycle complet.
     * @param uid UID interne valide, vérifié mais non inclus dans le payload de cette commande
     * @param tempId index du doigt de 0 à 9
     * @param userId userid ASCII du profil à capturer, de 1 à 24 octets
     * @return accusé de réception de la commande, pas le résultat de capture
     * @throws IOException si l'échange UDP échoue
     * @throws IllegalArgumentException si l'UID, le doigt ou le userid est invalide
     */
    public ZKCommandReply enrollFinger(int uid, int tempId, String userId) throws IOException {
        if (uid < 1 || uid > 65535) {
            throw new IllegalArgumentException("uid must be between 1 and 65535");
        }
        if (tempId < 0 || tempId > 9) {
            throw new IllegalArgumentException("tempId must be between 0 and 9");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        for (int i = 0; i < userId.length(); i++) {
            if (userId.charAt(i) > 0x7F) {
                throw new IllegalArgumentException("userId must contain ASCII characters only");
            }
        }
        byte[] userIdBytes = userId.getBytes(StandardCharsets.US_ASCII);
        if (userIdBytes.length == 0 || userIdBytes.length > 24) {
            throw new IllegalArgumentException("userId must contain between 1 and 24 ASCII bytes");
        }
        byte[] enrollData = new byte[26];
        System.arraycopy(userIdBytes, 0, enrollData, 0, userIdBytes.length);
        enrollData[24] = (byte) tempId;
        enrollData[25] = 0x01;

        int[] packet = ZKCommand.getPacketByte(CommandCodeEnum.CMD_STARTENROLL, sessionId, replyNo, enrollData);
        sendPacket(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] << 8));
        int responseReplyId = response[6] + (response[7] << 8);
        return new ZKCommandReply(replyCode, sessionId, responseReplyId,
                Arrays.copyOfRange(response, 8, response.length));
    }

    private void sendPacket(int[] packetData) throws IOException {
        byte[] buf = new byte[packetData.length];

        for (int i = 0; i < packetData.length; i++) {
            buf[i] = (byte) packetData[i];
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
    }

    // Test Voice CMD_TESTVOICE
    // play test voice:\n
    // 0 Thank You\n
    // 1 Incorrect Password\n
    // 2 Access Denied\n
    // 3 Invalid ID\n
    // 4 Please try again\n
    // 5 Duplicate ID\n
    // 6 The clock is flow\n
    // 7 The clock is full\n
    // 8 Duplicate finger\n
    // 9 Duplicated punch\n
    // 10 Beep kuko\n
    // 11 Beep siren\n
    // 12 -\n
    // 13 Beep bell\n
    // 14 -\n
    // 15 -\n
    // 16 -\n
    // 17 -\n
    // 18 Windows(R) opening sound\n
    // 19 -\n
    // 20 Fingerprint not emolt\n
    // 21 Password not emolt\n
    // 22 Badges not emolt\n
    // 23 Face not emolt\n
    // 24 Beep standard\n
    // 25 -\n
    // 26 -\n
    // 27 -\n
    // 28 -\n
    // 29 -\n
    // 30 Invalid user\n
    // 31 Invalid time period\n
    // 32 Invalid combination\n
    // 33 Illegal Access\n
    // 34 Disk space full\n
    // 35 Duplicate fingerprint\n
    // 36 Fingerprint not registered\n
    // 37 -\n
    // 38 -\n
    // 39 -\n
    // 40 -\n
    // 41 -\n
    // 42 -\n
    // 43 -\n
    // 43 -\n
    // 45 -\n
    // 46 -\n
    // 47 -\n
    // 48 -\n
    // 49 -\n
    // 50 -\n
    // 51 Focus eyes on the green box\n
    // 52 -\n
    // 53 -\n
    // 54 -\n
    // 55 -\n
    //
    // :param index: int sound index
    // :return: bool
    public ZKCommandReply testVoice(int voice) throws IOException {
        int[] voiceArray = new int[] { voice };
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_TESTVOICE, sessionId, replyNo, voiceArray);
        byte[] buf = new byte[toSend.length];
        int index = 0;

        for (int byteToSend : toSend) {
            buf[index++] = (byte) (byteToSend & 0xFF);
        }

        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;

        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));
        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);

        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // CMD_REFRESHDATA (Not Verified)
    /**
     * Demande au lecteur d'actualiser ses données après une écriture.
     * @return réponse du lecteur ; vérifier {@code CMD_ACK_OK}
     * @throws IOException si l'échange UDP échoue
     * @throws ParseException si la réponse ne peut être décodée
     */
    public ZKCommandReply RefreshData() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_REFRESHDATA, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }

        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // CMD_FREE_DATA (Not Verified)
    public ZKCommandReply FreeDeviceBuffer() throws IOException, ParseException {
        int[] toSend = ZKCommand.getPacket(CommandCodeEnum.CMD_FREE_DATA, sessionId, replyNo, null);
        byte[] buf = new byte[toSend.length];
        int index = 0;
        for (int byteToSend : toSend) {
            buf[index++] = (byte) byteToSend;
        }
        DatagramPacket packet = new DatagramPacket(buf, buf.length, address, port);
        socket.send(packet);
        replyNo++;
        int[] response = readResponse();
        CommandReplyCodeEnum replyCode = CommandReplyCodeEnum.decode(response[0] + (response[1] * 0x100));

        if (replyCode == CommandReplyCodeEnum.CMD_ACK_OK) {
            // boolean first = true;
        }

        int replyId = response[6] + (response[7] * 0x100);
        int[] payloads = new int[response.length - 8];
        System.arraycopy(response, 8, payloads, 0, payloads.length);
        return new ZKCommandReply(replyCode, sessionId, replyId, payloads);
    }

    // Method to create JSON backup
    public void createBackup() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        try (FileWriter writer = new FileWriter("device_backup.json")) {
            Map<String, Object> deviceInfoMap = new HashMap<>();
            // deviceInfoMap.put("attendanceRecords", getAttendanceRecords());
            deviceInfoMap.put("isOnlyRFMachine", IsOnlyRFMachine());
            deviceInfoMap.put("firmwareVersion", getFirmwareVersion());
            deviceInfoMap.put("productTime", getProductTime());
            deviceInfoMap.put("deviceName", getDeviceName());
            deviceInfoMap.put("pin2Width", getPIN2Width());
            deviceInfoMap.put("attendanceRecords", getAttendanceRecords());
            deviceInfoMap.put("isOnlyRFMachine", IsOnlyRFMachine());
            deviceInfoMap.put("firmwareVersion", getFirmwareVersion());
            deviceInfoMap.put("productTime", getProductTime());
            deviceInfoMap.put("deviceName", getDeviceName());
            deviceInfoMap.put("pin2Width", getPIN2Width());
            deviceInfoMap.put("showState", getShowState());
            deviceInfoMap.put("deviceIP", getDeviceIP());
            deviceInfoMap.put("devicePort", getDevicePORT());
            deviceInfoMap.put("commKey", getCommKey());
            deviceInfoMap.put("deviceId", getDeviceId());
            deviceInfoMap.put("isDHCP", iSDHCP());
            deviceInfoMap.put("dns", getDNS());
            deviceInfoMap.put("enableProxyServer", isEnableProxyServer());
            deviceInfoMap.put("proxyServerIP", getProxyServerIP());
            deviceInfoMap.put("proxyServerPort", getProxyServerPort());
            deviceInfoMap.put("daylightSavingTime", isDaylightSavingTime());
            deviceInfoMap.put("language", getLanguage());
            deviceInfoMap.put("lockPowerKey", isLockPowerKey());
            deviceInfoMap.put("voiceOn", isVoiceOn());
            deviceInfoMap.put("platform", getPlatform());
            deviceInfoMap.put("serialNumber", getSerialNumber());
            deviceInfoMap.put("mac", getMAC());
            deviceInfoMap.put("faceVersion", getFaceVersion());
            deviceInfoMap.put("fpVersion", getFPVersion());
            deviceInfoMap.put("oemVendor", getOEMVendor());
            // deviceInfoMap.put("allUsers", getAllUsers());
            deviceInfoMap.put("workCode", getWorkCode());
            deviceInfoMap.put("deviceStatus", getDeviceStatus());
            deviceInfoMap.put("state", getState());
            // deviceInfoMap.put("deviceTime", getDeviceTime());
            // List<SmsInfo> smsInfo = null;
            // int i = 1;
            // while (smsInfo == null && i <= 10) { // You can adjust the loop condition as
            // needed
            // smsInfo = getSms(i);
            // i++;
            // }
            // deviceInfoMap.put("smsInfo", smsInfo);
            objectMapper.writeValue(writer, deviceInfoMap);
            System.out.println("Device information backup created successfully.");
        } catch (IOException | ParseException e) {
            e.printStackTrace();
            System.out.println("Error creating device information backup.");
        }
    }

    // Read response
    public int[] readResponse() throws IOException {
        byte[] buf = new byte[1000000];

        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);

        int[] response = new int[packet.getLength()];

        for (int i = 0; i < response.length; i++) {
            response[i] = buf[i] & 0xFF;
        }

        if (Boolean.getBoolean("zkteco.debugProtocol") && response.length >= 8) {
            System.out.println("UDP response: bytes=" + response.length
                    + ", code=" + (response[0] | (response[1] << 8))
                    + ", session=" + (response[4] | (response[5] << 8))
                    + ", reply=" + (response[6] | (response[7] << 8)));
        }

        return response;

        /*
         * int index = 0;
         * int[] data = new int[1000000];
         * 
         * int read;
         * int size = 0;
         * 
         * boolean reading = true;
         * 
         * while (reading && (read = is.read()) != -1) {
         * if (index >= 4 && index <= 7) {
         * size += read * Math.pow(16, index - 4);
         * } else if (index > 7) {
         * if (index - 7 >= size) {
         * reading = false;
         * }
         * }
         * 
         * data[index] = read;
         * index++;
         * }
         * 
         * int[] finalData = new int[index];
         * 
         * System.arraycopy(data, 0, finalData, 0, index);
         * 
         * return finalData;
         */
    }

}
