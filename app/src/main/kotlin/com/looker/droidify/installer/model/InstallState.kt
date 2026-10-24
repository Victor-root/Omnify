package com.looker.droidify.installer.model

enum class InstallState { Failed, Pending, Installing, Installed }

/** Handed to the installer and not finished yet: waiting in the queue, or being installed. */
inline val InstallState.isInProgress: Boolean
    get() = this == InstallState.Pending || this == InstallState.Installing
