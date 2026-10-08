/**
 * Profils, templates et résultats des opérations du lecteur ZKTeco.
 * <p>Utiliser {@link com.zkteco.commands.UserInfo} pour un profil seul et
 * {@link com.zkteco.commands.UserBiometricData} pour un profil avec ses empreintes.
 * Les templates sont des octets binaires, sérialisés en Base64 par Jackson ; leur
 * index de doigt est distinct de l'UID interne et du userid métier.
 * <p>Les résultats séparent l'écriture du profil, la capture/import des templates
 * et le nettoyage. Un échec ne provoque pas de rollback automatique.
 */
package com.zkteco.commands;
