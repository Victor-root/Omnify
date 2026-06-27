package com.looker.droidify.external

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's tracked external app sources as a JSON file in the app's storage. Mirrors
 * [com.looker.droidify.datastore.CustomButtonRepository] so we avoid a Room schema migration for
 * what is a short, user-managed list.
 */
@Singleton
class ExternalAppRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val mutex = Mutex()
    private var isLoaded = false
    private val _apps = MutableStateFlow<List<ExternalApp>>(emptyList())

    val apps: Flow<List<ExternalApp>> = flow {
        ensureLoaded()
        emitAll(_apps)
    }

    suspend fun getApps(): List<ExternalApp> {
        ensureLoaded()
        return _apps.value
    }

    /** Adds [app] unless an entry with the same key is already tracked. */
    suspend fun addApp(app: ExternalApp) {
        mutex.withLock {
            ensureLoadedInternal()
            if (_apps.value.any { it.key == app.key }) return@withLock
            val updated = _apps.value + app
            saveToFile(updated)
            _apps.value = updated
        }
    }

    /** Replaces the tracked app sharing [ExternalApp.key], or adds it when absent. */
    suspend fun upsertApp(app: ExternalApp) {
        mutex.withLock {
            ensureLoadedInternal()
            val updated = if (_apps.value.any { it.key == app.key }) {
                _apps.value.map { if (it.key == app.key) app else it }
            } else {
                _apps.value + app
            }
            saveToFile(updated)
            _apps.value = updated
        }
    }

    suspend fun removeApp(key: String) {
        mutex.withLock {
            ensureLoadedInternal()
            val updated = _apps.value.filter { it.key != key }
            saveToFile(updated)
            _apps.value = updated
        }
    }

    /** Writes the tracked external sources to [uri] as JSON (for the backup feature). */
    suspend fun exportToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureLoaded()
            val jsonString = json.encodeToString(ListSerializer(ExternalApp.serializer()), _apps.value)
            context.contentResolver.openOutputStream(uri)?.use { it.write(jsonString.toByteArray()) }
                ?: throw IllegalStateException("Cannot open output stream")
        }
    }

    /** Merges external sources from a backup [uri], skipping any already tracked (by key). Returns the
     *  number of newly added sources. */
    suspend fun importFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot open input stream")
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val imported = json.decodeFromString(ListSerializer(ExternalApp.serializer()), jsonString)
            mutex.withLock {
                ensureLoadedInternal()
                val existingKeys = _apps.value.map { it.key }.toSet()
                val newApps = imported.filter { it.key !in existingKeys }
                val merged = _apps.value + newApps
                saveToFile(merged)
                _apps.value = merged
                newApps.size
            }
        }
    }

    private suspend fun ensureLoaded() {
        if (!isLoaded) {
            mutex.withLock { ensureLoadedInternal() }
        }
    }

    private suspend fun ensureLoadedInternal() {
        if (!isLoaded) {
            _apps.value = loadFromFile()
            isLoaded = true
        }
    }

    private suspend fun loadFromFile(): List<ExternalApp> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, FILE_NAME)
        // One-time migration: the feature was originally GitHub-only and stored its list in
        // github_apps.json. Its JSON is a compatible subset of ExternalApp (provider defaults to
        // GitHub), so we can read it straight in and re-save under the new name.
        if (!file.exists()) {
            val legacy = File(context.filesDir, LEGACY_FILE_NAME)
            if (legacy.exists()) {
                val migrated = decode(legacy)
                if (migrated.isNotEmpty()) saveToFile(migrated)
                runCatching { legacy.delete() }
                return@withContext migrated
            }
            return@withContext emptyList()
        }
        decode(file)
    }

    private fun decode(file: File): List<ExternalApp> = try {
        json.decodeFromString(ListSerializer(ExternalApp.serializer()), file.readText())
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    private suspend fun saveToFile(apps: List<ExternalApp>) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            file.writeText(json.encodeToString(ListSerializer(ExternalApp.serializer()), apps))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val FILE_NAME = "external_apps.json"
        private const val LEGACY_FILE_NAME = "github_apps.json"
    }
}
