package com.zkteco.commands;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.List;

/** Command 110 buffered layout based on pyzk HR_save_usertemplates (72-byte users). */
public final class UserBiometricCodec {
    private final UserRecordCodec userCodec;

    public UserBiometricCodec(Charset charset) { userCodec = new UserRecordCodec(charset); }

    /**
     * Construit le buffer de commande 110 : profil marqué, table UID/doigt/offset,
     * puis templates précédés de leurs longueurs. N'effectue aucun échange réseau.
     * @param user profil avec l'UID du lecteur destination
     * @param fingerprints templates avec des indices distincts, liste vide autorisée
     * @return buffer little-endian prêt à transférer par morceaux
     * @throws IllegalArgumentException si le profil ou les templates sont invalides
     */
    public byte[] encode(UserInfo user, List<FingerprintTemplate> fingerprints) {
        // Also checks duplicate finger indices before producing a device packet.
        UserBiometricData snapshot = new UserBiometricData(user, fingerprints);
        byte[] record = userCodec.encode(snapshot.getUser());
        int templateBytes = 0;
        for (FingerprintTemplate fingerprint : fingerprints) {
            templateBytes += 2 + fingerprint.getSize();
        }
        int userBytes = 1 + record.length;
        int tableBytes = 8 * fingerprints.size();
        ByteBuffer buffer = ByteBuffer.allocate(12 + userBytes + tableBytes + templateBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(userBytes).putInt(tableBytes).putInt(templateBytes);
        buffer.put((byte) 2).put(record);
        int offset = 0;
        for (FingerprintTemplate fingerprint : fingerprints) {
            buffer.put((byte) 2).putShort((short) user.getUid())
                    .put((byte) (0x10 + fingerprint.getFingerIndex())).putInt(offset);
            offset += 2 + fingerprint.getSize();
        }
        for (FingerprintTemplate fingerprint : fingerprints) {
            buffer.putShort((short) fingerprint.getSize()).put(fingerprint.getTemplate());
        }
        return buffer.array();
    }
}
