# Omnify

## Envoi automatique vers F-Droid Panel

- `fdroid-panel.gradle.kts`, à la racine du projet, envoie automatiquement chaque build **trial** vers le F-Droid Panel du mainteneur, et seulement la trial : c'est la ligne `fdroidPanel.variant=trial` de `gradle.properties` qui le décide. La release et la beta ne sont jamais envoyées.
- Ne jamais modifier `fdroid-panel.gradle.kts` à la main, ni supprimer la ligne `apply(from = rootProject.file("fdroid-panel.gradle.kts"))` à la fin de `app/build.gradle.kts`, ni la ligne `fdroidPanel.variant=trial`, même lors d'un nettoyage ou d'une mise à jour de Gradle ou du plugin Android.
- Pour le mettre à jour, le retélécharger tel quel depuis https://raw.githubusercontent.com/Victor-root/F-Droid-Panel/main/tools/android/fdroid-panel.gradle.kts
- La configuration (`fdroidPanel.url`, `fdroidPanel.repository`, `fdroidPanel.key`) est dans `keystore.properties`, qui ne doit jamais être commité.
- Chaque build envoyé doit avoir un `versionCode` plus grand que le précédent, sinon le panneau le refuse. C'est déjà le cas : hors debug, le `versionCode` est calculé à chaque build à partir de l'heure (voir `BuildMinute` dans `app/build.gradle.kts`).
