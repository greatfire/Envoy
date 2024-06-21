package org.greatfire.envoy

import android.content.Context
import android.util.Log
import okhttp3.Request
import okio.Buffer
import org.chromium.net.CronetEngine
import org.chromium.net.UploadDataProviders
import org.chromium.net.UrlRequest
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.net.URI
import java.net.URL
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/*
import java.security.Provider
import java.security.Security
 */

object CronetNetworking {
    private var mCronetEngine: CronetEngine? = null
    private val mExecutorService = Executors.newSingleThreadExecutor()
    private var mCustomCronetBuilder: CustomCronetBuilder? = null
    private var envoyUrl: String? = null

    private const val TAG = "Envoy"

    fun buildEngine(
        context: Context,
        cacheFolder: String? = null,
        envoyUrl: String? = null,
        strategy: Int = 0,
        cacheSize: Long = 0
    ): CronetEngine {
        var builder = CronetEngine.Builder(context)
            .enableBrotli(true)
            .enableHttp2(true)
            .enableQuic(true)
        if (!cacheFolder.isNullOrEmpty() && cacheSize > 0) {
            val cacheDir = File(context.cacheDir, cacheFolder)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            builder = builder
                .setStoragePath(cacheDir.absolutePath)
                .enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK, cacheSize * 1024 * 1024)
        }
        if (!envoyUrl.isNullOrEmpty()) {
            Log.d(TAG, "building cronet engine with url $envoyUrl")
            this.envoyUrl = envoyUrl
            // TODO: is the envoy url used for something besides headers or can we remove it entirely?
            builder = builder.setEnvoyUrl(envoyUrl)
        } else {
            Log.d(TAG, "building cronet engine with no url")
        }
        if (strategy > 0) {
            builder = builder.SetStrategy(strategy)
        }
        return builder.build()
    }

    @JvmStatic
    fun cronetEngine(): CronetEngine? {
        return mCronetEngine
    }

    //@JvmStatic
    //fun setCustomCronetBuilder(builder: CustomCronetBuilder?) {
    //    customCronetBuilder = builder
    //}

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun initializeCronetEngine(context: Context, envoyUrl: String?, reInitializeIfNeeded: Boolean = false, strategy: Int = 0) {
        Log.d(TAG, "try to initialize cronet engine with url $envoyUrl")
        if (this.mCronetEngine != null && !reInitializeIfNeeded) {
            Log.d(TAG, "cronet engine is initialized already, and reInitializeIfNeeded is $reInitializeIfNeeded")
            return
        }
        if (mCustomCronetBuilder != null) {
            mCronetEngine = mCustomCronetBuilder!!.build(context)
        } else {
            mCronetEngine = buildEngine(
                context = context,
                cacheFolder = "cronet-cache",
                envoyUrl = envoyUrl,
                strategy = strategy,
                cacheSize = 10
            )
            if (mCronetEngine != null) {
                Log.d(TAG, "engine version " + mCronetEngine!!.versionString)
                val factory = mCronetEngine!!.createURLStreamHandlerFactory()
                // https://stackoverflow.com/questions/30267447/seturlstreamhandlerfactory-and-java-lang-error-factory-already-set
                try {
                    // Try doing it the normal way
                    URL.setURLStreamHandlerFactory(factory)
                } catch (e: Error) {
                    // Force it via reflection
                    try {
                        val factoryField: Field = URL::class.java.getDeclaredField("factory")
                        factoryField.isAccessible = true
                        factoryField.set(null, factory)
                    } catch (ex: Exception) {
                        when (ex) {
                            is NoSuchFieldException, is IllegalAccessException ->
                                Log.e(TAG, "Could not access factory field on URL class: {}", e)
                            else -> throw ex
                        }
                    }
                }
            } else {
                Log.e(TAG, "failed to initialize cronet engine")
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?, cronetEngine: CronetEngine, executorService: ExecutorService): UrlRequest {
        val localUrl = envoyUrl
        val url = request.url.toString()
        val requestBuilder = cronetEngine.newUrlRequestBuilder(url, callback, executorService)
        requestBuilder.setHttpMethod(request.method)
        // TODO: is this redundant?
        request.headers.forEach {
            if (it.first.toLowerCase(Locale.ENGLISH) != "accept-encoding") {
                // Log.d(TAG, "add header for url $url: ${it.first}, ${it.second}")
                requestBuilder.addHeader(it.first, it.second)
            }
        }

        // add more headers based on envoy url parameters
        if (!localUrl.isNullOrEmpty() && (localUrl.startsWith("http") && localUrl.startsWith("envoy"))) {
            Log.e(TAG, "parse additional headers from envoy url")
            val headerPrefix = "header_"
            val headerPrefixLength = headerPrefix.length
            val uri: URI = URI(localUrl)
            val rawQuery = uri.rawQuery
            val queries = rawQuery.split("&")
            for (i in 0 until queries.size) {
                val queryParts = queries[i].split("=")
                if (queryParts[0].startsWith(headerPrefix) && queryParts[0].length > headerPrefixLength) {
                    requestBuilder.addHeader(queryParts[0].substring(headerPrefixLength), queryParts[1])
                }
            }

            requestBuilder.addHeader("Url-Orig", request.url.toString())
            requestBuilder.addHeader("Host-Orig", request.url.host)
        } else {
            Log.e(TAG, "no envoy url to parse additional headers from")
        }

        val requestBody = request.body
        if (requestBody != null) {
            val contentType = requestBody.contentType()
            if (contentType != null) {
                requestBuilder.addHeader("Content-Type", contentType.toString())
            }
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            val uploadDataProvider = UploadDataProviders.create(buffer.readByteArray())
            requestBuilder.setUploadDataProvider(uploadDataProvider, executorService)
        }

        return requestBuilder.build()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?, cronetEngine: CronetEngine): UrlRequest {
        return buildRequest(request, callback, cronetEngine, mExecutorService)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun buildRequest(request: Request, callback: UrlRequest.Callback?): UrlRequest {
        if (mCronetEngine == null) {
            Log.d(CronetNetworking.TAG, "build request with no cronet engine (exception?)")
        } else {
            Log.d(CronetNetworking.TAG, "build request with cronet engine")
        }
        return buildRequest(request, callback, mCronetEngine!!, mExecutorService)
    }

    interface CustomCronetBuilder {
        fun build(context: Context?): CronetEngine?
    }

}
