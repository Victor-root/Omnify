package com.looker.droidify.data.local.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.Index
import com.looker.droidify.sync.v2.model.FileV2
import com.looker.droidify.utility.common.CatalogueLanguage

@Entity(
    tableName = "localized_app_name",
    primaryKeys = ["appId", "locale"],
    indices = [Index("appId"), Index("locale")],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["id"],
            childColumns = ["appId"],
            onDelete = CASCADE,
        ),
    ],
)
data class LocalizedAppNameEntity(
    override val appId: Int,
    override val locale: String,
    val name: String,
) : LocalizedEntityData

@Entity(
    tableName = "localized_app_summary",
    primaryKeys = ["appId", "locale"],
    indices = [Index("appId"), Index("locale")],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["id"],
            childColumns = ["appId"],
            onDelete = CASCADE,
        ),
    ],
)
data class LocalizedAppSummaryEntity(
    override val appId: Int,
    override val locale: String,
    val summary: String,
) : LocalizedEntityData

@Entity(
    tableName = "localized_app_description",
    primaryKeys = ["appId", "locale"],
    indices = [Index("appId"), Index("locale")],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["id"],
            childColumns = ["appId"],
            onDelete = CASCADE,
        ),
    ],
)
data class LocalizedAppDescriptionEntity(
    override val appId: Int,
    override val locale: String,
    val description: String,
) : LocalizedEntityData

@Entity(
    tableName = "localized_app_icon",
    primaryKeys = ["appId", "locale"],
    indices = [Index("appId"), Index("locale")],
    foreignKeys = [
        ForeignKey(
            entity = AppEntity::class,
            parentColumns = ["id"],
            childColumns = ["appId"],
            onDelete = CASCADE,
        ),
    ],
)
data class LocalizedAppIconEntity(
    override val appId: Int,
    override val locale: String,
    @Embedded(prefix = "icon_")
    val icon: FileV2,
) : LocalizedEntityData

sealed interface LocalizedEntityData {
    val appId: Int
    val locale: String
}

/** The translation to show for the app language [locale] (see [CatalogueLanguage]), else the English one,
 *  else any. */
fun <T : LocalizedEntityData> List<T>.findLocale(locale: String): T {
    require(isNotEmpty()) { "List of localized data cannot be empty" }
    val language = CatalogueLanguage(locale)
    return mapNotNull { item -> language.rank(item.locale)?.let { item to it } }
        .minWithOrNull(compareBy({ it.second }, { it.first.locale }))?.first
        ?: firstOrNull { it.locale == "en-US" || it.locale == "en" }
        ?: first()
}
