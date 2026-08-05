package com.looker.droidify.data.local.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.PrimaryKey
import com.looker.droidify.data.encryption.Encrypted
import com.looker.droidify.data.encryption.Key
import com.looker.droidify.data.model.Authentication

@Entity(
    tableName = "authentication",
    foreignKeys = [
        ForeignKey(
            entity = RepoEntity::class,
            childColumns = ["repoId"],
            parentColumns = ["id"],
            onDelete = CASCADE,
        ),
    ],
)
class AuthenticationEntity(
    val password: Encrypted,
    val username: String,
    val initializationVector: ByteArray,
    @PrimaryKey
    val repoId: Int,
)

/** Null when the saved password can't be decrypted any more, which reads the same as having no
 *  credentials at all: the repository is synced anonymously and the user can enter them again. See
 *  [Encrypted.decrypt] for when that happens. */
fun AuthenticationEntity.toAuthentication(key: Key): Authentication? =
    password.decrypt(key, initializationVector)?.let { plain ->
        Authentication(password = plain, username = username)
    }
