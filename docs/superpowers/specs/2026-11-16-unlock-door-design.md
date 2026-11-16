# Ouverture de porte à distance

## Intention et périmètre
Permettre à une application Java de demander une ouverture temporaire de la porte reliée à un lecteur ZKTeco compatible, via la session UDP existante.

## Approche
Exposer la commande existante `CMD_UNLOCK` (31) dans `ZKTerminal`. C'est la voie directe pour ce besoin ; une simulation de pointage ou une intégration SDK native ajouterait un comportement hors périmètre. Cette approche est proposée par l'agent, en attente de validation.

## API proposée
`public ZKCommandReply unlockDoor(int durationSeconds) throws IOException`.
La durée doit être strictement positive et son encodage doit être vérifié dans une implémentation de référence avant réalisation. La réponse conserve les codes du lecteur, y compris ses refus. Une réponse positive confirme l'acceptation de la commande, pas le mouvement physique de la porte.

## Contraintes
Réutiliser le transport, la session et les conventions de réponse du client existant. Ne pas réessayer automatiquement une ouverture après un délai d'attente. La compatibilité et la durée effective dépendent du firmware et du câblage du relais. Aucun enregistrement de pointage, identifiant utilisateur ou signal vocal n'est demandé.

## Validation et documentation
Ajouter un test UDP simulé qui vérifie la commande et sa durée, les réponses positives et négatives, et le rejet local des durées invalides. Ajouter un exemple au README. Exécuter les tests Maven ; la validation physique nécessitera un lecteur compatible.
