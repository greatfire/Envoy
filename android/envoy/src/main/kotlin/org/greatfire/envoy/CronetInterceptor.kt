package org.greatfire.envoy

import android.util.Log
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.chromium.net.CronetEngine
import java.io.IOException
import java.net.SocketTimeoutException

class CronetInterceptor(private var mCronetEngine: CronetEngine?) : Interceptor {

    companion object {
        private const val TAG = "Envoy"
    }

    constructor() : this(null)

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        return when {
            mCronetEngine != null -> {
                try {
                    Log.d(TAG, "hit interceptor for " + chain.request().url)
                    proxyToCronet(chain.request(), chain.call(), mCronetEngine!!)
                } catch (se: SocketTimeoutException) {
                    Log.e(TAG, "got socket timeout exception for " + chain.request().url + " skipping interceptor")
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("socket timeout exception")
                        .body("socket timeout exception".toResponseBody(null))
                        .build()
                } catch (e: Exception) {
                    Log.e(TAG, "got other exception for " + chain.request().url + " skipping interceptor")
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("other exception")
                        .body("other exception".toResponseBody(null))
                        .build()                }
            }
            CronetNetworking.cronetEngine() != null -> {
                try {
                    Log.d(TAG, "hit global interceptor for " + chain.request().url)
                    // This will stop later interceptors
                    proxyToCronet(chain.request(), chain.call())
                } catch (se: SocketTimeoutException) {
                    Log.e(TAG, "got socket timeout exception for " + chain.request().url + " skipping global interceptor")
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("socket timeout exception")
                        .body("socket timeout exception".toResponseBody(null))
                        .build()
                } catch (e: Exception) {
                    Log.e(TAG, "got other exception for " + chain.request().url + " skipping global interceptor")
                    return Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("other exception")
                        .body("other exception".toResponseBody(null))
                        .build()                }
            }
            else -> {
                Log.d(TAG, "bypass interceptor for " + chain.request().url)
                chain.proceed(chain.request())
            }
        }
    }

    @Throws(IOException::class)
    private fun proxyToCronet(request: Request, call: Call): Response {
        // eventListener=eventListener, responseCallback=responseCallback
        Log.d(CronetInterceptor.TAG, "proxy to cronet with no cronet engine")
        val callback = CronetUrlRequestCallback(request, call)
        val urlRequest = CronetNetworking.buildRequest(request, callback)
        urlRequest.start()
        return callback.blockForResponse()
    }

    @Throws(IOException::class)
    private fun proxyToCronet(request: Request, call: Call, cronetEngine: CronetEngine): Response {
        Log.d(CronetInterceptor.TAG, "proxy to cronet with cronet engine")
        val callback = CronetUrlRequestCallback(request, call)
        val urlRequest = CronetNetworking.buildRequest(request, callback, cronetEngine)
        urlRequest.start()
        return callback.blockForResponse()
    }
}
