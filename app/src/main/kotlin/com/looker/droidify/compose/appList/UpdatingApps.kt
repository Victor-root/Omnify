package com.looker.droidify.compose.appList

import androidx.compose.runtime.Immutable
import com.looker.droidify.installer.model.InstallState
import com.looker.droidify.installer.model.isInProgress
import com.looker.droidify.work.BatchUpdateProgress

/**
 * What the Updates tab rings, on the phone and on TV: the app "update all" is downloading
 * ([batchPackage], with how far [batch] says it got), and every app the installer holds, queued or
 * installing. Catalogue apps are named by package name and external sources by their key, the way the
 * batch itself names them; the two never collide.
 */
@Immutable
class UpdatingApps(
    private val batchPackage: String?,
    private val batch: BatchUpdateProgress.State?,
    private val installingPackages: Set<String>,
    private val externalInstallStates: Map<String, InstallState>,
) {
    fun isUpdating(key: String): Boolean =
        key == batchPackage || key in installingPackages || externalInstallStates[key]?.isInProgress == true

    /** How far [key]'s download has come, while the batch downloads it and its size is known. */
    fun fraction(key: String): Float? = batch?.takeIf { it.packageName == key }?.download?.fraction

    companion object {
        val None = UpdatingApps(null, null, emptySet(), emptyMap())
    }
}
