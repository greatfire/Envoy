package org.greatfire.envoy

// Defines submit url action
const val ACTION_SUBMIT = "org.greatfire.envoy.action.SUBMIT"

// Defines arguments for submit url action
const val EXTRA_PARAM_SUBMIT = "org.greatfire.envoy.extra.PARAM_SUBMIT"
const val EXTRA_PARAM_DIRECT = "org.greatfire.envoy.extra.PARAM_DIRECT"
const val EXTRA_PARAM_CERT = "org.greatfire.envoy.extra.PARAM_CERT"
const val EXTRA_PARAM_SOURCES = "org.greatfire.envoy.extra.PARAM_SOURCES"
const val EXTRA_PARAM_INTERVAL = "org.greatfire.envoy.extra.PARAM_INTERVAL"
const val EXTRA_PARAM_START = "org.greatfire.envoy.extra.PARAM_START"
const val EXTRA_PARAM_END = "org.greatfire.envoy.extra.PARAM_END"
const val EXTRA_PARAM_FIRST = "org.greatfire.envoy.extra.PARAM_FIRST"

// Defines custom broadcasts
const val ENVOY_BROADCAST_VALIDATION_SUCCEEDED = "org.greatfire.envoy.VALIDATION_SUCCEEDED"
const val ENVOY_BROADCAST_VALIDATION_FAILED = "org.greatfire.envoy.VALIDATION_FAILED"
const val ENVOY_BROADCAST_BATCH_SUCCEEDED = "org.greatfire.envoy.BATCH_SUCCEEDED"
const val ENVOY_BROADCAST_BATCH_FAILED = "org.greatfire.envoy.BATCH_FAILED"
const val ENVOY_BROADCAST_UPDATE_SUCCEEDED = "org.greatfire.envoy.UPDATE_SUCCEEDED"
const val ENVOY_BROADCAST_UPDATE_FAILED = "org.greatfire.envoy.UPDATE_FAILED"
const val ENVOY_BROADCAST_VALIDATION_CONTINUED = "org.greatfire.envoy.VALIDATION_CONTINUED"
const val ENVOY_BROADCAST_VALIDATION_ENDED = "org.greatfire.envoy.VALIDATION_ENDED"

// Defines data for custom broadcasts
const val ENVOY_DATA_URL_SUCCEEDED = "org.greatfire.envoy.URL_SUCCEEDED"
const val ENVOY_DATA_URL_FAILED = "org.greatfire.envoy.URL_FAILED"
const val ENVOY_DATA_SERVICE_SUCCEEDED = "org.greatfire.envoy.SERVICE_SUCCEEDED"
const val ENVOY_DATA_SERVICE_FAILED = "org.greatfire.envoy.SERVICE_FAILED"
const val ENVOY_DATA_URL_LIST = "org.greatfire.envoy.URL_LIST"
const val ENVOY_DATA_SERVICE_LIST = "org.greatfire.envoy.SERVICE_LIST"
const val ENVOY_DATA_UPDATE_URL = "org.greatfire.envoy.UPDATE_URL"
const val ENVOY_DATA_UPDATE_LIST = "org.greatfire.envoy.UPDATE_LIST"
const val ENVOY_DATA_VALIDATION_MS = "org.greatfire.envoy.VALIDATION_MS"
const val ENVOY_DATA_VALIDATION_ENDED_CAUSE = "org.greatfire.envoy.VALIDATION_ENDED_CAUSE"

// Defines values for custom broadcasts
const val ENVOY_SERVICE_UPDATE = "update"
const val ENVOY_SERVICE_DIRECT = "direct"
const val ENVOY_SERVICE_HTTPS = "https"
const val ENVOY_SERVICE_ENVOY = "envoy"
const val ENVOY_SERVICE_V2WS = "v2ws"
const val ENVOY_SERVICE_V2SRTP = "v2srtp"
const val ENVOY_SERVICE_V2WECHAT = "v2wechat"
const val ENVOY_SERVICE_HYSTERIA = "hysteria"
const val ENVOY_SERVICE_SS = "ss"
const val ENVOY_SERVICE_SNOWFLAKE = "snowflake"
const val ENVOY_SERVICE_OBFS4 = "obfs4"
const val ENVOY_SERVICE_SOCKS = "socks"
const val ENVOY_SERVICE_UNKNOWN = "unknown"
const val ENVOY_ENDED_EMPTY = "empty"
const val ENVOY_ENDED_BLOCKED = "blocked"
const val ENVOY_ENDED_FAILED = "failed"
const val ENVOY_ENDED_TIMEOUT = "timeout"
const val ENVOY_ENDED_UNKNOWN = "unknown"

class Constants {}