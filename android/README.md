
## Download

Cronet and IEnvoyProxy dependencies are now provided by Maven and no additional .aar files need to be downloaded.

## Server setup

Some documentation and example Ansible playbooks are available [here](https://gitlab.com/stevenmcdonald/envoy-proxy-examples/). The values used below will be based on what's used when configuring the server.

## Build

Run `./build-envoy.sh debug` or `./build-envoy.sh release` to build an envoy .aar file that can be indluded in other projects. If using Android Studio, open this android subdirectoy as a project and select the "debug" or "release" build variant.

## Get Started

Envoy has only one more extra API call than Google's [chromium](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/)/android [cronet library](https://developer.android.com/guide/topics/connectivity/cronet): `CronetEngine.Builder.setEnvoyUrl` .

Build the demo module to see it in action, or just call this in `Activity`'s `onCreate` method:

```java
CronetNetworking.initializeCronetEngine(getApplicationContext(), "YOUR-ENVOY-URL"); // set envoy url here, read native/README.md for all supported formats.
```
## Envoy url format

### HTTP/HTTPS

Envoy uses a nonstandard HTTP/HTTPS proxy where the origial request is passed in a header. Unencrypted HTTP is supported, but not recommeded. Cronet currently does not support proxying over QUIC / HTTP/3, so HTTP 1.1 or 2 is required. Server side setup is documented [here](../native/README.md) and a working example can be found [here](https://gitlab.com/stevenmcdonald/envoy-proxy-examples/-/tree/main/http_proxy).

The URL can be specified simply with the `http://` or `https://` protocol, e.g. `https://wiki.example.com/path/`, or the `envoy://` protocol can be used for more advanced features. The envoy protocol supports these paramters:

* url: proxy URL, for example, https://allowed.example.com/path/
* header_xxx: HTTP header, header_Host=my-host` will send Host header with value my-host
* address: IP address for domain in proxy URL, to replace IP from DNS resolving
* resolve: resolve map, same as `--host-resolver-rules` command line for chromium, [Chromium docs](https://www.chromium.org/developers/design-documents/network-stack/socks-proxy)
* disabled_cipher_suites: cipher suites to be disabled, same as `--cipher-suite-blacklist` command line for chromium
* salt: a 16 characters long random string, unique to each combination of app-signing key, user, and device, such [ANDROID_ID](https://developer.android.com/reference/android/provider/Settings.Secure.html#ANDROID_ID)

All keys except url are optional, for example, only `resolve` without `url` will just override the DNS setting. Note that values should be URL encoded.

For example, Cloudflare issues wildcard certs for hosted domains, so we can send a fake host name with hardcoded DNS, and provide an allowed host header to bypass DNS blacklisting and connection blocking based on host name. If our server is blocked.example.com and has an IP address of 10.10.10.10, we can use:

`envoy://?url=https%3A%2F%2Ffake.example.com%2Fpath%2F&address=10.10.10.10&header_Host=blocked.example.com`

An example disabling some cypher suites:

`envoy://?url=https%3A%2F%2Fallowed.example.com%2Fapp1%2F%3Fk1%3Dv1&header_Host=forbidden.example.com&address=1.2.3.4&disabled_cipher_suites=0xc024,0xc02f`

### [Shadowsocks](https://shadowsocks.org/)

Shadowsocks is configured using `ss://` URLs (full documentation [here](https://shadowsocks.org/guide/configs.html))

The "username" portion of the url is the method and password, spearated by a colon, base64 encoded. So if your server uses `chacha20-ietf-poly1305` and a password of `password`, the "username" would be "chacha20-ietf-poly1305:password" base64 encoded, or `Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzd29yZA==`. If the server is running on 192.168.64.19 port 8388, your Envoy Shadowsocks URL would be: `ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwYXNzd29yZA==@192.168.64.19:8388`

Note, Envoy uses [this fork](https://github.com/gfw-report/shadowsocks-rust) of Shadowsocks, updated based on research.


### [Hysteria](https://hysteria.network/)

> Hysteria is a feature-packed proxy & relay tool optimized for lossy, unstable connections (e.g. satellite networks, congested public Wi-Fi, connecting to foreign servers from China) powered by a customized protocol based on QUIC.

Server setup is documented [here](https://gitlab.com/stevenmcdonald/envoy-proxy-examples/-/tree/main/hysteria)

Envoy uses Hysteria's "wechat-video" protocol, where the QUIC stream is encoded to masquerade as a WeChat video call.

For a server on 192.168.64.19 on port 32323 with a password of "password", the URL would be `hysteria://192.168.64.19:32323?obfs=password`


### [V2Ray](https://github.com/v2fly/v2ray-core)

> Project V is a set of network tools that helps you to build your own computer network. It secures your network connections and thus protects your privacy.

Envoy currently uses a V2Ray fork maintained by a group called [V2Fly](https://www.v2fly.org/en_US/)

Envoy supports two protocols from V2Ray, both based on QUIC. One also masquerades as a WeChat video call, the other masquerades as an SRTP call. V2Ray uses shared knowledge of an UUID for authentication. For this example, we'll use "9e16552c-5de9-4369-95da-db712d7281ee"

If we have a server on 192.168.64.19 with V2Ray on port 16285, the URLs would be:

* Wechat: `v2wechat://192.168.64.19:16285?id=9e16552c-5de9-4369-95da-db712d7281ee`
* SRTP: `v2srtp://192.168.64.19:16285?id=9e16552c-5de9-4369-95da-db712d7281ee`
    
## Submit envoy urls
    
There are two options for submitting envoy urls:
    
 - submit(context, urls)
 - submit(context, urls, directUrls, hysteriaCert, urlSources, urlInterval, urlStart, urlEnd)

The first method signature is intended for simplified usage, it will not support a Hysteria URL or fetch additional URLs.

If the optional directUrls parameter is included, Envoy will attempt to connect to those urls directly first. This can be included to avoid using proxy resources when the target domain is not blocked.

The optional hysteriaCert parameter must be included if you submit any Hysteria URLs. It is a comma delimited string representing a self generated root certificate for the hysteria server in PEM format.

If the optional urlSources parameter is included, Envoy will attempt to fetch a list of additional URLs from those sources and validate them if all of the provided URLs fail. The remaining parameters can be used to specify certain URLs within those lists.

## Envoy broadcasts

Envoy provides feedback with a variety of broadcasts. Create a BroadcastReceiver and add actions to an IntentFilter as needed. The following are some of the more significant actions and their parameters:

 - ENVOY_BROADCAST_VALIDATION_SUCCEEDED, includes ENVOY_DATA_URL_SUCCEEDED, ENVOY_DATA_SERVICE_SUCCEEDED and ENVOY_DATA_VALIDATION_MS
 
Received when a URL is validated successfully. This can include any direct URLs that were submitted. The parameters include the URL that was validated and the corresponding service, as well as the number of milliseconds elapsed since validation began. Use this URL to initialize Cronet (initializing Cronet with a direct URL will cause a redirection exception).
 
 - ENVOY_BROADCAST_VALIDATION_FAILED, includes ENVOY_DATA_URL_FAILED and ENVOY_DATA_SERVICE_FAILED
 
Received when a URL fails validation. This can include any direct URLs that were submitted. The parameters include the URL that failed validation and the corresponding service.
 
 - ENVOY_BROADCAST_BATCH_SUCCEEDED, includes ENVOY_DATA_URL_LIST and ENVOY_DATA_SERVICE_LIST

Received if a batch of URLs is validated successfully. The parameters include the set of URLs that were validated and the corresponding set of services.

 - ENVOY_BROADCAST_BATCH_FAILED, includes ENVOY_DATA_URL_LIST and ENVOY_DATA_SERVICE_LIST

Received if a batch of URLs fails validation. The parameters include the set of URLs that failed validation and the corresponding set of services.

 - ENVOY_BROADCAST_UPDATE_SUCCEEDED, includes ENVOY_DATA_UPDATE_URL and ENVOY_DATA_UPDATE_LIST

Received if additional URLs have been fetched from a remote source. The parameters include the URL of the remote source and the list of additional URLs.

 - ENVOY_BROADCAST_UPDATE_FAILED, includes ENVOY_DATA_UPDATE_URL

Received if additional URLs could not be fetched from a remote source. The parameters include the URL of the remote source.

 - ENVOY_BROADCAST_VALIDATION_CONTINUED

Received if all originally submitted URLs fail validation but additional URLs are available for validation.

 - ENVOY_BROADCAST_VALIDATION_ENDED, includes ENVOY_DATA_VALIDATION_ENDED_CAUSE and ENVOY_DATA_VALIDATION_MS

Received if envoy validation has stopped without finding a valid url. Includes the reason that validation stopped as well as the number of milliseconds elapsed since validation began.

## Basic envoy integration

```kotlin
    private val mBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null && context != null) {
                if (intent.action == ENVOY_BROADCAST_VALIDATION_SUCCEEDED) {
                    val validUrl = intent.getStringExtra(ENVOY_DATA_URL_SUCCEEDED)          
                    if (validUrl != null) {
                        if (CronetNetworking.cronetEngine() == null) {
                            CronetNetworking.initializeCronetEngine(context, validUrl)
                        } else {
                            // cronet engine already initialized
                        }
                    } else {
                        // received null url
                    }
                } else if (intent.action == ENVOY_BROADCAST_VALIDATION_FAILED) {
                    val invalidUrl = intent.getStringExtra(ENVOY_DATA_URL_FAILED)
                    if (invalidUrl != null) {
                        // report invalid url
                    } else {
                        // received null url
                    }
                } else if (intent.action == ENVOY_BROADCAST_VALIDATION_ENDED) {
                    val cause = intent.getStringExtra(ENVOY_DATA_VALIDATION_ENDED_CAUSE)
                    if (cause != null) {
                        // report cause of failure
                    } else {
                        // received null cause
                    }
                } else {
                    // received unexpected intent
                }
            } else {
                // received null intent or context
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
            addAction(ENVOY_BROADCAST_VALIDATION_SUCCEEDED)
            addAction(ENVOY_BROADCAST_VALIDATION_FAILED)
            addAction(ENVOY_BROADCAST_VALIDATION_ENDED)
        })
    
        val listOfUrls = mutableListOf<String>()
        listOfUrls.add(urlOne)
        listOfUrls.add(urlTwo)
        org.greatfire.envoy.NetworkIntentService.submit(this@MainActivity, listOfUrls)
    }
```

Elsewhere, add the CronetInterceptor to your network client. This interceptor will be automatically bypassed if CronetEngine has not been initialized.

```kotlin
    private fun createClient(): OkHttpClient {
        // the cronet interceptor will be bypassed if the cronet engine has not been initialized
        return OkHttpConnectionFactory.client.newBuilder()
            .addInterceptor(CronetInterceptor())
            .build()
    }
```

Add uses-permission and services to AndroidManifest.xml
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

...

<service
    android:name="org.greatfire.envoy.NetworkIntentService"
    android:stopWithTask="false"
    android:exported="false"
    android:isolatedProcess="false" />

<service
    android:name="org.greatfire.envoy.ShadowsocksService"
    android:stopWithTask="false"
    android:exported="false"
    android:isolatedProcess="false" />
```

## FAQ

### library strip error
```
Task :app:stripDevDebugDebugSymbols
/Users/xxx/Library/Android/sdk/ndk-bundle/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-strip:/Users/me/Downloads/apps-android-wikipedia/app/build/intermediates/merged_native_libs/devDebug/out/lib/armeabi-v7a/libcronet.72.0.3626.122.so: File format not recognized

Unable to strip library /Users/xxx/apps-android-wikipedia/app/build/intermediates/merged_native_libs/devDebug/out/lib/armeabi-v7a/libcronet.72.0.3626.122.so due to error 1 returned from /Users/me/Library/Android/sdk/ndk-bundle/toolchains/arm-linux-androideabi-4.9/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-strip, packaging it as is
```

configure module build.gradle:
```
defaultConfig {
    packagingOptions {
        doNotStrip '**/libcronet*.so'
    }
}
```

### merged aar files
- [gradle - Android Studio how to package single AAR from multiple library projects?](https://stackoverflow.com/questions/20700581/android-studio-how-to-package-single-aar-from-multiple-library-projects/20715155#20715155)
- [Android native library merging](https://engineering.fb.com/android/android-native-library-merging/)

## TODO
1. customCronetBuilder in CronetNetworking
2. [WebView 内容远程调试](https://hearrain.com/webview-remote-debugging)

## Thanks and Acknowledgements
1. [react-native-cronet](https://github.com/akshetpandey/react-native-cronet).
2. [cronet](https://github.com/lizhangqu/cronet)
2. [How to choose an Android HTTP Library](https://appdevelopermagazine.com/how-to-choose-an-android-http-library/)
