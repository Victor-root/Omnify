package com.looker.droidify.di

import com.looker.droidify.BuildConfig.BUILD_TYPE
import com.looker.droidify.BuildConfig.VERSION_NAME
import com.looker.droidify.datastore.SettingsRepository
import com.looker.droidify.datastore.model.ProxyPreference
import com.looker.droidify.datastore.model.ProxyType
import com.looker.droidify.network.Downloader
import com.looker.droidify.network.KtorDownloader
import com.looker.droidify.utility.common.log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * How many requests may be in flight to one host at a time.
     *
     * OkHttp's own default is 5, and this client is shared by everything the app fetches: APK
     * downloads, repository indexes, source APIs, and every app icon the image loader pulls. An
     * F-Droid icon and an F-Droid APK are the same host, so a download could be queued behind icons
     * a list happened to be loading, and then share the connection with them once it started. A
     * download is the one request the user is actually waiting on; it should not wait its turn behind
     * artwork. Still bounded, so a repository is never hit with an unreasonable number at once.
     */
    private const val MAX_REQUESTS_PER_HOST = 16

    @Singleton
    @Provides
    fun provideHttpClient(settingsRepository: SettingsRepository): HttpClient {
        val proxyPreference = runBlocking { settingsRepository.getInitial().proxy }
        val engine = OkHttp.create {
            proxy = proxyPreference.toProxy()
            config {
                dispatcher(
                    Dispatcher().apply {
                        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
                    },
                )
            }
        }
        return HttpClient(engine) {
            install(UserAgent) {
                agent = "Droid-ify/${VERSION_NAME}-${BUILD_TYPE}"
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 15_000L
            }
        }
    }

    @Singleton
    @Provides
    fun provideDownloader(
        httpClient: HttpClient,
        @IoDispatcher
        dispatcher: CoroutineDispatcher,
    ): Downloader = KtorDownloader(
        client = httpClient,
        dispatcher = dispatcher,
    )
}

private fun ProxyPreference.toProxy(): Proxy {
    val socketAddress = when (type) {
        ProxyType.DIRECT -> null
        ProxyType.HTTP, ProxyType.SOCKS -> {
            try {
                InetSocketAddress.createUnresolved(host, port)
            } catch (e: IllegalArgumentException) {
                log(e)
                null
            }
        }
    }
    val androidProxyType = when (type) {
        ProxyType.DIRECT -> Proxy.Type.DIRECT
        ProxyType.HTTP -> Proxy.Type.HTTP
        ProxyType.SOCKS -> Proxy.Type.SOCKS
    }
    return socketAddress?.let { Proxy(androidProxyType, it) } ?: Proxy.NO_PROXY
}
