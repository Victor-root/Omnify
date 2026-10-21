package com.looker.droidify.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import java.io.IOException
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

sealed interface NetworkResponse {

    sealed interface Error : NetworkResponse {

        data class ConnectionTimeout(val exception: Exception) : Error

        data class SocketTimeout(val exception: Exception) : Error

        data class IO(val exception: Exception) : Error

        data class Unknown(val exception: Exception) : Error

        data class Http(val statusCode: Int) : Error
    }

    data class Success(
        val statusCode: Int,
        val lastModified: Date?,
        val etag: String?,
    ) : NetworkResponse
}

/** How this response reads in the diagnostic log: a status or the kind of failure, never an error's
 *  message, which can quote an address. */
internal fun NetworkResponse.diagnosticLabel(): String = when (this) {
    is NetworkResponse.Success -> "HTTP $statusCode"
    is NetworkResponse.Error.Http -> "HTTP $statusCode"
    is NetworkResponse.Error.SocketTimeout -> "read timeout"
    is NetworkResponse.Error.ConnectionTimeout -> "connect timeout"
    is NetworkResponse.Error.IO -> "I/O error (${exception::class.simpleName})"
    is NetworkResponse.Error.Unknown -> "error (${exception::class.simpleName})"
}

/** The error a request that threw this ends in. Most specific first: both timeouts are also
 *  [IOException]s. A cancellation is no error of the request's, so it is thrown on. */
internal fun Exception.asNetworkError(): NetworkResponse.Error = when (this) {
    is CancellationException -> throw this
    is SocketTimeoutException -> NetworkResponse.Error.SocketTimeout(this)
    is ConnectTimeoutException -> NetworkResponse.Error.ConnectionTimeout(this)
    is IOException -> NetworkResponse.Error.IO(this)
    else -> NetworkResponse.Error.Unknown(this)
}
