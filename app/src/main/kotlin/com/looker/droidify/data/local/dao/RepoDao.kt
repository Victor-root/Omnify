package com.looker.droidify.data.local.dao

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import com.looker.droidify.data.local.model.CategoryEntity
import com.looker.droidify.data.local.model.LocalizedRepoIconEntity
import com.looker.droidify.data.local.model.MirrorEntity
import com.looker.droidify.data.local.model.RepoEntity
import com.looker.droidify.data.model.CatalogCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {

    @Query("SELECT * FROM repository")
    fun stream(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repository WHERE id = :repoId")
    fun repo(repoId: Int): Flow<RepoEntity?>

    @Query("SELECT * FROM repository WHERE id = :repoId")
    suspend fun getRepo(repoId: Int): RepoEntity?

    @Query("SELECT id, address FROM repository WHERE id IN (:ids)")
    suspend fun getAddressByIds(ids: List<Int>): Map<
        @MapColumn("id")
        Int,
        @MapColumn("address")
        String,
        >

    /**
     * One row per category with its localized display name: the name whose locale matches the user's
     * language ([langPrefix], e.g. "fr%"), else the en-US name, else any English name, else any name.
     * [CatalogCategory.defaultName] stays the English key used for filtering and the icon mapping.
     *
     * Only categories some app is actually in. A category is never deleted from this table (nothing
     * ties it to the repository that declared it), so one a repository has stopped declaring, or that
     * left with the repository itself, would otherwise sit in the list for ever and open on nothing.
     *
     * [CatalogCategory.ownRepo] is true for a category declared by a repository whose address is not
     * one of [shippedAddresses] (Omnify's own, trailing slash trimmed, which is how the repositories
     * list tells the two apart as well), and those come first: the user's own repository brings one
     * category among a hundred, and it is the one they went looking for.
     */
    @Query(
        """
        SELECT category.defaultName AS defaultName,
            COALESCE(
                MAX(CASE WHEN locale LIKE :langPrefix THEN name END),
                MAX(CASE WHEN locale = 'en-US' THEN name END),
                MAX(CASE WHEN locale LIKE 'en%' THEN name END),
                MAX(name)
            ) AS name,
            EXISTS (
                SELECT 1 FROM category_repo_relation
                JOIN repository ON repository.id = category_repo_relation.id
                WHERE category_repo_relation.defaultName = category.defaultName
                    AND RTRIM(repository.address, '/') NOT IN (:shippedAddresses)
            ) AS ownRepo
        FROM category
        WHERE EXISTS (
            SELECT 1 FROM category_app_relation
            WHERE category_app_relation.defaultName = category.defaultName
        )
        GROUP BY category.defaultName
        ORDER BY ownRepo DESC, name COLLATE NOCASE
        """,
    )
    fun categoriesLocalized(langPrefix: String, shippedAddresses: List<String>): Flow<List<CatalogCategory>>

    @Query(
        """
        SELECT category.* FROM category
        JOIN category_repo_relation ON category.defaultName = category_repo_relation.defaultName
        WHERE category_repo_relation.id = :repoId
        """,
    )
    fun categoriesByRepoId(repoId: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM mirror WHERE repoId = :repoId")
    suspend fun mirrors(repoId: Int): List<MirrorEntity>

    @Query("SELECT * FROM mirror")
    fun mirrors(): Flow<List<MirrorEntity>>

    @Query("UPDATE repository SET timestamp = NULL WHERE id = :id")
    suspend fun resetTimestamp(id: Int)

    @Query("DELETE FROM repository WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("SELECT name FROM localized_repo_name WHERE repoId = :id AND (locale = :locale OR locale = \'en-US\')")
    suspend fun name(id: Int, locale: String): String?

    @Query(
        "SELECT description FROM localized_repo_description WHERE repoId = :id AND (locale = :locale OR locale = \'en-US\')",
    )
    suspend fun description(id: Int, locale: String): String?

    @Query("SELECT * FROM localized_repo_icon WHERE repoId = :id AND (locale = :locale OR locale = \'en-US\')")
    suspend fun icon(id: Int, locale: String): LocalizedRepoIconEntity?

    /** Every icon row a repo holds, whatever its locale, so a logo that isn't the one expected can be
     *  compared against what a sync actually stored. Debug trail only (see trailRepoIcon). */
    @Query("SELECT * FROM localized_repo_icon WHERE repoId = :id")
    suspend fun allIcons(id: Int): List<LocalizedRepoIconEntity>
}
