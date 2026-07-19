package com.looker.droidify.external

import android.content.Context
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

    private var isAccountsLoaded = false
    private val _accounts = MutableStateFlow<List<ExternalAccount>>(emptyList())

    val apps: Flow<List<ExternalApp>> = flow {
        ensureLoaded()
        emitAll(_apps)
    }

    /** Tracked whole-account sources (each expands to several [apps]). */
    val accounts: Flow<List<ExternalAccount>> = flow {
        ensureAccountsLoaded()
        emitAll(_accounts)
    }

    suspend fun getApps(): List<ExternalApp> {
        ensureLoaded()
        return _apps.value
    }

    suspend fun getAccounts(): List<ExternalAccount> {
        ensureAccountsLoaded()
        return _accounts.value
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

    /** Adds or replaces several apps in one write (used when an account discovers many repos). */
    suspend fun upsertApps(apps: List<ExternalApp>) {
        if (apps.isEmpty()) return
        mutex.withLock {
            ensureLoadedInternal()
            val byKey = apps.associateBy { it.key }
            val replaced = _apps.value.map { byKey[it.key] ?: it }
            val existingKeys = _apps.value.map { it.key }.toSet()
            val added = apps.filter { it.key !in existingKeys }
            val updated = replaced + added
            saveToFile(updated)
            _apps.value = updated
        }
    }

    /** Removes every app discovered from the account with [accountKey]. */
    suspend fun removeAppsByAccount(accountKey: String) {
        mutex.withLock {
            ensureLoadedInternal()
            val updated = _apps.value.filterNot { it.accountKey == accountKey }
            saveToFile(updated)
            _apps.value = updated
        }
    }

    /** Sets the enabled flag on every app of the account with [accountKey] (cascades the toggle). */
    suspend fun setAccountAppsEnabled(accountKey: String, enabled: Boolean) {
        mutex.withLock {
            ensureLoadedInternal()
            val updated = _apps.value.map {
                if (it.accountKey == accountKey && it.enabled != enabled) it.copy(enabled = enabled) else it
            }
            saveToFile(updated)
            _apps.value = updated
        }
    }

    /** Adds or replaces the account sharing [ExternalAccount.key]. */
    suspend fun upsertAccount(account: ExternalAccount) {
        mutex.withLock {
            ensureAccountsLoadedInternal()
            val updated = if (_accounts.value.any { it.key == account.key }) {
                _accounts.value.map { if (it.key == account.key) account else it }
            } else {
                _accounts.value + account
            }
            saveAccountsToFile(updated)
            _accounts.value = updated
        }
    }

    suspend fun removeAccount(key: String) {
        mutex.withLock {
            ensureAccountsLoadedInternal()
            val updated = _accounts.value.filter { it.key != key }
            saveAccountsToFile(updated)
            _accounts.value = updated
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

    private suspend fun ensureAccountsLoaded() {
        if (!isAccountsLoaded) {
            mutex.withLock { ensureAccountsLoadedInternal() }
        }
    }

    private suspend fun ensureAccountsLoadedInternal() {
        if (!isAccountsLoaded) {
            _accounts.value = loadAccountsFromFile()
            isAccountsLoaded = true
        }
    }

    private suspend fun loadAccountsFromFile(): List<ExternalAccount> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, ACCOUNTS_FILE_NAME)
        if (!file.exists()) return@withContext emptyList()
        try {
            json.decodeFromString(ListSerializer(ExternalAccount.serializer()), file.readText())
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun saveAccountsToFile(accounts: List<ExternalAccount>) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, ACCOUNTS_FILE_NAME)
            file.writeText(json.encodeToString(ListSerializer(ExternalAccount.serializer()), accounts))
        } catch (e: Exception) {
            e.printStackTrace()
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
        private const val ACCOUNTS_FILE_NAME = "external_accounts.json"
    }
}
