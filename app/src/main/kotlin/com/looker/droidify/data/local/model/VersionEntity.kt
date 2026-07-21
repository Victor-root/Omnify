package com.looker.droidify.data.local.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import androidx.room.PrimaryKey
import com.looker.droidify.data.model.ApkFile
import com.looker.droidify.data.model.Manifest
import com.looker.droidify.data.model.Package
import com.looker.droidify.data.model.Permission
import com.looker.droidify.data.model.Platforms
import com.looker.droidify.data.model.SDKs
import com.looker.droidify.network.DataSize
import com.looker.droidify.sync.v2.model.ApkFileV2
import com.looker.droidify.sync.v2.model.FileV2
import com.looker.droidify.sync.v2.model.LocalizedString
import com.looker.droidify.sync.v2.model.PackageV2
import com.looker.droidify.sync.v2.model.PermissionV2
import com.looker.droidify.sync.v2.model.localizedValue

@Entity(
    tableName = "version",
    indices = [
        Index("appId"),
        Index(value = ["appId", "versionCode"], unique = true),
    ],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            childColumns = ["appId"],
            parentColumns = ["id"],
            onDelete = CASCADE,
        ),
    ],
)
data class VersionEntity(
    val added: Long,
    val whatsNew: LocalizedString,
    val versionName: String,
    val versionCode: Long,
    val maxSdkVersion: Int?,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    @Embedded("apk_")
    val apk: ApkFileV2,
    @Embedded("src_")
    val src: FileV2?,
    val features: List<String>,
    val nativeCode: List<String>,
    // SHA-256 fingerprint(s) of this version's signing certificate(s), lowercase hex — exactly the
    // format the installed app's signature is stored in. Used to tell whether a catalogue update can
    // actually replace the installed app (same signer) or not (different signer).
    val signer: List<String>,
    val permissions: List<PermissionV2>,
    val permissionsSdk23: List<PermissionV2>,
    val appId: Int,
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
)

fun PackageV2.versionEntities(appId: Int): Map<VersionEntity, List<AntiFeatureAppRelation>> {
    return versions.map { (_, version) ->
        VersionEntity(
            added = version.added,
            whatsNew = version.whatsNew,
            versionName = version.manifest.versionName,
            versionCode = version.manifest.versionCode,
            maxSdkVersion = version.manifest.maxSdkVersion,
            minSdkVersion = version.manifest.usesSdk?.minSdkVersion ?: -1,
            targetSdkVersion = version.manifest.usesSdk?.targetSdkVersion ?: -1,
            apk = version.file,
            src = version.src,
            features = version.manifest.features.map { it.name },
            nativeCode = version.manifest.nativecode,
            signer = version.manifest.signer?.sha256 ?: emptyList(),
            permissions = version.manifest.usesPermission,
            permissionsSdk23 = version.manifest.usesPermissionSdk23,
            appId = appId,
        ) to version.antiFeatures.map { (tag, reason) ->
            AntiFeatureAppRelation(
                tag = tag,
                reason = reason,
                appId = appId,
                versionCode = version.manifest.versionCode,
            )
        }
    }.toMap()
}

fun List<VersionEntity>.toPackages(
    locale: String,
    installed: InstalledEntity?,
    antiFeatures: List<AntiFeatureAppRelation> = emptyList(),
) = map { version ->
    Package(
        id = version.id.toLong(),
        // Matched by package name + exact versionCode alone — deliberately NOT also gated on
        // [signerMismatch]: a differently-signed install of the same versionCode (the same app from a
        // different distribution channel, most commonly) still counts as "this release, installed" here,
        // so the detail screen's Update/Launch button and the versions list's "Installée" checkmark
        // behave as if there were no signer to compare at all — matching every other "is this installed"
        // signal in the app (see [com.looker.droidify.data.InstalledIdentityRepository]'s own doc
        // comment for the same call). The signer comparison itself still runs, independently, wherever a
        // screen needs to actually WARN about it (the detail screen's own footer message) or decide
        // whether an update can be applied in place without an uninstall first.
        installed = installed != null && installed.versionCode == version.versionCode,
        added = version.added,
        apk = ApkFile(
            name = version.apk.name,
            hash = version.apk.sha256,
            size = DataSize(version.apk.size),
        ),
        platforms = Platforms(version.nativeCode),
        features = version.features,
        // Anti-features are stored per (appId, versionCode) in anti_features_app_relation; pick this
        // version's. The rewrite had dropped this (emptyList()), so the detail screen showed no
        // Tracking/Ads/NonFree/KnownVuln warnings the old Droidify did.
        antiFeatures = antiFeatures
            .filter { it.versionCode == version.versionCode }
            .map { it.tag },
        manifest = Manifest(
            versionCode = version.versionCode,
            versionName = version.versionName,
            usesSDKs = SDKs(
                min = version.minSdkVersion,
                max = version.maxSdkVersion ?: -1,
                target = version.targetSdkVersion,
            ),
            signer = version.signer.toSet(),
            // Include the uses-permission-sdk-23 declarations too (requested at runtime on API 23+).
            // The rewrite only mapped `permissions`, so those extra permissions were never shown.
            // Dedupe by name in case a permission is declared in both lists.
            permissions = (version.permissions + version.permissionsSdk23)
                .distinctBy { it.name }
                .map {
                    Permission(
                        name = it.name,
                        sdKs = SDKs(
                            min = -1, // PermissionV2 doesn't have minSdkVersion
                            max = it.maxSdkVersion ?: -1,
                            target = -1,
                        ),
                    )
                },
        ),
        whatsNew = version.whatsNew.localizedValue(locale) ?: "",
    )
}
