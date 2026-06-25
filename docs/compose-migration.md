# Plan de migration vers Jetpack Compose — Droidify Enhanced

> Objectif : migrer **toute** l'app de l'ancien système (Android Views + XML)
> vers Jetpack Compose, puis **supprimer** l'ancien système. Fait de façon
> **incrémentale** : chaque phase compile, est testable, et est validée avant
> de passer à la suivante.

## État des lieux (audit du code existant)

L'app tourne désormais **entièrement sur Compose** (`MainComposeActivity` est le
lanceur **et** le gestionnaire de deeplinks). L'**ancienne interface Views a été
supprimée** (`MainActivity`, tout `ui/`, `widget/`, `CursorOwner`). Il reste à
unifier la **couche données** : une ancienne base + une ancienne synchro
héritées sont encore utilisées par la tuyauterie (sync / install / prefs) en
parallèle de la nouvelle base Room.

| Zone | État Compose | Manques principaux |
|------|--------------|--------------------|
| Réglages | ✅ Fini (déjà actif) | — (sélecteur de couleur déjà ajouté) |
| Dépôts (liste/détail/édition) | 🟡 ~80% | bouton "Ajouter un dépôt", choix de miroir, collage presse-papier, nb d'apps |
| Accueil / navigation d'apps | 🟢 ~80% | filtre par dépôt, bouton "tout mettre à jour" (onglets ✅, recherche/tri ✅, états vides ✅, refresh auto après synchro ✅) |
| Détail d'app | 🟡 ~75% | menus de la barre, description déroulante, anti-fonctionnalités (installer/MàJ/lancer/désinstaller ✅, favori ✅, changelog ✅, permissions ✅, liens ✅, captures plein écran ✅) |
| Favoris | ❌ Absent | écran dédié à créer |
| Dialogues (permissions, incompatibilité) | ❌ Absent | à porter |
| Coquille (`MainComposeActivity`) | 🟢 ~85% | reste : pré-remplissage deeplink (recherche/adresse) + intents internes notifs (install/MàJ) ; flux install ✅, deeplinks externes ✅, couleur ✅, recréation thème ✅ |

## Feuille de route

### Phase 0 — Fondations (la coquille Compose devient capable)
- [x] Porter le thème + couleur d'accentuation (DynamicColors) dans `MainComposeActivity`
- [x] Faire de `MainComposeActivity` le lanceur de l'app (l'app ouvre directement Compose)
- [x] Brancher le flux d'installation/désinstallation (`InstallManager`)
- [~] Gérer les intents/deeplinks : **voir une app** ✅ (lien f-droid / `market://` / `fdroid.app` → fiche Compose), **ajouter un dépôt** (`fdroidrepo://`) → formulaire, recherche → accueil. Les filtres d'intent sont passés de `MainActivity` à `MainComposeActivity`. Reste : pré-remplir l'adresse/la recherche, et les intents **internes** des notifs (install/MàJ) — à finir en supprimant `MainActivity`.
- **Testable :** lancer l'app Compose, vérifier thème/couleur, et que les liens externes ouvrent le bon écran.

### Phase 1 — Accueil / navigation (+ redesign)
- [x] **Onglets Disponibles / Installées / Mises à jour** — l'onglet MàJ affiche un compteur ; détection des mises à jour en comparant la version installée à la dernière version dispo (versionCode)
- [x] Recherche + **tri** (menu déroulant fonctionnel, persiste le choix) + synchro (+ barre de progression)
- [ ] Filtre par dépôt
- [~] États vides (onglets Installées/MàJ ✅), bouton "remonter en haut", "tout mettre à jour"
- [x] **Rafraîchir après une synchro** : la liste, le carrousel « What's new » et le compteur de mises à jour se mettent à jour **tout seuls** après une synchro (et après une install/désinstall), via un signal réactif Room sur les tables `app`/`version`. Plus besoin de changer un filtre pour voir les nouveautés.
- [~] Redesign **accueil "magasin" unifié** : 1re vitrine en place (carrousel « What's new » / nouveautés en haut de l'accueil). Reste : section « mises à jour dispo », catégories mises en avant.
- **Testable :** parcourir, chercher, trier, synchroniser depuis l'accueil Compose.

### Phase 2 — Détail d'app (parité complète)
- [x] **2a** — Bouton d'action (états Installer/MàJ/Lancer/Désinstaller/Annuler) + **Lancer** + **Désinstaller** — ✅ validé sur téléphone
- [x] **2b** — **Téléchargement + installation** (le bouton télécharge, vérifie le hash SHA-256, puis installe) — branché directement via `Downloader` + `InstallManager` (sans passer par `DownloadService`). **Vraie barre de progression** : Mo téléchargés / total, vitesse en Mo/s et %, bouton Annuler. Manque : téléchargement en arrière-plan (notification) + reprise.
- [ ] Actions de la barre (partager, source, infos, désinstaller)
- [x] Sections : **changelog (What's new)**, **permissions** (dépliable, avec compteur), **liens** (site / source / suivi de bugs / changelog / traduction / dons / site de l'auteur) — tout depuis les données déjà chargées
- [x] Captures en **plein écran** : visionneuse balayable (HorizontalPager, fond noir, bouton fermer)
- [ ] Sections restantes : vidéo, description **déroulante**, anti-fonctionnalités (données pas encore peuplées côté data), liste des versions (déjà affichée en brut)
- [x] **Bouton favori** (persiste dans les réglages)
- [ ] Interrupteurs "ignorer les mises à jour"
- **➡️ Compose est déjà le lanceur** (bascule faite en phase 0) ; cette phase le rend pleinement utilisable (parcourir + installer).
- **Testable :** installer/mettre à jour/lancer de vraies apps depuis Compose.

### Phase 3 — Dépôts + Favoris
- [ ] Bouton "Ajouter un dépôt", dialogue de miroir, collage presse-papier, nb d'apps
- [ ] Écran Favoris dédié + entrée de navigation

### Phase 4 — Dialogues & flux restants
- [ ] Dialogue des permissions, dialogues d'incompatibilité, confirmations (en Compose)
- [ ] Deeplink de recherche + cas limites d'intents

### Phase 5 — Couleur du thème, étapes 2 & 3
- [ ] Onglet "Personnalisé" (couleur libre)
- [ ] Onglet "Icône" (couleur de l'icône de l'app)
- [ ] Picker à onglets conforme au screenshot

### Phase 6 — Suppression de l'ancien système
- [x] **Supprimer l'ancienne UI Views** : `MainActivity`, tout `ui/` (21 fichiers), `widget/` (5), `CursorOwner`, l'extension `Fragment.mainActivity` — supprimés ; entrée manifest retirée ; notifs + deeplinks repointés sur Compose.
- [x] Supprimer les **layouts XML** (21) — fait ; reste les ressources héritées (anim/animator/styles) devenues inutilisées
- [x] **Unifier la couche données — FAIT : un seul moteur de données (Room).** 🎉
  - [x] Auto-sync (périodique + forcée) basculé sur le **nouveau moteur** (`SyncWorker` → `RepoRepository` → Room), au lieu du legacy `SyncService`. Toute synchro alimente maintenant Room.
  - [x] `ProductPreferences` (ignorer les MàJ) et l'auto-MàJ de `InstallManager` découplés du legacy `Database`.
  - [x] **Moteur de synchro legacy supprimé** : `SyncService` (+ `SyncService$Job`) et `RepositoryUpdater` supprimés — plus aucun appelant (init retirée de `Droidify`, entrées manifest et test associé supprimés, imports morts nettoyés).
  - [x] **Export/import de dépôts (`SettingsViewModel`) porté sur Room** : lecture via `RepoRepository.getRepo` (conserve identifiants + miroirs), réimport via `insertRepo` + `enableRepository` (dédup par adresse, état activé restauré, sync déclenchée). Le format de fichier de backup legacy est conservé (mapping `Repo` → `Repository`).
  - [x] **`UnarchiveWorker` (Android 15+) porté sur Room** : recherche via `AppRepository`, choix de l'APK via `selectForDevice`, téléchargement + vérif SHA-256 via le `Downloader` partagé, install via `InstallManager`. Ne dépend plus du legacy `Database` ni de `DownloadService`. ⚠️ Filtrage par signature retiré temporairement (le modèle Room ne stocke pas encore les signatures — `signer = emptySet()`) ; TODO posé pour le réintroduire.
  - [x] **Legacy `Database` SQLite supprimé** : `Database.kt`, `table/` (`Table`, `DatabaseHelper`), `QueryBuilder`, `QueryLoader`, `ObservableCursor` supprimés ; `Database.init` retiré de `Droidify`. `RepositoryExporter` conservé (sérialisation du backup, indépendant de la base) — à reloger hors du package `database/` au nettoyage final.
- [~] **Nettoyage final** :
  - [x] **Code legacy mort supprimé** : l'ancien service de téléchargement (`service/` : `DownloadService`, `Connection`, `ConnectionService`, `DownloadManager`, `ReleaseFileValidator` + `di/DownloadModule` + l'extension `startUpdate`), les parseurs d'index legacy (`index/` : `IndexV1Parser`, `IndexMerger`, `OemRepositoryParser`), et les (dé)sérialiseurs + modèles legacy orphelins (`Product`/`Release`/`ProductItem`/`ProductPreference` Serialization, modèles `Product` + `ProductPreference`). Vérifié à chaque fois : zéro référence restante.
  - Encore actifs (legacy de nom mais utilisés par le nouveau moteur, **à garder**) : modèles `Repository`, `Release`, `ProductItem`, `InstalledItem` ; `RepositorySerialization` + `RepositoryExporter` (backup des dépôts) ; `utility/extension/android/Android` (utilisé par `ReleaseItem`).
  - [ ] Reste : ressources orphelines (`anim`/`animator`/styles/strings inutilisés), dépendances Gradle inutilisées, et reloger `RepositoryExporter` hors de `database/`.

## Stratégie (choix du mainteneur)
L'app bascule **immédiatement** sur l'interface Compose : une seule app, une seule
icône. Pendant la migration l'app est donc **incomplète** (notamment, l'installation
d'apps ne marche qu'à partir de la phase 2) — c'est assumé. L'ancienne `MainActivity`
(Views) est conservée seulement pour ses deeplinks, jusqu'à leur migration.

## Bugs connus (à corriger)
- **Synchro gourmande en mémoire** : l'analyse de l'index v2 alloue ~200 Mo
  d'un coup → `OutOfMemoryError` lors d'un vrai parse d'index (repéré dans le
  logcat en testant le bouton Sync). L'app survit (erreur rattrapée) et la
  synchro fonctionne quand l'index est inchangé (cache), mais une grosse mise à
  jour d'index échouera. Correctif prévu : parser l'index en streaming plutôt
  que de tout charger en mémoire. (Code hérité, couche data/sync v2.)

## Notes techniques (pièges rencontrés)
- **Nom de fichier APK avec slash en tête** : dans l'index **v2**, les noms de
  fichiers commencent par `/` (ex. `/An.stop_10.apk`). Pour construire l'URL de
  téléchargement il faut **concaténer** `repo.address` + le nom (comme le fait
  `sync/v2/EntrySyncable.kt`), surtout **pas** `Uri.appendPath()` qui encoderait
  le `/` en `%2F` → le serveur renvoie une erreur et l'install échoue. (Bug
  corrigé dans `AppDetailViewModel.downloadAndInstall`.)
- **Choix de l'APK multi-ABI** : les apps comme **VLC** publient un APK **par
  ABI** (arm64-v8a, x86_64, x86…) sous des `versionCode` **différents** (…04, …03,
  …02), et `suggestedVersionCode` = le plus haut = l'APK **arm64**. Prendre
  bêtement le plus haut installe l'APK arm64 sur un appareil x86 →
  `INSTALL_FAILED_NO_MATCHING_ABIS` (peu importe l'installateur : root **ou**
  session). Il faut filtrer par ABI compatible (`Build.SUPPORTED_ABIS`) **avant**
  de choisir le plus récent, comme l'ancien moteur. Logique centralisée dans
  `data/model/DeviceAbi.kt` (`selectForDevice`), réutilisée par l'install
  **et** l'écran (sinon « mise à jour dispo » en boucle sur x86).

## Features livrées (après le moteur unique)
- [x] **Barre de synchro dans l'app** — bandeau (spinner + « Synchronisation en cours ») sous les
  onglets, affiché pour **toute** synchro (1er lancement, bouton Sync, activation d'un dépôt,
  périodique), sur l'écran principal et l'écran Dépôts. Source unique : `SyncWorker.isSyncing()`.
- [x] **Sources externes façon Obtainium** (paquet `github/` + `compose/githubApps/`) — ajout d'un
  projet par URL, choix d'APK selon l'ABI, download + install via le moteur existant, suivi des
  MàJ. Onglet « GitHub » dans la liste principale ; ajout via Dépôts → `</>`.

## Modernisation UI (chantier en cours — demandé par le mainteneur)
> Objectif : **modernisation totale**, propre et sérieuse (pas de bricolage), par étapes. Réf. de
> style citées : **Aurora Store** (affichage des apps) et **tabler.io** (icônes). Seul le panneau
> Réglages est jugé déjà beau/moderne.

1. [x] **Apps externes = cycle de vie complet** : sur l'onglet **Externe**, vraie barre de
   progression (taille/%/vitesse) + boutons Installer / Mettre à jour / Ouvrir / Désinstaller,
   comme les apps F-Droid (lignes de progression partagées via `components/InstallProgress.kt`,
   carte partagée `ExternalAppCard`). **Fait.**
2. [x] **Recherche repliable** : bouton **loupe** dans le header qui déplie un champ au tap
   (masqué par défaut). **Fait.**
3. [x] **Refonte de la liste d'apps** — **grille de cartes 2 colonnes** (icône large + nom +
   résumé + version), chips de catégories défilantes supprimées. **Fait.**
4. [x] **Retirer la surbrillance d'accent** : surfaces neutres **noir/blanc selon light/dark**
   (`Theme.withNeutralSurfaces` + `surfaceTint` transparent), accent réservé titres/boutons. **Fait.**
5. [x] **Retirer le toggle « Material You »** des Réglages (doublon avec l'option *Fond d'écran* de
   la palette de couleurs, qui active déjà Material You). **Fait.**
6. **Traductions FR** : tout ce qui a été ajouté récemment est en dur en anglais → passer par des
   `string` resources + `values-fr`. (Partie *Externe* faite ; reste à balayer l'app : messages
   « No installed apps found », « Everything is up to date », placeholder « Search », etc.)
7. **Refonte des icônes** (tabler.io) : header + loupe/sync/tri/serveur/réglages + package/plus/
   trash faits ; reste back, check, et divers, à passer en vecteurs tabler.
8. [x] **Sources externes universelles** — **GitHub + GitLab + Codeberg**, détection auto depuis
   l'URL (paquet `external/`, `SourceProvider`, `ExternalApi`). Onglet renommé « Externe ».
   Migration auto de `github_apps.json` → `external_apps.json`. **Fait.**

**Contraintes mainteneur (à respecter dans toute la refonte) :**
- **Garder les 4 onglets** (Explorer / Installé / Mises à jour / Externe) + le **header** avec les
  fonctions principales — ce système plaît et est rapide d'accès. On change le *contenu*, pas la
  structure onglets+header.
