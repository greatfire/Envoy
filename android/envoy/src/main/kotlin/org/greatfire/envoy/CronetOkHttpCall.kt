package org.greatfire.envoy

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.AsyncTimeout
import okio.Timeout
import org.chromium.net.CronetEngine
import org.chromium.net.UrlRequest
import org.greatfire.envoy.CronetNetworking.buildRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class CronetOkHttpCall(
        private val client: OkHttpClient,
        private val engine: CronetEngine,
        private val originalRequest: Request) : Call {
    private val mEventListener: EventListener = client.eventListenerFactory.create(this)
    private var mUrlRequest: UrlRequest? = null
    private var mIsExecuted = false
    private var mIsCanceled = false
    private val mTimeout: Timeout = object : AsyncTimeout() {
        override fun timedOut() {
            cancel()
        }
    }

    init {
        mTimeout.timeout(client.callTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun request(): Request {
        return originalRequest
    }

    @Throws(IOException::class)
    override fun execute(): Response {
        synchronized(this) {
            check(!mIsExecuted) { "Already Executed" }
            mIsExecuted = true
        }
        mEventListener.callStart(this)
        Log.d("CronetOkHttpCall", "execute request with no cronet engine")
        val callback = CronetUrlRequestCallback(originalRequest, this, mEventListener, null)
        mUrlRequest = buildRequest(originalRequest, callback)
        mUrlRequest!!.start()
        return callback.blockForResponse()
    }

    override fun enqueue(responseCallback: Callback) {
        synchronized(this) {
            check(!mIsExecuted) { "Already Executed" }
            mIsExecuted = true
        }
        mEventListener.callStart(this)
        try {
            Log.d("CronetOkHttpCall", "enqueue request with no cronet engine")
            val callback = CronetUrlRequestCallback(originalRequest, this, mEventListener, responseCallback)
            mUrlRequest = buildRequest(originalRequest, callback)
            mUrlRequest!!.start()
        } catch (exception: IOException) {
            responseCallback.onFailure(this, exception)
        }
    }

    override fun cancel() {
        if (mUrlRequest != null && !mUrlRequest!!.isDone) {
            mIsCanceled = true
            mUrlRequest!!.cancel()
        }
    }

    override fun isExecuted(): Boolean {
        return mIsExecuted
    }

    override fun isCanceled(): Boolean {
        return mIsCanceled
    }

    override fun timeout(): Timeout {
        return mTimeout
    }

    override fun clone(): Call {
        return CronetOkHttpCall(client, engine, originalRequest)
    }
}