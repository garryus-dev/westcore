# WestCore

Plugin maison WestCube (Paper 1.21.11, Java 21). Remplace WestAPI, Skript + skript-placeholders
(5 scripts), BetterKeepInventory, TAB, MiniMOTD et InvisibleFrames.

| Module     | Remplace                     | Commandes / placeholders |
|------------|------------------------------|--------------------------|
| `api`      | WestAPI                      | `GET /api/top-voters`, `/api/codes`, `/api/codes/valeurs`, `/api/joueur/<pseudo>/codes` (en-tête `X-API-Key`) |
| `afk`      | westafk_v2.3.sk              | `/afk`, `%westcube_afk%` |
| `dragon`   | dragon_system_v2.1.sk        | `/endstatus`, `/dragonkills`, `/dragonkillsset`, `/endreset`, `%westcube_endstatus%`, `%westcube_endcooldown%`, `%westcube_dragonkills%` |
| `keepinv`  | keepinv_v1.1.sk + BetterKeepInventory | `/keepinv [joueur]`, `%westcube_keepinv%` |
| `lastpos`  | westcube_lastpos_v1.2.sk     | `/tplastpos <joueur> <monde>`, `%westcube_haslastpos_<monde>%` |
| `staffcmd` | westcmd_v2.sk                | `/mob`, `/tuer`, `/entites`, `/claims`, `/westcmd` |
| `tab`      | TAB                          | header/footer, scoreboard + `/sb`, préfixes LuckPerms, tri par grade |
| `motd`     | MiniMOTD                     | MOTD, icône, places affichées |
| `frames`   | InvisibleFrames              | accroupi + clic droit sur un cadre |
| `stats`    | —                            | `/wc stats` (menu en jeu, résumé en console), `/wc stats export [7d\|30d\|90d\|all]` |

Chaque module s'active / se désactive dans `config.yml` (`<module>.enabled`).
Administration : `/wc reload | modules | perms | migrate-skript [chemin]` (permission `westcore.admin`).

## Version 2.0.0

- Toutes les commandes passent sous `/wc` ; les anciens noms restent disponibles via `core.aliases`.
- Permissions configurables par module (`<module>.permissions`), déclarées par le plugin et non plus par `plugin.yml`.
- Nouveau module `stats` (menu, export .xlsx).
- Mise à jour depuis 1.0.0 : remplacer le jar et **redémarrer** (pas de `/reload`). Les nouvelles options sont ajoutées
  à `config.yml` et les anciennes clés de permission reprises automatiquement ; vérifier ensuite avec `/wc perms`.

## Commandes et raccourcis

Toutes les commandes sont des sous-commandes de `/westcore` (alias `/wc`) : `/wc afk`, `/wc keepinv`, `/wc mob`…
`/wc` seul liste les commandes accessibles au joueur.

`core.aliases` dans `config.yml` crée des commandes directes, recréées à chaque `/wc reload` :

```yaml
core:
  aliases:
    afk: "afk"                    # /afk = /wc afk
    export7: "stats export 7d"    # arguments fixes possibles
```

Par défaut, tous les anciens noms (`/afk`, `/keepinv`, `/mob`, `/sb`…) sont déclarés : rien ne change pour les joueurs.
Supprimer une ligne supprime le raccourci. Si le nom est déjà pris par un autre plugin, il reste disponible en `/westcore:<nom>`.

## Permissions

Chaque module déclare ses permissions dans `<module>.permissions` (clé interne → nom). Les noms se changent librement
et s'appliquent au reload. Les commandes et raccourcis sont masqués aux joueurs qui n'y ont pas accès.
`/wc perms` affiche les permissions et raccourcis effectifs.

⚠ Renommer une permission ne met pas à jour LuckPerms : modifier les groupes en même temps (`lp search <ancien.nom>`).
Les anciennes clés `frames.permission`, `tab.staff-permission` et `tab.scoreboard.toggle-permission` sont reprises automatiquement.

## Statistiques (`stats`)

- Base SQLite `plugins/WestCore/data/stats.db` (driver fourni par Paper), écrite par un thread dédié `WestCore-Stats` :
  aucune requête sur le thread principal (une tentative lève une exception).
- Sessions : ouvertes au join, fermées au quit (raison Paper), à l'arrêt (`SHUTDOWN`), ou au démarrage suivant
  après un crash (`CRASH`, à l'heure du dernier relevé). Un `/wc reload` ne coupe pas les sessions.
- Stockés : pseudo, UUID, adresse du serveur tapée, version / marque / langue du client, temps AFK (module `afk`). Jamais l'IP.
- Relevés toutes les 5 min (calés sur l'horloge) : connectés, TPS, MSPT moyen et pire tick, RAM, chunks, entités, ping moyen.
- Tâche quotidienne (04:00, fuseau `stats.timezone`) : table journalière, purge des relevés > 90 j et des exports > 7 j.
- Les joueurs avec `westcore.stats.exempt` sont totalement ignorés (y compris dans le nombre de connectés).
- Menu (`westcore.stats.view`) : vue d'ensemble, fréquentation, rétention, joueurs (fiche par joueur), performance.
  Calculs en asynchrone, mis en cache 60 s.
- Export (`westcore.stats.export`, cooldown 60 s) : `plugins/WestCore/exports/stats_AAAA-MM-JJ_<période>.xlsx`,
  onglets Résumé, Journalier, Horaire, Joueurs, Rétention, Performance. Dates Excel, durées en `[h]:mm`
  (sommables), taux en %. `stats.export.anonymize` remplace les pseudos par « Joueur #0042 » et retire les UUID.
  L'onglet Performance ne remonte pas au-delà de `raw-retention-days`.

## Codes courts du site

`plugins/WestCore/codes-courts.yml` associe un identifiant à un placeholder PlaceholderAPI.
Chaque entrée devient un code court WordPress `[wc_<id>]` (plugin WestAPI ≥ 2.0, onglet « Codes courts »).
Ce fichier est la liste blanche de ce que le site peut lire. Après modification : `/westcore reload`.

## Compiler

```bash
mvn -B package
```

Le jar est produit dans `target/WestCore.jar` (FastExcel embarqué et relocalisé sous `com.westcube.westcore.libs`).
Les tests (stockage SQLite des stats) tournent pendant `package`.

## Mise en production

1. Arrêter le serveur, faire une sauvegarde de `plugins/`.
2. Retirer `westapi.jar`, `Skript-*.jar`, `skript-placeholders-*.jar`, `BetterKeepInventory-*.jar`,
   `TAB*.jar`, `minimotd-*.jar`, `InvisibleFrames-*.jar` (et `ProtocolLib.jar` si le test local est concluant).
   **Garder** les dossiers `plugins/Skript/`, `plugins/WestAPI/` et `plugins/TAB/` jusqu'à la fin de la migration
   (les joueurs ayant masqué leur scoreboard sont repris de `TAB/playerdata.yml`).
3. Déposer `WestCore.jar`, démarrer.
   La clé et le port de l'API sont repris automatiquement de `plugins/WestAPI/config.yml`.
4. En console : `westcore migrate-skript`
   (importe kills du dragon, état du cycle, keepinv, dernières positions,
   et retire l'ancienne permission `betterkeepinventory.keep` des joueurs importés).
5. Vérifier : `westcore modules`, `/papi parse me %westcube_dragonkills%`, menu `/mondes`, le site.
6. Si un grade donnait `betterkeepinventory.keep` en permanence : `lp search betterkeepinventory.keep`,
   puis lui donner `westcube.keepinv.always` à la place.
7. Une fois validé, archiver `plugins/Skript/`, `plugins/WestAPI/`, `plugins/TAB/`, `plugins/MiniMOTD/`, `plugins/InvisibleFrames/`.
8. Reporter aussi `server.properties`, `spigot.yml` et `config/paper-world-defaults.yml` optimisés
   (vérifier qu'aucun monde n'a de `paper-world.yml` qui écrase ces valeurs).

## Nouvelles permissions

- `westcube.keepinv.always` : garde l'inventaire sans passer par `/keepinv`.
- `westcube.end.bypass` : entrer dans le monde du dragon même verrouillé.
