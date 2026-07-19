package com.looker.droidify.data.backup

import com.looker.droidify.datastore.model.CustomButton
import com.looker.droidify.external.ExternalAccount
import com.looker.droidify.external.ExternalApp
import kotlinx.serialization.Serializable

/** The zip's own table of contents — written first, read first, so a restore can show exactly what a
 *  backup contains before touching anything. [categories] is what the backup was CREATED with;
 *  [BackupRepository.inspectBackup] additionally cross-checks that each one's file genuinely made it
 *  into the archive, in case of a partial/corrupted zip. */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val appVersionName: String,
    val exportedAt: Long,
    val categories: Set<BackupCategory>,
)

/** One repository, in backup form. Deliberately its own shape rather than the app's internal repo
 *  models — those carry sync bookkeeping (timestamps, etags, a DB id) that means nothing once restored
 *  into a different database row. [username]/[password] are stored as plain text, exactly
 *  as reversible as the legacy Base64-wrapped export this replaces (Base64 is an encoding, not
 *  encryption — it added no real confidentiality) — a repository with saved credentials makes this a
 *  sensitive file, same as before. */
@Serializable
data class RepoBackupEntry(
    val address: String,
    val name: String = "",
    val description: String = "",
    val fingerprint: String = "",
    val enabled: Boolean = true,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class RepositoriesBackup(val repositories: List<RepoBackupEntry> = emptyList())

/** Both tracked external-source shapes together — a single-repo [ExternalApp] and a whole-account
 *  [ExternalAccount] — since the old per-category export only ever covered the former, silently
 *  dropping any account-level source on every export. */
@Serializable
data class ExternalSourcesBackup(
    val apps: List<ExternalApp> = emptyList(),
    val accounts: List<ExternalAccount> = emptyList(),
)

@Serializable
data class FavouritesBackup(val packageNames: Set<String> = emptySet())

@Serializable
data class CustomButtonsBackup(val buttons: List<CustomButton> = emptyList())
