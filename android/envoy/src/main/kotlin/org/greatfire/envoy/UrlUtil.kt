package org.greatfire.envoy

import android.util.Log
import java.net.URI

class UrlUtil {

    companion object {

        private val TAG = "UrlUtil"

        // added for convenience
        @JvmStatic
        fun sanitizeUrl(url: String): String {
            return sanitizeUrl(url, ENVOY_SERVICE_UNKNOWN)
        }

        @JvmStatic
        fun sanitizeUrl(url: String, urlService: String): String {

            var sanitizedString = ""
            var service = urlService

            // special handling for local socks urls
            if (url.startsWith("socks5:")) {
                service = ENVOY_SERVICE_SOCKS
            }

            // attempt to guess service for some unknowns
            if (urlService.equals(ENVOY_SERVICE_UNKNOWN) && url.startsWith("https://")) {
                service = ENVOY_SERVICE_HTTPS
            } else if (urlService.equals(ENVOY_SERVICE_UNKNOWN) && url.startsWith("envoy://")) {
                service = ENVOY_SERVICE_ENVOY
            }

            try {
                if (service.equals(ENVOY_SERVICE_UPDATE)) {
                    // extract number from url
                    val parts = url.split("/")
                    if (parts.size > 1) {
                        sanitizedString = parts[parts.size - 2]
                    }
                } else if (service.equals(ENVOY_SERVICE_DIRECT)
                    || service.equals(ENVOY_SERVICE_HTTPS)
                ) {
                    // extract domain from url
                    sanitizedString = sanitizedString.plus(
                        url.substring(
                            url.indexOf('.') + 1,
                            url.indexOf("/", url.indexOf('.'))
                        )
                    )
                } else if (service.equals(ENVOY_SERVICE_ENVOY)) {
                    // extract domain and ip from queries
                    val uri = URI(url)
                    val rawQuery = uri.rawQuery
                    val queries = rawQuery.split("&")
                    for (i in 0 until queries.size) {
                        val queryParts = queries[i].split("=")
                        if (queryParts[0].equals("url")) {
                            if (!sanitizedString.isNullOrEmpty()) {
                                sanitizedString = sanitizedString.plus(":")
                            }
                            sanitizedString = sanitizedString.plus(
                                queryParts[1].substring(
                                    queryParts[1].indexOf('.') + 1,
                                    queryParts[1].indexOf("%2F", queryParts[1].indexOf('.'))
                                )
                            )
                        } else if (queryParts[0].equals("address")) {
                            if (!sanitizedString.isNullOrEmpty()) {
                                sanitizedString = sanitizedString.plus(":")
                            }
                            sanitizedString = sanitizedString.plus(queryParts[1])
                        }
                    }
                } else if (service.equals(ENVOY_SERVICE_SNOWFLAKE)) {
                    // handles both submitted and local urls for snowflake service
                    // extract domain from queries
                    val uri = URI(url)
                    val rawQuery = uri.rawQuery
                    val queries = rawQuery.split("&")
                    for (i in 0 until queries.size) {
                        val queryParts = queries[i].split("=")
                        if (queryParts[0].equals("tunnel") || queryParts[0].equals("url")) {
                            sanitizedString = sanitizedString.plus(
                                queryParts[1].substring(
                                    queryParts[1].indexOf('.') + 1,
                                    queryParts[1].indexOf("/", queryParts[1].indexOf('.'))
                                )
                            )
                        }
                    }
                } else if (service.equals(ENVOY_SERVICE_SNOWFLAKE)) {
                    // TEMP
                    sanitizedString = url
                } else {
                    // handles v2ray/hysteria/ss and unknown services
                    // if possible, extract prefix and port from url
                    if (url.contains(":")) {
                        val urlParts = url.split(":")
                        sanitizedString = urlParts[0]
                        if (urlParts.size == 3) {
                            // if found, remove queries
                            if (urlParts[2].contains("?")) {
                                sanitizedString = sanitizedString.plus(":" + urlParts[2].split("?")[0])
                            } else {
                                sanitizedString = sanitizedString.plus(":" + urlParts[2])
                            }
                        }
                    } else {
                        sanitizedString = "***"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "got exception while sanitizing url for service " + service + ": " + e.message)
            }

            return sanitizedString
        }

        @JvmStatic
        fun sanitizeUrlList(urls: List<String>, urlServices: List<String>): String {

            var sanitizedString = ""

            // method assumes the elements in each list match up
            if (urls.size != urlServices.size) {
                Log.e(TAG, "url/service list mismatch while sanitizing urls")
                return "***"
            }

            for (i in 0..(urls.size - 1)) {
                Log.d(TAG, "sanitize url " + i + " from batch")
                if (!sanitizedString.isNullOrEmpty()) {
                    sanitizedString = sanitizedString.plus(",")
                }
                sanitizedString = sanitizedString.plus(sanitizeUrl(urls[i], urlServices[i]))
            }

            return sanitizedString
        }

        // added for convenience
        @JvmStatic
        fun sanitizeServiceList(urlServices: List<String>): String {

            var sanitizedString = ""

            for (i in 0..(urlServices.size - 1)) {
                Log.d(TAG, "sanitize service " + i + " from batch")
                if (!sanitizedString.isNullOrEmpty()) {
                    sanitizedString = sanitizedString.plus(",")
                }
                sanitizedString = sanitizedString.plus(urlServices[i])
            }

            return sanitizedString
        }

        @JvmStatic
        fun getServiceFromUrl(url: String): String? {
            if (url.isNotEmpty()) {
                val split = url.split("://")[0]
                return split
            }
            return null
        }

        @JvmStatic
        fun sanitizeUrlList(urls: List<String>): ArrayList<String> {
            val sanitizedUrls = ArrayList<String>()
            urls.map { sanitizedUrls += sanitizeUrl(it) }
            return sanitizedUrls
        }

        @JvmStatic
        // FIXME should n always be 30? 100 char limit, always 3 items?
        fun truncate(s: String, n: Int): String {
            return s.take(n)
        }

        @JvmStatic
        fun truncateUrlList(urls: ArrayList<String>, n: Int): ArrayList<String> {
            val truncatedUrls = ArrayList<String>()
            urls.map { truncatedUrls += it.take(n) }
            return truncatedUrls
        }

        /*
         *  joins an ArrayList<String>, truncating and sanitizing as it goes
         */
        @JvmStatic
        fun joinToSanitizedAndTruncatedString(urls: List<String>, service: String, length: Int): String {
            return urls.joinToString(separator = ",", transform = { truncate(sanitizeUrl(it, service), length) })
        }

        /*
         *  joins an ArrayList<String>, truncating and sanitizing as it goes
         */
        @JvmStatic
        fun joinToSanitizedAndTruncatedString(urls: List<String>, length: Int): String {
            return urls.joinToString(separator = ",", transform = { truncate(sanitizeUrl(it), length) })
        }
    }
}