package com.zkteco;

import java.util.List;

import com.zkteco.commands.AttendanceRecord;
import com.zkteco.commands.UserInfo;
import com.zkteco.commands.ZKCommandReply;
import com.zkteco.terminal.ZKTerminal;

public class MainTest {
    public static void main(String[] args) throws Exception {
        ZKTerminal terminal = new ZKTerminal("192.168.204.18", 4370);

        // 1. Connexion + auth
        ZKCommandReply reply = terminal.connect();
        System.out.println("Connect: " + reply.getCode());

        reply = terminal.connectAuth(100);
        System.out.println("Auth: " + reply.getCode());

        reply = terminal.enableDevice();
        System.out.println("Enable: " + reply.getCode());

        List<UserInfo> users = terminal.getAllUsers();

        System.out.println("=== Utilisateurs sur le terminal ===");
        for (UserInfo user : users) {
            System.out.println(user);
            System.out.println("User ID: " + user.getUserid() + "| Uid: " + user.getUid() + " | Name: " + user.getName()
                    + " | Privilege: "
                    + user.getGroupNumber());
        }

        // 2. Démarrage du listener realtime dans un thread dédié (non bloquant)
        terminal.startRealTimeLogs((AttendanceRecord record) -> {
            System.out.println("=== Pointage détecté ===");
            System.out.println("User ID     : " + record.getUserID());
            System.out.println("User SN     : " + record.getUserSN());
            System.out.println("Verify Type : " + record.getVerifyType());
            System.out.println("Verify State: " + record.getVerifyState());
            System.out.println("Time        : " + record.getRecordTime());
            System.out.println("========================");
        });
        System.out.println("Listener démarré — en attente d'événements...");

        // 3. Le main continue librement ici — exemple : attente clavier pour arrêter
        System.out.println("Appuyez sur ENTER pour arrêter.");
        System.in.read();

        terminal.stopRealTimeLogs();
        terminal.disconnect();
        System.out.println("Arrêt propre.");
    }
}
