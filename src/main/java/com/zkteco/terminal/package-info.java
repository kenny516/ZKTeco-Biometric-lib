/**
 * Communication UDP avec les lecteurs ZKTeco.
 * <p>Point d'entrée : {@link com.zkteco.terminal.ZKTerminal}. Ouvrir et authentifier
 * la session, effectuer les opérations, puis la fermer dans un bloc {@code finally}.
 * Les commandes et le listener temps réel partagent le même socket : effectuer les
 * lectures/écritures avant l'écoute et ne pas envoyer de commande depuis son callback.
 */
package com.zkteco.terminal;
