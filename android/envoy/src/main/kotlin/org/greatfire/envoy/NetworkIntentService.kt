package org.greatfire.envoy

import IEnvoyProxy.IEnvoyProxy
import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileNotFoundException
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.collections.ArrayList

const val SHORT_DELAY = 1000L
const val MEDIUM_DELAY = 5000L
const val LONG_DELAY = 10000L
const val PREF_VALID_URLS = "validUrls"
const val LOCAL_URL_BASE = "socks5://127.0.0.1:"
const val SNOWFLAKE_URL_BASE_1 = "envoy://?url="
const val SNOWFLAKE_URL_BASE_2 = "&socks5=socks5%3A%2F%2F127.0.0.1%3A"
const val MEEK_URL_BASE_1 = SNOWFLAKE_URL_BASE_1
const val MEEK_URL_BASE_2 = SNOWFLAKE_URL_BASE_2

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

    private var submittedUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shuffledUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shuffledHttps = Collections.synchronizedList(mutableListOf<String>())
    private var currentBatch = Collections.synchronizedList(mutableListOf<String>())
    private var currentBatchChecked = Collections.synchronizedList(mutableListOf<String>())
    private var currentServiceChecked = Collections.synchronizedList(mutableListOf<String>())
    private var batchInProgress = false
    private var directUrlCount = 0
    private var validationStart = 0L
    private var additionalUrlSources = Collections.synchronizedList(mutableListOf<String>())

    // currently only a single url is supported for each service but we may support more in the future
    // NOTE: only one url for a service can be tested at a time, because once a service has been started,
    // the current port will be returned for any subsequent start call, even if the config is different
    private var v2rayWsUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2raySrtpUrls = Collections.synchronizedList(mutableListOf<String>())
    private var v2rayWechatUrls = Collections.synchronizedList(mutableListOf<String>())
    private var hysteriaUrls = Collections.synchronizedList(mutableListOf<String>())
    private var shadowsocksUrls = Collections.synchronizedList(mutableListOf<String>())
    private var snowflakeUrls = Collections.synchronizedList(mutableListOf<String>())
    private var meekUrls = Collections.synchronizedList(mutableListOf<String>())
    private var httpsUrls = Collections.synchronizedList(mutableListOf<String>())
    private var additionalUrls = Collections.synchronizedList(mutableListOf<String>())

    // the valid url at index 0 should be the one that was selected to use for setting up cronet
    private var validUrls = Collections.synchronizedList(mutableListOf<String>())
    private var invalidUrls = Collections.synchronizedList(mutableListOf<String>())
    private var blockedUrls = Collections.synchronizedList(mutableListOf<String>())

    // Binder given to clients
    private val binder = NetworkBinder()

    // map urls to their cache and engine for cleanup
    private var cacheCounter = 1
    private val cacheMap = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val cronetMap = Collections.synchronizedMap(mutableMapOf<String, CronetEngine>())

    private val httpPrefixes = Collections.synchronizedList(mutableListOf<String>("https", "http", "envoy"))
    private val supportedPrefixes = Collections.synchronizedList(mutableListOf<String>("v2ws", "v2srtp", "v2wechat", "hysteria", "ss", "meek"))
    private val preferredPrefixes = Collections.synchronizedList(mutableListOf<String>("snowflake"))

    fun manageCurrentBatch(itemToRemove: String?): Int {
        synchronized(currentBatch) {
            if (!itemToRemove.isNullOrEmpty()) {
                currentBatch.remove(itemToRemove)
            }
            return currentBatch.size
        }
    }

    fun checkValidationTime(): Boolean {
        val validationCheck = System.currentTimeMillis()
        if ((validationCheck - validationStart) < TIME_LIMIT) {
            Log.d(TAG, "validation checked at " + validationCheck + ", time limit not yet exceeded: " + (validationCheck - validationStart))
            return true
        } else {
            Log.d(TAG, "validation checked at " + validationCheck + ", time limit has been exceeded: " + (validationCheck - validationStart))
            return false
        }
    }

    fun calculateValidationTime(): Long {
        val validationStop = System.currentTimeMillis()
        Log.d(TAG, "validation calculated at " + validationStop + ", duration: " + (validationStop - validationStart))
        return validationStop - validationStart
    }

    private fun getCauseOfFailure(): String {
        if (!checkValidationTime()
            && (((shuffledUrls.size + shuffledHttps.size) > 0)) || (additionalUrls.size > 0)) {
            // the cause of failure is reported as a timeout only if there were remaining unchecked urls
            return ENVOY_ENDED_TIMEOUT
        } else if (submittedUrls.isNullOrEmpty()) {
            // check whether no urls were submitted because they had all been previously blocked
            if (blockedUrls.isNullOrEmpty()) {
                return ENVOY_ENDED_EMPTY
            } else {
                return ENVOY_ENDED_BLOCKED
            }
        } else if (!invalidUrls.isNullOrEmpty()) {
            return ENVOY_ENDED_FAILED
        } else {
            return ENVOY_ENDED_UNKNOWN
        }
    }

    private fun broadcastValidationFailure() {
        val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_ENDED)
        localIntent.putExtra(ENVOY_DATA_VALIDATION_MS, calculateValidationTime())
        localIntent.putExtra(ENVOY_DATA_VALIDATION_ENDED_CAUSE, getCauseOfFailure())
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastUpdateFailed(url: String, msg: String) {
        Log.e(TAG, "broadcast update failure for url: " + UrlUtil.sanitizeUrl(url, ENVOY_SERVICE_UPDATE))
        val localIntent = Intent(ENVOY_BROADCAST_UPDATE_FAILED).apply {
            putExtra(ENVOY_DATA_UPDATE_URL, url)
            putExtra(ENVOY_DATA_UPDATE_STATUS, msg)
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastUpdateSucceeded(url: String, msg: String, list: List<String>) {
        Log.d(TAG, "broadcast update success for url: " + UrlUtil.sanitizeUrl(url, ENVOY_SERVICE_UPDATE))
        val localIntent = Intent(ENVOY_BROADCAST_UPDATE_SUCCEEDED).apply {
            putExtra(ENVOY_DATA_UPDATE_URL, url)
            putExtra(ENVOY_DATA_UPDATE_STATUS, msg)
            putStringArrayListExtra(ENVOY_DATA_UPDATE_LIST, ArrayList(list))
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    private fun broadcastBatchStatus(status: String) {
        // create local copies to avoid possible concurrent modification exception
        val localBatchList = ArrayList<String>(currentBatchChecked)
        val localServiceList = ArrayList<String>(currentServiceChecked)

        val localIntent = Intent(status).apply {
            putStringArrayListExtra(ENVOY_DATA_URL_LIST, localBatchList)
            putStringArrayListExtra(ENVOY_DATA_SERVICE_LIST, localServiceList)
        }
        LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)
    }

    inner class NetworkBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): NetworkIntentService = this@NetworkIntentService
    }

    override fun onHandleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_SUBMIT -> {
                val urls = intent.getStringArrayListExtra(EXTRA_PARAM_SUBMIT)
                val directUrls = intent.getStringArrayListExtra(EXTRA_PARAM_DIRECT)
                val hysteriaCert = intent.getStringExtra(EXTRA_PARAM_CERT)
                val urlSources = intent.getStringArrayListExtra(EXTRA_PARAM_SOURCES)
                val urlInterval = intent.getIntExtra(EXTRA_PARAM_INTERVAL, 1)
                val urlStart = intent.getIntExtra(EXTRA_PARAM_START, -1)
                val urlEnd = intent.getIntExtra(EXTRA_PARAM_END, -1)
                val firstAttempt = intent.getBooleanExtra(EXTRA_PARAM_FIRST, false)
                handleActionSubmit(urls, directUrls, hysteriaCert, urlSources, urlInterval, urlStart, urlEnd, firstAttempt)
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
    private fun handleActionSubmit(
        urls: List<String>?,
        directUrls: List<String>?,
        hysteriaCert: String?,
        urlSources: List<String>?,
        urlInterval: Int,
        urlStart: Int,
        urlEnd: Int,
        firstAttempt: Boolean
    ) {

        if (firstAttempt) {
            validationStart = System.currentTimeMillis()
            Log.d(TAG, "validation started at " + validationStart)

            // set logging directory
            Log.d(TAG, "set logging directory to " + filesDir.path)
            IEnvoyProxy.setStateLocation(filesDir.path)

            Log.d(TAG, "clear " + additionalUrlSources.size + " previously submitted url sources")
            additionalUrlSources.clear()

            Log.d(TAG, "clear " + additionalUrls.size + " previously submitted additional urls")
            additionalUrls.clear()

            if (!urlSources.isNullOrEmpty()) {
                urlSources.forEach { urlSource ->
                    Log.d(TAG, "found url source: " + UrlUtil.sanitizeUrl(urlSource, ENVOY_SERVICE_UPDATE))
                    additionalUrlSources.add(urlSource)
                }

                // fetch additional urls at startup to collect analytics
                getAdditionalUrls(urlInterval, urlStart, urlEnd)
            }
        } else {
            Log.d(TAG, "additional urls have been submitted, clear " + additionalUrls.size + " cached urls")
            additionalUrls.clear()
        }

        Log.d(TAG, "clear " + submittedUrls.size + " previously submitted urls")
        submittedUrls.clear()

        // observed behavior where this may be called when no urls are submitted and additional urls
        // are submitted immediately. this will cause the check in the receiver to decrement the count
        // of 0 when the direct url result comes back. unclear whether this is a problem.

        Log.d(TAG, "reset previous direct url count")
        directUrlCount = 0

        Log.d(TAG, "clear " + blockedUrls.size + " previously blocked urls")
        blockedUrls.clear()

        if (!directUrls.isNullOrEmpty()) {
            directUrls.forEach { directUrl ->
                Log.d(TAG, "found direct url: " + UrlUtil.sanitizeUrl(directUrl, ENVOY_SERVICE_DIRECT))
                submittedUrls.add(directUrl)
                directUrlCount = directUrlCount + 1
                handleDirectRequest(directUrl, hysteriaCert)
            }
        } else {
            Log.d(TAG, "found no direct urls to test")
        }

        val urlsToSubmit = mutableListOf<String>()
        if (!urls.isNullOrEmpty()) {
            urls.forEach { url ->
                if (shouldSubmitUrl(url)) {
                    urlsToSubmit.add(url)
                } else {
                    blockedUrls.add(url)
                }
            }
        }

        if (urlsToSubmit.isNullOrEmpty()) {
            if (firstAttempt && !additionalUrls.isNullOrEmpty()) {
                // if first attempt and additional urls available, submit additional urls
                submitAdditionalUrls(hysteriaCert)
            } else {
                Log.w(TAG, "no urls found to submit, cannot continue")
                broadcastValidationFailure()
            }
        } else {

            // set aside certain urls to test first
            var preferredUrls = ArrayList<String>()

            urlsToSubmit.forEach() { url ->
                val parts = url.split(":")
                val prefix = parts[0]
                if (url.contains("test")) {
                    // no-op
                } else if (httpPrefixes.contains(prefix)) {
                    shuffledHttps.add(url)
                } else if (supportedPrefixes.contains(prefix)) {
                    shuffledUrls.add(url)
                } else if (preferredPrefixes.contains(prefix)) {
                    preferredUrls.add(url)
                } else {
                    Log.w(TAG, "found url with unsupported prefix: " + prefix)
                }
            }

            Log.d(TAG, "shuffle " + (shuffledHttps.size + shuffledUrls.size) + " submitted urls")
            Collections.shuffle(shuffledHttps)
            Collections.shuffle(shuffledUrls)

            Log.d(TAG, "insert " + preferredUrls.size + " preferred urls")
            preferredUrls.forEach() { url ->
                shuffledUrls.add(0, url)
            }

            handleBatch(hysteriaCert)
        }
    }

    private fun shouldSubmitUrl(url: String): Boolean {

        // disable this feature for debugging
        if (BuildConfig.BUILD_TYPE == "debug") {
            Log.d(TAG, "debug build, ignore time limit and submit")
            return true
        }

        val currentTime = System.currentTimeMillis()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val failureTime = preferences.getLong(url + TIME_SUFFIX, 0)
        val failureCount = preferences.getInt(url + COUNT_SUFFIX, 0)

        val sanitizedUrl = UrlUtil.sanitizeUrl(url)

        if ((failureCount in 1..3 && currentTime - failureTime < ONE_HOUR_MS * failureCount)
            || (failureCount == 4 && currentTime - failureTime < ONE_DAY_MS)
            || (failureCount >= 5 && currentTime - failureTime < ONE_WEEK_MS)) {
            Log.d(TAG, "time limit has not expired for url(" + failureTime + "), do not submit: " + sanitizedUrl)
            return false
        } else {
            Log.d(TAG, "time limit expired for url(" + failureTime + "), submit again: " + sanitizedUrl)
            return true
        }
    }

    private fun handleBatch(hysteriaCert: String?,
                            captive_portal_url: String = "https://www.google.com/generate_204") {

        // under certain circumstances this can be called multiple times when a single batch is completed
        if (batchInProgress) {
            Log.d(TAG, "batch already in progress")
            return
        } else {
            batchInProgress = true
            Log.d(TAG, "start new batch")
        }

        val batchSize: Int = (2..5).random()

        var max = shuffledUrls.size + shuffledHttps.size
        if (max > batchSize) {
            max = batchSize
        }

        currentBatch.clear()
        currentBatchChecked.clear()
        currentServiceChecked.clear()

        var alternativeIncluded = false

        repeat(max) { index ->
            if (shuffledUrls.isNullOrEmpty() || (alternativeIncluded && !shuffledHttps.isNullOrEmpty())) {
                Log.d(TAG, "add " + (index + 1) + " http url out of " + max + " to batch: " + UrlUtil.sanitizeUrl(shuffledHttps[0]))
                currentBatch.add(shuffledHttps[0])
                shuffledHttps.removeAt(0)
            } else {
                Log.d(TAG, "add " + (index + 1) + " non-http url out of " + max + " to batch: " + UrlUtil.sanitizeUrl(shuffledUrls[0]))
                currentBatch.add(shuffledUrls[0])
                shuffledUrls.removeAt(0)
                alternativeIncluded = true
            }
        }

        Collections.shuffle(currentBatch)
        // implementing this as an interator seemed to cause a concurrent access exceptin
        var index = currentBatch.size
        while (index > 0) {
            index = index - 1
            var envoyUrl = currentBatch.get(index)
            submittedUrls.add(envoyUrl)

            // generate cache directories here where it's synchronized
            // increment counter after allocating so we don't retry numbers
            var cacheDir: File
            do {
                cacheDir = File(applicationContext.cacheDir, "cache_" + cacheCounter)
                Log.d(TAG, "cache setup, check directory: " + cacheDir.absolutePath)
                cacheCounter = cacheCounter + 1
            } while (cacheDir.exists())
            cacheMap.put(envoyUrl, cacheDir.name)
            Log.d(TAG, "cache setup, create directory: " + cacheDir.absolutePath)
            cacheDir.mkdirs()

            if (envoyUrl.startsWith("v2ws://")) {
                Log.d(TAG, "found v2ray url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_V2WS))
                handleV2rayWsSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("v2srtp://")) {
                Log.d(TAG, "found v2ray url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_V2SRTP))
                handleV2raySrtpSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("v2wechat://")) {
                Log.d(TAG, "found v2ray url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_V2WECHAT))
                handleV2rayWechatSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("hysteria://")) {
                Log.d(TAG, "found hysteria url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_HYSTERIA))
                handleHysteriaSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("ss://")) {
                Log.d(TAG, "found ss url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_SS))
                handleShadowsocksSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("snowflake://")) {
                Log.d(TAG, "found snowflake url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_SNOWFLAKE));
                handleSnowflakeSubmit(envoyUrl, captive_portal_url, hysteriaCert);
            } else if (envoyUrl.startsWith("meek://")) {
                Log.d(TAG, "found meek url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_MEEK));
                handleMeekSubmit(envoyUrl, captive_portal_url, hysteriaCert);
            } else if (envoyUrl.startsWith("http")) {
                Log.d(TAG, "found http url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_HTTPS))
                handleHttpsSubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else if (envoyUrl.startsWith("envoy")) {
                Log.d(TAG, "found envoy url: " + UrlUtil.sanitizeUrl(envoyUrl, ENVOY_SERVICE_ENVOY))
                handleEnvoySubmit(envoyUrl, captive_portal_url, hysteriaCert)
            } else {
                // prefix check should handle this but if not, batch count may not add up
                Log.w(TAG, "found unsupported url: " + UrlUtil.sanitizeUrl(envoyUrl))
            }
        }

        Log.d(TAG, "batch is finished")
        batchInProgress = false
    }

    private fun handleHttpsSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        // nothing to parse at this time, leave url in string format

        httpsUrls.add(url)
        Log.d(TAG, "submit http(s) url")
        handleRequest(url, url, ENVOY_SERVICE_HTTPS, captive_portal_url, hysteriaCert)
    }

    private fun handleEnvoySubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        // nothing to parse at this time, leave url in string format

        httpsUrls.add(url)
        Log.d(TAG, "submit envoy url")
        handleRequest(url, url, ENVOY_SERVICE_ENVOY, captive_portal_url, hysteriaCert)
    }

    private fun handleShadowsocksSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        // nothing to parse at this time, leave url in string format

        // start shadowsocks service
        val shadowsocksIntent = Intent(this, ShadowsocksService::class.java)
        shadowsocksIntent.putExtra("org.greatfire.envoy.START_SS_LOCAL", url)
        ContextCompat.startForegroundService(applicationContext, shadowsocksIntent)
        Log.d(TAG, "shadowsocks service started at " + LOCAL_URL_BASE + 1080)

        shadowsocksUrls.add(LOCAL_URL_BASE + 1080)

        // attempt to use fixed delay rather than broadcast receiver
        Log.d(TAG, "submit url after a short delay for starting shadowsocks")
        ioScope.launch() {
            Log.d(TAG, "start shadowsocks delay")
            delay(MEDIUM_DELAY)
            Log.d(TAG, "end shadowsocks delay")
            handleRequest(
                url,
                LOCAL_URL_BASE + 1080,
                ENVOY_SERVICE_SS,
                captive_portal_url,
                hysteriaCert
            )
        }
    }

    private fun handleHysteriaSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {
        val uri = URI(url)
        var hystKey = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("obfs")) {
                hystKey = queryParts[1]
            }
        }

        // start hysteria service
        if (hystKey.isNullOrEmpty() || hysteriaCert.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for hysteria service are missing")
        } else {
            val hystCertParts = hysteriaCert.split(",")
            var hystCert = "-----BEGIN CERTIFICATE-----\n"
            for (i in 0 until hystCertParts.size) {
                hystCert = hystCert + hystCertParts[i] + "\n"
            }
            hystCert = hystCert + "-----END CERTIFICATE-----"
            val hysteriaPort =
                IEnvoyProxy.startHysteria(uri.host + ":" + uri.port, hystKey, hystCert)
            Log.d(TAG, "hysteria service started at " + LOCAL_URL_BASE + hysteriaPort)

            hysteriaUrls.add(LOCAL_URL_BASE + hysteriaPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting hysteria")
            ioScope.launch() {
                Log.d(TAG, "start hysteria delay")
                delay(MEDIUM_DELAY)
                Log.d(TAG, "end hysteria delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + hysteriaPort,
                    ENVOY_SERVICE_HYSTERIA,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2rayWsSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        val uri = URI(url)
        var path = ""
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("path")) {
                path = "/" + queryParts[1]
            } else if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }

        // start v2ray websocket service
        if (path.isNullOrEmpty() || id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray websocket service are missing")
        } else {
            val v2wsPort = IEnvoyProxy.startV2RayWs(uri.host, "" + uri.port, path, id)
            Log.d(TAG, "v2ray websocket service started at " + LOCAL_URL_BASE + v2wsPort)

            v2rayWsUrls.add(LOCAL_URL_BASE + v2wsPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray websocket")
            ioScope.launch() {
                Log.d(TAG, "start v2ray websocket delay")
                delay(MEDIUM_DELAY)
                Log.d(TAG, "end v2ray websocket delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2wsPort,
                    ENVOY_SERVICE_V2WS,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2raySrtpSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        val uri = URI(url)
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }

        // start v2ray srtp service
        if (id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray srtp service are missing")
        } else {
            val v2srtpPort = IEnvoyProxy.startV2raySrtp(uri.host, "" + uri.port, id)
            Log.d(TAG, "v2ray srtp service started at " + LOCAL_URL_BASE + v2srtpPort)

            v2raySrtpUrls.add(LOCAL_URL_BASE + v2srtpPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray srtp")
            ioScope.launch() {
                Log.d(TAG, "start v2ray srtp delay")
                delay(MEDIUM_DELAY)
                Log.d(TAG, "end v2ray srtp delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2srtpPort,
                    ENVOY_SERVICE_V2SRTP,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleV2rayWechatSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        val uri = URI(url)
        var id = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("id")) {
                id = queryParts[1]
            }
        }
        // start v2ray wechat service
        if (id.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for v2ray wechat service are missing")
        } else {
            val v2wechatPort = IEnvoyProxy.startV2RayWechat(uri.host, "" + uri.port, id)
            Log.d(TAG, "v2ray wechat service started at " + LOCAL_URL_BASE + v2wechatPort)

            v2rayWechatUrls.add(LOCAL_URL_BASE + v2wechatPort)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting v2ray wechat")
            ioScope.launch() {
                Log.d(TAG, "start v2ray wechat delay")
                delay(MEDIUM_DELAY)
                Log.d(TAG, "end v2ray wechat delay")
                handleRequest(
                    url,
                    LOCAL_URL_BASE + v2wechatPort,
                    ENVOY_SERVICE_V2WECHAT,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleSnowflakeSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        // borrowed from the list Tor Browser uses: https://gitlab.torproject.org/tpo/applications/tor-browser-build/-/merge_requests/617/diffs
        // too many is a problem, both of these seem to work for us
        var ice = "stun:stun.l.google.com:19302,stun:stun.sonetel.com:3478,stun:stun.voipgate.com:3478"
        // additional hardcoded snowflake parameters
        val logFile = ""
        val logToStateDir = false
        val keepLocalAddresses = true
        val unsafeLogging = true
        val maxPeers = 1L  // With >1 our test client can never make more than one

        val uri = URI(url)
        var brokerUrl = ""
        var ampCache = ""
        var front = ""
        var tunnelUrl = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("broker")) {
                brokerUrl = URLDecoder.decode(queryParts[1], "UTF-8")
            } else if (queryParts[0].equals("ampCache")) {
                ampCache = URLDecoder.decode(queryParts[1], "UTF-8")
            } else if (queryParts[0].equals("front")) {
                front = queryParts[1]
                // if needed, generate randomized host name prefix
                if (front.startsWith('.')) {
                    front = randomString().plus(front)
                    Log.d(TAG, "front updated with random prefix: " + front)
                } else {
                    Log.d(TAG, "front included as-is: " + front)
                }
            } else if (queryParts[0].equals("tunnel")) {
                // this is purposfully not decocded to add to an Envoy URL
                tunnelUrl = queryParts[1]
            } else if (queryParts[0].equals("ice")) {
                // allow overriding the STUN server list
                ice = URLDecoder.decode(queryParts[1], "UTF-8")
            }
        }
        // start snowflake service
        if (brokerUrl.isNullOrEmpty() || tunnelUrl.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for snowflake service are missing")
        } else {
            val snowflakePort = IEnvoyProxy.startSnowflake(
                ice, brokerUrl, front, ampCache, logFile, logToStateDir, keepLocalAddresses,
                unsafeLogging, maxPeers)
            val urlString = SNOWFLAKE_URL_BASE_1 + tunnelUrl + SNOWFLAKE_URL_BASE_2 + snowflakePort

            Log.d(TAG, "snowflake service started at " + urlString)

            snowflakeUrls.add(urlString)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting snowflake")
            ioScope.launch() {
                Log.d(TAG, "start snowflake delay")
                delay(SHORT_DELAY)
                Log.d(TAG, "end snowflake delay")
                handleRequest(
                    url,
                    urlString,
                    ENVOY_SERVICE_SNOWFLAKE,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun handleMeekSubmit(
        url: String,
        captive_portal_url: String,
        hysteriaCert: String?
    ) {

        val uri = URI(url)
        var meekUrl = ""
        var meekFront = ""
        var meekTunnel = ""
        val rawQuery = uri.rawQuery
        val queries = rawQuery.split("&")
        for (i in 0 until queries.size) {
            val queryParts = queries[i].split("=")
            if (queryParts[0].equals("url")) {
                meekUrl = URLDecoder.decode(queryParts[1], "UTF-8")
            } else if (queryParts[0].equals("front")) {
                meekFront = URLDecoder.decode(queryParts[1], "UTF-8")
                // if needed, generate randomized host name prefix
                if (meekFront.startsWith('.')) {
                    meekFront = randomString().plus(meekFront)
                    Log.d(TAG, "front updated with random prefix: " + meekFront)
                } else {
                    Log.d(TAG, "front included as-is: " + meekFront)
                }
            } else if (queryParts[0].equals("tunnel")) {
                // this is purposfully not decocded to add to an Envoy URL
                meekTunnel = queryParts[1]
            }
        }
        // start meek service
        if (meekUrl.isNullOrEmpty() || meekFront.isNullOrEmpty() || meekTunnel.isNullOrEmpty()) {
            Log.e(TAG, "some arguments required for meek service are missing")
        } else {

            // set the login params
            val meekUserString = "url=" + meekUrl + ";front=" + meekFront
            val nullCharString = "" + Char.MIN_VALUE

            // additional hardcoded meek parameters
            val logLevel = "ERROR"
            val enableLogging = false
            val unsafeLogging = false

            val meekPort = IEnvoyProxy.startMeek(meekUserString, nullCharString, logLevel, enableLogging, unsafeLogging)
            val urlString = MEEK_URL_BASE_1 + meekTunnel + MEEK_URL_BASE_2 + meekPort

            Log.d(TAG, "meek service started at " + urlString)

            meekUrls.add(urlString)

            // method returns port immediately but service is not ready immediately
            Log.d(TAG, "submit url after a short delay for starting meek")
            ioScope.launch() {
                Log.d(TAG, "start meek delay")
                delay(SHORT_DELAY)
                Log.d(TAG, "end meek delay")
                handleRequest(
                    url,
                    urlString,
                    ENVOY_SERVICE_MEEK,
                    captive_portal_url,
                    hysteriaCert
                )
            }
        }
    }

    private fun randomString(): String {
        val length: Int = (4..16).random()
        var randomstring: String = ""
        while (randomstring.length < length) {
            randomstring = randomstring.plus(('a'..'z').random())
        }
        return randomstring
    }

    // test direct connection to avoid using proxy resources when not required
    private fun handleDirectRequest(
        directUrl: String,
        hysteriaCert: String?
    ) {

        Log.d(TAG, "create direct request to " + UrlUtil.sanitizeUrl(directUrl, ENVOY_SERVICE_DIRECT))

        val executor: Executor = Executors.newSingleThreadExecutor()
        val cronetEngine: CronetEngine = CronetNetworking.buildEngine(context = applicationContext)
        val requestBuilder = cronetEngine.newUrlRequestBuilder(
            directUrl,
            MyUrlRequestCallback(
                directUrl,
                directUrl,
                ENVOY_SERVICE_DIRECT,
                hysteriaCert
            ),
            executor
        )
        val request: UrlRequest = requestBuilder.build()
        request.start()
    }

    // TODO: do we just hard code captive portal url or add the default here?

    private fun handleRequest(
        originalUrl: String,
        envoyUrl: String,
        envoyService: String,
        captive_portal_url: String,
        hysteriaCert: String?,
        strategy: Int = 0
    ) {

        Log.d(TAG, "create request to " + captive_portal_url + " for url: " + UrlUtil.sanitizeUrl(envoyUrl, envoyService))

        val sanitizedOriginal = UrlUtil.sanitizeUrl(originalUrl, envoyService)

        if (cacheMap.keys.contains(originalUrl)) {

            Log.d(TAG, "cache setup, found cache directory for " + sanitizedOriginal + " -> " + cacheMap.get(originalUrl))

            try {
                val executor: Executor = Executors.newSingleThreadExecutor()
                val cronetEngine: CronetEngine = CronetNetworking.buildEngine(
                    context = applicationContext,
                    cacheFolder = cacheMap.get(originalUrl),
                    envoyUrl = envoyUrl,
                    strategy = strategy,
                    cacheSize = 1
                )
                val requestBuilder = cronetEngine.newUrlRequestBuilder(
                    captive_portal_url,
                    MyUrlRequestCallback(
                        originalUrl,
                        envoyUrl,
                        envoyService,
                        hysteriaCert
                    ),
                    executor
                )
                val request: UrlRequest = requestBuilder.build()
                request.start()
                Log.d(TAG, "cache setup, cache cronet engine for url " + sanitizedOriginal)
                cronetMap.put(originalUrl, cronetEngine)
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "cache setup, cache directory " + cacheMap.get(originalUrl) + " could not be used")
            }
        } else {
            Log.e(TAG, "cache setup, could not find cache directory for " + sanitizedOriginal)
        }
    }

    private fun handleCleanup(envoyUrl: String, envoyService: String) {

        Log.d(TAG, UrlUtil.sanitizeUrl(envoyUrl, envoyService) + " is redundant or invalid, cleanup and/or stop services if needed")

        if (v2rayWsUrls.contains(envoyUrl)) {
            v2rayWsUrls.remove(envoyUrl)
            if (v2rayWsUrls.isEmpty()) {
                Log.d(TAG, "no v2rayWs urls remaining, stop service")
                IEnvoyProxy.stopV2RayWs()
            } else {
                Log.d(TAG, "" + v2rayWsUrls.size + " v2rayWs urls remaining, service in use")
            }
        } else if (v2raySrtpUrls.contains(envoyUrl)) {
            v2raySrtpUrls.remove(envoyUrl)
            if (v2raySrtpUrls.isEmpty()) {
                Log.d(TAG, "no v2raySrtp urls remaining, stop service")
                IEnvoyProxy.stopV2RaySrtp()
            } else {
                Log.d(TAG, "" + v2raySrtpUrls.size + " v2raySrtp urls remaining, service in use")
            }

        } else if (v2rayWechatUrls.contains(envoyUrl)) {
            v2rayWechatUrls.remove(envoyUrl)
            if (v2rayWechatUrls.isEmpty()) {
                Log.d(TAG, "no v2rayWechat urls remaining, stop service")
                IEnvoyProxy.stopV2RayWechat()
            } else {
                Log.d(TAG, "" + v2rayWechatUrls.size + " v2rayWechat urls remaining, service in use")
            }

        } else if (hysteriaUrls.contains(envoyUrl)) {
            hysteriaUrls.remove(envoyUrl)
            if (hysteriaUrls.isEmpty()) {
                Log.d(TAG, "no hysteria urls remaining, stop service")
                IEnvoyProxy.stopHysteria()
            } else {
                Log.d(TAG, "" + hysteriaUrls.size + " hysteria urls remaining, service in use")
            }

        } else if (shadowsocksUrls.contains(envoyUrl)) {
            shadowsocksUrls.remove(envoyUrl)
            if (shadowsocksUrls.isEmpty()) {
                Log.d(TAG, "no shadowsocks urls remaining, stop service")
                // TODO - how to stop shadowsocks service?
            } else {
                Log.d(TAG, "" + shadowsocksUrls.size + " shadowsocks urls remaining, service in use")
            }

        } else if (httpsUrls.contains(envoyUrl)) {
            httpsUrls.remove(envoyUrl)
            if (httpsUrls.isEmpty()) {
                Log.d(TAG, "no https urls remaining (no service)")
            } else {
                Log.d(TAG, "" + httpsUrls.size + " https urls remaining (no service)")
            }
        } else if (snowflakeUrls.contains(envoyUrl)) {
            snowflakeUrls.remove(envoyUrl)
            if (snowflakeUrls.isEmpty()) {
                Log.d(TAG, "no snowflake urls remaining, stop service")
                IEnvoyProxy.stopSnowflake()
            } else {
                Log.d(TAG, "" + snowflakeUrls.size + " snowflake urls remaining, service in use")
            }
        } else if (meekUrls.contains(envoyUrl)) {
            meekUrls.remove(envoyUrl)
            if (meekUrls.isEmpty()) {
                Log.d(TAG, "no meek urls remaining, stop service")
                // stop lyrebird for both meek/obfs4
                IEnvoyProxy.stopLyrebird()
            } else {
                Log.d(TAG, "" + meekUrls.size + " meek urls remaining, service in use")
            }
        } else {
            Log.d(TAG, "url was not previously cached")
        }
    }

    fun getAdditionalUrls(urlInterval: Int, urlStart: Int, urlEnd: Int) {
        while (additionalUrlSources.isNotEmpty()) {

            Log.d(TAG, "get a list of additional urls")
            val url = URL(additionalUrlSources.removeAt(0))

            var msg = "ok"

            try {
                Log.d(TAG, "open connection: " + UrlUtil.sanitizeUrl(url.toString(), ENVOY_SERVICE_UPDATE))
                val connection = url.openConnection() as HttpURLConnection
                try {
                    Log.d(TAG, "set timeout")
                    connection.connectTimeout = 5000
                    Log.d(TAG, "connect")
                    connection.connect()
                } catch (e: SocketTimeoutException) {
                    msg = "socket timeout when connecting: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                } catch (e: ConnectException) {
                    msg = "connection error: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                } catch (e: Exception) {
                    msg = "unexpected error (" + e.javaClass.canonicalName + ") when connecting: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                }

                try {
                    Log.d(TAG, "open input stream")
                    val input = connection.inputStream
                    if (input != null) {
                        Log.d(TAG, "parse json and extract possible urls")
                        val json = input.bufferedReader().use(BufferedReader::readText)

                        val newUrls = Collections.synchronizedList(mutableListOf<String>())

                        val envoyObject = JSONObject(json)
                        val envoyUrlArray = envoyObject.getJSONArray("envoyUrls")

                        Log.w(TAG, "received json with " + envoyUrlArray.length() + " urls")

                        for (i in 0 until envoyUrlArray.length()) {
                            if (((urlInterval > 1) && ((i % urlInterval) > 0))
                                || ((urlStart >= 0) && (i >= urlStart))
                                || ((urlEnd >= 0) && (i <= urlEnd))
                            ) {
                                Log.d(TAG, "skip url at index " + i)
                                continue
                            }

                            val sanitizedUrl = UrlUtil.sanitizeUrl(envoyUrlArray.getString(i))

                            if (submittedUrls.contains(envoyUrlArray.getString(i))) {
                                Log.d(TAG, "additional url " + sanitizedUrl + " has already been submitted")
                            } else if (additionalUrls.contains(envoyUrlArray.getString(i))) {
                                Log.d(TAG,"additional url " + sanitizedUrl + " was already found")
                            } else {
                                Log.d(TAG, "additional url " + sanitizedUrl + " has not been submitted yet")
                                newUrls.add(envoyUrlArray.getString(i))
                            }
                        }

                        broadcastUpdateSucceeded(url.toString(), msg, newUrls)
                        additionalUrls.addAll(newUrls)
                        continue
                    } else {
                        msg = "response contained no json to parse"
                        Log.e(TAG, msg)
                    }
                } catch (e: SocketTimeoutException) {
                    msg = "socket timeout when getting input: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                } catch (e: FileNotFoundException) {
                    msg = "config file error: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                } catch (e: Exception) {
                    msg = "unexpected error (" + e.javaClass.canonicalName + ") when reading file: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                    Log.e(TAG, msg)
                }
            } catch (e: Error) {
                msg = "connection error: " + UrlUtil.sanitizeError(e, ENVOY_SERVICE_UPDATE)
                Log.e(TAG, msg)
            } catch (e: Exception) {
                msg = "unexpected error (" + e.javaClass.canonicalName + ") when opening connection: " + UrlUtil.sanitizeException(e, ENVOY_SERVICE_UPDATE)
                Log.e(TAG, msg)
            }

            broadcastUpdateFailed(url.toString(), msg)
        }
    }

    fun submitAdditionalUrls(hysteriaCert: String?) {
        if (additionalUrls.isNullOrEmpty()) {
            // this check may be redundant
            Log.w(TAG, "no additional urls to submit")
            broadcastValidationFailure()
        } else {
            Log.d(TAG, "submit " + additionalUrls.size + " additional urls")

            val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_CONTINUED)
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            submitAdditional(this@NetworkIntentService, additionalUrls, hysteriaCert)
        }
    }

    companion object {
        private const val TAG = "NetworkIntentService"

        private const val TIME_LIMIT = 60000
        private const val ONE_HOUR_MS = 3600000
        private const val ONE_DAY_MS = 86400000
        private const val ONE_WEEK_MS = 604800000
        private const val TIME_SUFFIX = "_time"
        private const val COUNT_SUFFIX = "_count"

        val ioScope = CoroutineScope(Dispatchers.IO)
        var dnsttFlag = false

        /**
         * Starts this service to perform action Submit with the given parameters. If
         * the service is already performing a task this action will be queued.
         *
         * @see IntentService
         */
        @JvmStatic
        fun submit(
            context: Context,
            urls: List<String>,
            directUrls: List<String>?,
            hysteriaCert: String?,
            urlSources: List<String>?,
            urlInterval: Int,
            urlStart: Int,
            urlEnd: Int
        ) {
            Log.d(TAG, "jvm submit")
            processSubmit(context, urls, directUrls, hysteriaCert, urlSources, urlInterval, urlStart, urlEnd, true)
        }

        @JvmStatic
        fun submit(context: Context, urls: List<String>) {
            Log.d(TAG, "backwards compatible submit")
            processSubmit(context, urls, null, null, null, 1, -1, -1, true)
        }

        // no jvm annotation, not for external use
        fun submitAdditional(context: Context, urls: List<String>, hysteriaCert: String?) {
            Log.d(TAG, "dnstt submit")
            processSubmit(context, urls, null, hysteriaCert, null, 1, -1, -1, false)
        }

        // no jvm annotation, not for external use
        fun processSubmit(
            context: Context,
            urls: List<String>,
            directUrls: List<String>?,
            hysteriaCert: String?,
            urlSources: List<String>?,
            urlInterval: Int,
            urlStart: Int,
            urlEnd: Int,
            firstAttempt: Boolean
        ) {
            Log.d(TAG, "process submit")
            val intent = Intent(context, NetworkIntentService::class.java).apply {
                action = ACTION_SUBMIT
                putStringArrayListExtra(EXTRA_PARAM_SUBMIT, ArrayList<String>(urls))
                if (!directUrls.isNullOrEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_PARAM_DIRECT,
                        directUrls as java.util.ArrayList<String>?
                    )
                }
                if (!hysteriaCert.isNullOrEmpty()) {
                    putExtra(EXTRA_PARAM_CERT, hysteriaCert)
                }
                if (!urlSources.isNullOrEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_PARAM_SOURCES,
                        urlSources as java.util.ArrayList<String>?
                    )
                }
                if (urlInterval > 1) {
                    putExtra(EXTRA_PARAM_INTERVAL, urlInterval)
                }
                if (urlStart >= 0) {
                    putExtra(EXTRA_PARAM_START, urlStart)
                }
                if (urlEnd >= 0) {
                    putExtra(EXTRA_PARAM_END, urlEnd)
                }
                putExtra(EXTRA_PARAM_FIRST, firstAttempt)
            }
            context.startService(intent)
        }
    }

    inner class MyUrlRequestCallback(private val originalUrl: String,
                                     private val envoyUrl: String,
                                     private val envoyService: String,
                                     private val hysteriaCert: String?) : UrlRequest.Callback() {

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

        // TODO: do we continue to return all urls or can we start cronet here?
        override fun onSucceeded(request: UrlRequest?, info: UrlResponseInfo?) {

            // delay processing of proxy results if results for direct urls have not been received

            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                directUrlCount = directUrlCount - 1
                Log.d(TAG, "got direct url result, " + directUrlCount + " direct urls remaining")
                processSuccess(request, info)
            } else if (directUrlCount == 0) {
                Log.d(TAG, "skip onSucceeded delay")
                processSuccess(request, info)
            } else {
                ioScope.launch() {
                    Log.d(TAG, "start onSucceeded delay, " + directUrlCount + " direct urls remaining")
                    delay(MEDIUM_DELAY)
                    Log.d(TAG, "end onSucceeded delay")
                    processSuccess(request, info)
                }
            }
        }

        private fun processSuccess(request: UrlRequest?, info: UrlResponseInfo?) {

            val sanitizedUrl = UrlUtil.sanitizeUrl(originalUrl, envoyService)

            // update batch
            Log.d(TAG, "batch cleanup, remove valid url: " + sanitizedUrl)
            var batchCount = 0
            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                Log.d(TAG, "direct url " + sanitizedUrl + " was valid, but do not update lists")
                batchCount = manageCurrentBatch(null)
            } else {
                this@NetworkIntentService.currentBatchChecked.add(originalUrl)
                this@NetworkIntentService.currentServiceChecked.add(envoyService)
                batchCount = manageCurrentBatch(originalUrl)
            }

            if (info != null) {

                if (this@NetworkIntentService.validUrls.size > 0) {
                    Log.d(TAG, "got redundant url: " + UrlUtil.sanitizeUrl(envoyUrl, envoyService))
                    handleCleanup(envoyUrl, envoyService)
                }

                // only a 204 status code is valid, otherwise return invalid url as in onFailed
                if ((envoyService.equals(ENVOY_SERVICE_DIRECT) && info.httpStatusCode == 200)
                    || info.httpStatusCode == 204
                ) {
                    // logs captive portal url used to validate envoy url
                    Log.d(TAG, "onSucceeded method called, got response code " + info.httpStatusCode + " so tested url is valid")
                    this@NetworkIntentService.validUrls.add(envoyUrl)

                    // store valid urls in preferences
                    val sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
                    val editor: SharedPreferences.Editor = sharedPreferences.edit()
                    val json = JSONArray(this@NetworkIntentService.validUrls)
                    editor.putString(PREF_VALID_URLS, json.toString())
                    editor.putInt(originalUrl + COUNT_SUFFIX, 0)
                    editor.apply()

                    val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_SUCCEEDED).apply {
                        // puts the validation status into the intent
                        putExtra(ENVOY_DATA_URL_SUCCEEDED, envoyUrl)
                        putExtra(ENVOY_DATA_SERVICE_SUCCEEDED, envoyService)
                        putExtra(ENVOY_DATA_VALIDATION_MS, calculateValidationTime())
                    }
                    LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

                    // check whether batch is complete
                    if (batchCount > 0) {
                        Log.d(TAG, "" + batchCount + " urls remaining in current batch")
                    } else  {
                        Log.d(TAG, "current batch is empty, but a valid url was already found")

                        broadcastBatchStatus(ENVOY_BROADCAST_BATCH_SUCCEEDED)
                    }
                } else if (info.httpStatusCode in 200..299) {
                    // capturing this separately to troubleshoot redirect issue
                    Log.e(TAG, "onSucceeded method called, got unexpected response code " + info.httpStatusCode + " so tested url is invalid")
                    handleInvalidUrl(batchCount)
                } else {
                    // logs captive portal url used to validate envoy url
                    Log.e(TAG, "onSucceeded method called, got response code " + info.httpStatusCode + " so tested url is invalid")
                    handleInvalidUrl(batchCount)
                }
            } else {
                Log.w(TAG, "onSucceeded method called but UrlResponseInfo was null")
            }

            cacheCleanup()
        }

        override fun onFailed(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            error: CronetException?
        ) {
            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                directUrlCount = directUrlCount - 1
                Log.d(TAG, "got direct url result, " + directUrlCount + " direct urls remaining")
            }
            processFailure(request, info, error)
        }

        fun processFailure(
            request: UrlRequest?,
            info: UrlResponseInfo?,
            error: CronetException?
        ) {

            val sanitizedUrl = UrlUtil.sanitizeUrl(originalUrl, envoyService)

            // update batch
            Log.d(TAG, "batch cleanup, remove invalid url: " + sanitizedUrl)
            var batchCount = 0
            if (envoyService.equals(ENVOY_SERVICE_DIRECT)) {
                Log.d(TAG, "direct url " + sanitizedUrl + " was invalid, but do not update lists")
                batchCount = manageCurrentBatch(null)
            } else {
                this@NetworkIntentService.currentBatchChecked.add(originalUrl)
                this@NetworkIntentService.currentServiceChecked.add(envoyService)
                batchCount = manageCurrentBatch(originalUrl)
            }

            // logs captive portal url used to validate envoy url
            Log.e(TAG, "onFailed method called, got error message " + error?.message)
            handleInvalidUrl(batchCount)

            cacheCleanup()
        }

        private fun cacheCleanup() {

            val sanitizedOriginal = UrlUtil.sanitizeUrl(originalUrl, envoyService)

            val engine = cronetMap.get(originalUrl)
            if (engine == null) {
                Log.w(TAG, "cache cleanup, could not find cached cronet engine for url " + sanitizedOriginal)
            } else {
                Log.d(TAG, "cache cleanup, found cached cronet engine for url " + sanitizedOriginal)
                engine.shutdown()
                Log.d(TAG, "cache cleanup, shut down cached cronet engine for url " + sanitizedOriginal)
            }

            val cacheName = cacheMap.get(originalUrl)
            if (cacheName == null) {
                Log.w(TAG, "cache cleanup, could not find cached directory for url " + sanitizedOriginal)
                return
            } else {
                Log.d(TAG, "cache cleanup, found cached directory " + cacheName + " for url " + sanitizedOriginal)
            }

            // clean up http cache dir
            val cacheDir = File(applicationContext.cacheDir, cacheName)

            // clean up old cached data
            cacheDir.let {
                if (it.exists()) {
                    val files = it.listFiles()
                    if (files != null) {
                        for (file in files) {
                            if (file.deleteRecursively()) {
                                Log.d(TAG, "cache cleanup, delete " + file.absolutePath)
                            } else {
                                Log.e(TAG, "cache cleanup, failed to delete " + file.absolutePath)
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "cache cleanup, delete " + cacheDir.absolutePath)
            cacheDir.deleteRecursively()
        }

        fun handleInvalidUrl(batchCount: Int) {
            handleCleanup(envoyUrl, envoyService)

            // store failed urls so they are not attempted again
            val currentTime = System.currentTimeMillis()
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this@NetworkIntentService)
            val failureCount = sharedPreferences.getInt(originalUrl + COUNT_SUFFIX, 0)
            val editor: SharedPreferences.Editor = sharedPreferences.edit()
            editor.putLong(originalUrl + TIME_SUFFIX, currentTime)
            editor.putInt(originalUrl + COUNT_SUFFIX, failureCount + 1)
            editor.apply()

            // broadcast intent with invalid urls so application can handle errors
            this@NetworkIntentService.invalidUrls.add(envoyUrl)
            val localIntent = Intent(ENVOY_BROADCAST_VALIDATION_FAILED).apply {
                // puts the validation status into the intent
                putExtra(ENVOY_DATA_URL_FAILED, envoyUrl)
                putExtra(ENVOY_DATA_SERVICE_FAILED, envoyService)
            }
            LocalBroadcastManager.getInstance(this@NetworkIntentService).sendBroadcast(localIntent)

            if (batchCount > 0) {
                // check whether current batch of urls have failed
                Log.d(TAG, "" + batchCount + " urls remaining in current batch")
            } else if (this@NetworkIntentService.validUrls.size > 0) {
                // a valid url was found, do not continue
                Log.d(TAG, "current batch is empty, but a valid url was previously found")

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_SUCCEEDED)
            } else if ((this@NetworkIntentService.shuffledUrls.size + this@NetworkIntentService.shuffledHttps.size) > 0) {
                // check whether all submitted urls have failed
                Log.d(TAG, "current batch is empty, " + (this@NetworkIntentService.shuffledUrls.size + this@NetworkIntentService.shuffledHttps.size) + " submitted urls remaining")

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_FAILED)

                if (checkValidationTime()) {
                    // time remaining, continue
                    handleBatch(hysteriaCert)
                } else {
                    // time expired, do not continue
                    Log.w(TAG, "time expired, cannot continue with next batch")
                    broadcastValidationFailure()
                    return
                }
            } else {

                broadcastBatchStatus(ENVOY_BROADCAST_BATCH_FAILED)

                if (this@NetworkIntentService.additionalUrls.isNullOrEmpty()) {
                    // all available urls have failed, broadcast validation time
                    Log.w(TAG, "all available urls have failed, cannot continue")
                    broadcastValidationFailure()
                    return
                } else if (checkValidationTime()) {
                    // all urls in original submission have failed, submit additional urls for validation
                    Log.w(TAG, "all urls submitted have failed, validate additional urls")
                    submitAdditionalUrls(hysteriaCert)
                } else {
                    // time expired, do not continue
                    Log.w(TAG, "time expired, cannot continue with additional urls")
                    broadcastValidationFailure()
                    return
                }
            }
        }
    }
}
