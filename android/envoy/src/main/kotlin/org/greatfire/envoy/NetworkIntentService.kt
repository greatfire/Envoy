package org.greatfire.envoy

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONArray
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList


// IntentService can perform, e.g. ACTION_FETCH_NEW_ITEMS
private const val ACTION_SUBMIT = "org.greatfire.envoy.action.SUBMIT"
private const val ACTION_QUERY = "org.greatfire.envoy.action.QUERY"

private const val EXTRA_PARAM_SUBMIT = "org.greatfire.envoy.extra.PARAM_SUBMIT"

// Defines a custom Intent action
const val BROADCAST_URL_VALIDATION_SUCCEEDED = "org.greatfire.envoy.VALIDATION_SUCCEEDED"
const val BROADCAST_URL_VALIDATION_FAILED = "org.greatfire.envoy.VALIDATION_FAILED"

// Defines the key for the status "extra" in an Intent
const val EXTENDED_DATA_VALID_URLS = "org.greatfire.envoy.VALID_URLS"
const val EXTENDED_DATA_INVALID_URLS = "org.greatfire.envoy.INVALID_URLS"
const val EXTENDED_DATA_VALID_URL = "org.greatfire.envoy.VALID_URL"
const val EXTENDED_DATA_INVALID_URL = "org.greatfire.envoy.INVALID_URL"
const val EXTENDED_DATA_VALID_STRATEGY = "org.greatfire.envoy.VALID_STRATEGY"
const val EXTENDED_DATA_INVALID_STRATEGY = "org.greatfire.envoy.INVALID_STRATEGY"

const val PREF_VALID_URLS = "validUrls"

// Geneva evasion strategies
const val EXTRA_PARAM_STRATEGY = "org.greatfire.envoy.extra.PARAM_STRATEGY"
const val UNMODIFIED_STRATEGY = 0
const val ELEVATED_COUNT_STRATEGY = 1
const val TRUNCATED_RESERVED_STRATEGY = 2
const val MULTI_BYTE_STRATEGY = 3
const val MULTI_BYTE_ELEVATED_COUNT_STRATEGY = 4
const val COMPRESSED_STRATEGY = 5

/**
 * An [IntentService] subclass for handling asynchronous task requests in
 * a service on a separate handler thread.

 */
private const val DEFAULT_USER_AGENT = ("Mozilla/5.0 (X11; Linux x86_64) "
        + "AppleWebKit/537.36 (KHTML, like Gecko) "
        + "Chrome/52.0.2743.82 Safari/537.36")

class NetworkIntentService : IntentService("NetworkIntentService") {
    // https://android.googlesource.com/platform/frameworks/base.git/+/oreo-release/services/core/java/com/android/server/connectivity/NetworkMonitor.java
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NetworkIntentService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NetworkIntentService destroyed")
    }

    private var validUrls = Collections.synchronizedList(mutableListOf<String>())
    private var invalidUrls = Collections.synchronizedList(mutableListOf<String>())

    // Binder given to clients
    private val binder = NetworkBinder()

    inner class NetworkBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): NetworkIntentService = this@NetworkIntentService
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT -> {
                val urls = intent.getStringArrayListExtra(EXTRA_PARAM_SUBMIT)
                var strategy = intent.getIntExtra(EXTRA_PARAM_STRATEGY, UNMODIFIED_STRATEGY)
                // TODO: is there a better way to express this min/max?
                if (strategy < UNMODIFIED_STRATEGY || strategy > COMPRESSED_STRATEGY) {
                    strategy = UNMODIFIED_STRATEGY
                }
                handleActionSubmit(urls, strategy)
            }
            ACTION_QUERY -> {
                handleActionQuery()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }


    // sorted by latency, from the the fastest one
    fun getValidUrls(): List<String> {
        if (validUrls.isNotEmpty()) {
            return validUrls
        }

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val savedValidUrlsStr = sharedPreferences.getString(PREF_VALID_URLS, "[]")
        val savedValidUrls = JSONArray(savedValidUrlsStr)
        // then get it from saved preferences

        val validUrls = ArrayList<String>()
        for (i in 0 until savedValidUrls.length()) {
            validUrls.add(savedValidUrls.getString(i))
        }
        return validUrls
    }

    /**
     * Handle action Submit in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionSubmit(urls: List<String>?,
                                   strategy: Int,
                                   captive_portal_url: String = "https://www.google.com/generate_204") {
        val executor: Executor = Executors.newSingleThreadExecutor()
        urls?.forEachIndexed { index, envoyUrl ->
            val myBuilder = CronetEngine.Builder(applicationContext)
            // added new parameter for geneva strategies
            // TEMP: TEST FIRST URL WITH ALL STRATEGIES
            if (index == 0) {
                for (i in 0..5) {
                    Log.d("GENEVA", "TEST URL " + envoyUrl + " WITH STRATEGY " + i)
                    val cronetEngine: CronetEngine = myBuilder
                        .setEnvoyUrl(envoyUrl)
                        .SetStrategy(i)
                        .setUserAgent(DEFAULT_USER_AGENT).build()

                    val requestBuilder = cronetEngine.newUrlRequestBuilder(
                        captive_portal_url,
                        MyUrlRequestCallback(envoyUrl, i),
                        executor
                    )

                    val request: UrlRequest = requestBuilder.build()
                    request.start()
                    Log.d(TAG, "enqueue url $envoyUrl at index $index to test $captive_portal_url")
                }
            } else {
                Log.d("GENEVA", "TEST URL " + envoyUrl + " WITH STRATEGY " + strategy)
                val cronetEngine: CronetEngine = myBuilder
                    .setEnvoyUrl(envoyUrl)
                    .SetStrategy(strategy)
                    .setUserAgent(DEFAULT_USER_AGENT).build()

                val requestBuilder = cronetEngine.newUrlRequestBuilder(
                    captive_portal_url,
                    MyUrlRequestCallback(envoyUrl, strategy),
                    executor
                )

                val request: UrlRequest = requestBuilder.build()
                request.start()
                Log.d(TAG, "enqueue url $envoyUrl at index $index to test $captive_portal_url")
            }
        }
    }

    /**
     * Handle action Query in the provided background thread with the provided
     * parameters.
     */
    private fun handleActionQuery() {
        val localIntent = Intent(BROADCAST_URL_VALIDATION_SUCCEEDED).apply {
            // Puts the status into the Intent
            putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
        }
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent)
    }

    companion object {
        private const val TAG = "NetworkIntentService"

        /**
         * Starts this service to perform action Submit with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun submit(context: Context, urls: List<String>, strategy: Int) {
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_SUBMIT
                putStringArrayListExtra(EXTRA_PARAM_SUBMIT, ArrayList<String>(urls))
                putExtra(EXTRA_PARAM_STRATEGY, strategy)
            }
            context.startService(intent)
        }

        /**
         * Starts this service to perform action Query with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun enqueueQuery(context: Context) {
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_QUERY
            }
            context.startService(intent)
        }
    }

    inner class MyUrlRequestCallback(private val envoyUrl: String, private val envoyStrategy: Int) : UrlRequest.Callback() {

        override fun onRedirectReceived(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                newLocationUrl: String?
        ) {
            Log.i(TAG, "onRedirectReceived method called.")
            // You should call the request.followRedirect() method to continue
            // processing the request.
            request?.followRedirect()
        }

        override fun onResponseStarted(request: UrlRequest?, info: UrlResponseInfo?) {
            Log.i(TAG, "onResponseStarted method called.")
            // You should call the request.read() method before the request can be
            // further processed. The following instruction provides a ByteBuffer object
            // with a capacity of 102400 bytes to the read() method.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onReadCompleted(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                byteBuffer: ByteBuffer?
        ) {
            Log.i(TAG, "onReadCompleted method called.")
            // You should keep reading the request until there's no more data.
            request?.read(ByteBuffer.allocateDirect(102400))
        }

        override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {
            if (info != null) {
                // only a 200 status code is valid, otherwise return invalid url as in onFailed
                if (info?.httpStatusCode in 200..299) {
                    // logs captive portal url used to validate envoy url
                    Log.i(TAG, "onSucceeded method called for " + info?.url + " / " + envoyStrategy + " -> got " + info?.httpStatusCode + " response code so tested url is valid")
                    this@NetworkIntentService.validUrls.add(envoyUrl)

                    // store valid urls in preferences
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    val json = JSONArray(this@NetworkIntentService.validUrls)
                    editor.putString(PREF_VALID_URLS, json.toString())
                    editor.apply()

                    val localIntent = Intent(BROADCAST_URL_VALIDATION_SUCCEEDED).apply {
                        // puts the validation status into the intent
                        putStringArrayListExtra(EXTENDED_DATA_VALID_URLS, ArrayList(validUrls))
                        putExtra(EXTENDED_DATA_VALID_URL, envoyUrl)
                        putExtra(EXTENDED_DATA_VALID_STRATEGY, envoyStrategy)
                    }
                    LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
                } else {
                    // logs captive portal url used to validate envoy url
                    Log.e(TAG, "onSucceeded method called for " + info?.url + " / " + envoyStrategy + " -> got " + info?.httpStatusCode + " response code so tested url is invalid")
                    this@NetworkIntentService.invalidUrls.add(envoyUrl)

                    val localIntent = Intent(BROADCAST_URL_VALIDATION_FAILED).apply {
                        // puts the validation status into the intent
                        putStringArrayListExtra(EXTENDED_DATA_INVALID_URLS, ArrayList(invalidUrls))
                        putExtra(EXTENDED_DATA_INVALID_URL, envoyUrl)
                        putExtra(EXTENDED_DATA_INVALID_STRATEGY, envoyStrategy)
                    }
                    LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
                }
            } else {
                Log.i(TAG, "onSucceeded method called but UrlResponseInfo was null")
            }
        }

        override fun onFailed(
                request: UrlRequest?,
                info: UrlResponseInfo?,
                error: CronetException?
        ) {
            // logs captive portal url used to validate envoy url
            Log.e(TAG, "onFailed method called for " + info?.url + " / " + envoyStrategy + " -> " + error?.message)
            // broadcast intent with invalid urls so application can handle errors
            this@NetworkIntentService.invalidUrls.add(envoyUrl)
            val localIntent = Intent(BROADCAST_URL_VALIDATION_FAILED).apply {
                // puts the validation status into the intent
                putStringArrayListExtra(EXTENDED_DATA_INVALID_URLS, ArrayList(invalidUrls))
                putExtra(EXTENDED_DATA_INVALID_URL, envoyUrl)
                putExtra(EXTENDED_DATA_INVALID_STRATEGY, envoyStrategy)
            }
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
        }
    }
}



