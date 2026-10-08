package com.zkteco;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zkteco.Enum.CommandReplyCodeEnum;
import com.zkteco.commands.FingerprintTemplate;
import com.zkteco.commands.UserBiometricData;
import com.zkteco.commands.UserBiometricWriteResult;
import com.zkteco.commands.UserInfo;
import com.zkteco.commands.ZKCommandReply;
import com.zkteco.terminal.ZKTerminal;

public class MainTest {
    public static void main(String[] args) throws Exception {
        // Modifier les variables ici ; aucun argument de lancement nécessaire.
        String deviceIp = "192.168.204.18";
        int port = 4370;
        int commKey = 0;
        String userId = "32002262";
        Path backupFile = Paths.get("biometric-backups", "user-" + userId + ".json");
        boolean deleteAndRestore = true; // true : supprimer puis réajouter l'utilisateur sauvegardé.
        boolean realtime = true;

        ObjectMapper json = new ObjectMapper();
        ZKTerminal terminal = new ZKTerminal(deviceIp, port);
        terminal.connect();
        try {
            ZKCommandReply auth = terminal.connectAuth(commKey);
            if (auth.getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                throw new IllegalStateException("Authentification refusée : " + auth.getCode());
            }

            // 1. Récupérer les utilisateurs et afficher leur identifiant et leur nom.
            List<UserInfo> users = terminal.getAllUsers();
            for (UserInfo user : users) {
                System.out.println("Utilisateur : " + user.getUserid() + " | " + user.getName());
            }

            // 2. Exporter un utilisateur avec ses informations et ses templates
            // d'empreinte.
            // Sans sélection : essayer tous les indices de doigt, de 0 à 9.

            UserBiometricData userData = terminal.getUserWithFingerprints(userId);
            Files.createDirectories(backupFile.toAbsolutePath().getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(backupFile.toFile(),
                    userData);
            System.out.println("Sauvegarde : " + backupFile.toAbsolutePath());
            System.out.println("Empreintes récupérées : " +
                    userData.getFingerprints().size());
            for (FingerprintTemplate fingerprint : userData.getFingerprints()) {
                System.out.println("Doigt " + fingerprint.getFingerIndex() + " : " +
                        fingerprint.getSize() + " octets");
            }
            if (!userData.isComplete()) {
                System.out.println("Erreurs de lecture : " +
                        userData.getFingerprintReadErrors());
            }

            // 3. Supprimer cet utilisateur puis le réajouter depuis le JSON.
            if (deleteAndRestore) {
                UserBiometricData savedUser = json.readValue(backupFile.toFile(),
                        UserBiometricData.class);
                if (savedUser.getFingerprints().isEmpty()) {
                    throw new IllegalStateException("Aucune empreinte sauvegardée : suppression annulée.");
                }
                // Les erreurs des autres indices n'empêchent pas de restaurer les templates
                // récupérés.
                if (!savedUser.isComplete()) {
                    System.out.println("Restauration des " + savedUser.getFingerprints().size()
                            + " empreintes sauvegardées uniquement ; indices non récupérés : "
                            + savedUser.getFingerprintReadErrors().keySet());
                }
                // Rechercher l'UID actuel, car celui d'une ancienne sauvegarde peut être
                // différent.
                UserInfo currentUser = null;
                for (UserInfo user : terminal.getAllUsers()) {
                    if (userId.equals(user.getUserid())) {
                        if (currentUser != null) {
                            throw new IllegalStateException("Userid présent plusieurs fois.");
                        }
                        currentUser = user;
                    }
                }
                if (currentUser == null) {
                    throw new IllegalStateException("Utilisateur introuvable.");
                }
                if (terminal.disableDevice().getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                    throw new IllegalStateException("Désactivation refusée.");
                }
                try {
                    ZKCommandReply deleted = terminal.delUser(currentUser.getUid());
                    if (deleted == null || deleted.getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                        throw new IllegalStateException("Suppression refusée.");
                    }
                    if (terminal.RefreshData().getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                        throw new IllegalStateException("Actualisation après suppression refusée.");
                    }
                } finally {
                    if (terminal.enableDevice().getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                        throw new IllegalStateException("Réactivation refusée.");
                    }
                }
                for (UserInfo user : terminal.getAllUsers()) {
                    if (userId.equals(user.getUserid())) {
                        throw new IllegalStateException("Suppression non confirmée.");
                    }
                }

                UserBiometricWriteResult restored = terminal.addUserWithFingerprints(savedUser);
                if (!restored.isSuccess()) {
                    throw new IllegalStateException("Restauration incomplète : " +
                            restored.getMessage()
                            + " | sauvegarde conservée : " + backupFile.toAbsolutePath());
                }
                System.out.println(
                        "Utilisateur réajouté avec ses informations et empreintes | UID=" +
                                restored.getAssignedUid());
            }

            // 4. Temps réel en dernier : aucune autre opération ne lit le socket pendant
            // l'écoute.
            if (realtime) {
                Map<String, UserInfo> usersById = new HashMap<String, UserInfo>();
                for (UserInfo user : terminal.getAllUsers()) {
                    usersById.put(user.getUserid(), user);
                }
                if (terminal.enableDevice().getCode() != CommandReplyCodeEnum.CMD_ACK_OK) {
                    throw new IllegalStateException("Activation refusée.");
                }
                terminal.startRealTimeLogs(record -> {
                    UserInfo user = usersById.get(record.getUserID());
                    System.out.println("Utilisateur : " + record.getUserID()
                            + (user == null ? "" : " | " + user.getName()));
                });
                System.out.println("En attente de pointages. ENTER pour arrêter.");
                System.in.read();
            }
        } finally {
            terminal.stopRealTimeLogs();
            terminal.disconnect();
        }
    }
}
