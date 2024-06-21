C and Java Library derived from chromium [cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/). Envoy can be easily added to existing Android apps using OkHttp, Volley, WebView, Cronet basic or java.net.HttpURLConnection to add censorship evasion features.

We are looking for developers to improve Envoy together, please contact support@greatfire.org.

我们正在寻找开发者一同改进Envoy，详情联系support@greatfire.org。

Technical details are explained [here](native/README.md).

* for pure c and java cronet library, see directory [native/](native).
* for the android library, import directory [android/](android) from android studio or build with gradle from the command line.

Please note that this project is unrelated to the [Envoy Proxy project](https://www.envoyproxy.io/).

# Getting Started

## Example Applications

* Wiki Unblocked: a fork of the official Wikipedia app with Envoy support [[Play Store](https://play.google.com/store/apps/details?id=org.greatfire.wikiunblocked)] [[F-Droid](https://f-droid.org/en/packages/org.greatfire.wikiunblocked.fdroid/)] [[Source](https://github.com/greatfire/apps-android-wikipedia-envoy/tree/wiki-unblocked)]

## Getting Envoy

Envoy and our patched Cronet are released though Maven Central.
Envoy releases can be found [here](https://mvnrepository.com/artifact/org.greatfire/envoy).
Cronet releases can be found [here](https://mvnrepository.com/artifact/org.greatfire.envoy/cronet).

## Server Setup Examples

See [this repo](https://gitlab.com/stevenmcdonald/envoy-proxy-examples/) for working examples of services that can be used by Envoy

# ENVOY Developer Guide

Welcome to the Envoy developer guide.
This document will teach you how to build Android apps using
APIs in the Android framework and other libraries.

## Protocols which Envoy supports

- HTTPS: proxy all traffic through regular web servers and Content Delivery Networks with support for hardcoding DNS and adding additional headers
- [Shadowsocks](https://github.com/gfw-report/shadowsocks-rust): use the socks5 proxy provided by shadowsocks client/server.
- Hysteria: QUIC based protocol that masquerades as other protocols
- V2Ray: QUIC based protocols that masquesade as other protocols

Visit [native/README.md](./native/README.md) and [android/README.md](./android/README.md) for more technical details.

## What is Cronet
Cronet is the networking stack of Chromium put into a library for use on mobile. This is the same networking stack that is used in the Chrome browser by over a billion people. It offers an easy-to-use, high performance, standards-compliant, and secure way to perform HTTP requests.. On Android, Cronet offers its own Java asynchronous API as well as support for the java.net.HttpURLConnection API. Cronet takes advantage of multiple technologies that reduce the latency and increase the throughput of the network requests that your app needs to work.

### Cronet release schedule

The cronet builds are based on the Chromium "Extended Support" releases. They generally stick with one major version, e.g. 102.0.5005.x, until the end of the support period. The Chromium LTS end of life date can be found under "ChromeOS LTS Last Refresh" on https://chromiumdash.appspot.com/schedule

## All About Envoy
Envoy is built on top of cronet which offers support for OkHttp, Volley, WebView, Cronet basic and java.net.HttpURLConnection.

We have built robust, unique, censorship-defeating tools and services that are having a big impact in some country where some website and their content are censored

This tool is derived from [Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/).

[Cronet](https://chromium.googlesource.com/chromium/src/+/master/components/cronet/) is the networking stack of Chromium put into a library for use on mobile. This is the same networking stack that is used in chrome browser by over a billion user.

So it will work with all request types which are mostly used in development.
It is used to make apps which are resistant to **censorship**.

## What is Envoy And Where It can be used

As the name suggests, Envoy is a **representative** or **messenger**.

Like it, our tool will work as a messenger or representative for certain websites or web content access without worrying about censorship, as our tool will work as the representative.

As earlier said, it can be used to make an app resistant to **censorship**.

Also, it can be used as a proxy tool communication for secure web access to your app.
E.g. if Facebook and YouTube are not accessible in countries like China, and you want to make an app which shows Facebook or YouTube content, then you can use this tool to show your content.

It can be used for showing partial content, or it can be used to make all server communications depending upon your requirements.

Also, if you don't want to use Envoy proxy then you can still use this tool to get the benefits of the cronet library hassle-free. And in the future, if you want, then you simply define an Envoy url to bypass censorship.

## App examples

Patches for adding Envoy support to several applications can be found in the `apps` directory. However, these patches do not reflect the most recent versions of Envoy or the original apps and may not work as intended at this time.

1. [Wikipedia](https://github.com/wikimedia/apps-android-wikipedia): `./gradlew clean assembleDevDebug`, [demo apk](https://envoy.greatfire.org/static/wikipedia-prod.apk), and the [migration guide](apps/wikipedia.md).
2. [DuckDuckGo](https://github.com/duckduckgo/Android): `./gradlew assembleDebug`, [demo apk](https://envoy.greatfire.org/static/duckduckgo-5.41.0-debug.apk)
3. WordPress:
   1. [WordPress-FluxC-Android](https://github.com/wordpress-mobile/WordPress-FluxC-Android): `echo "sdk.dir=YOUR_SDK_DIR" > local.properties && ./gradlew fluxc:build`
   2. [WordPress-Android](https://github.com/wordpress-mobile/WordPress-Android): set `wp.oauth.app_id` and `wp.oauth.app_secret`, then `cp gradle.properties-example gradle.properties && ./gradlew assembleVanillaDebug`

[Wiki Unblocked](https://github.com/greatfire/envoy#example-applications) has been expanded into a stand-alone application. Links to the application and source code can be found above.

[Feeder Unblocked](https://github.com/mnbogner/feeder-unblocked/tree/envoy-integration) is a fork of the Feeder RSS reader with Envoy support added. It is currently in development.

You can submit more apps with `git -c diff.noprefix=false format-patch --numbered --binary HEAD~`.

## Release steps

1. Rebuild cronet-debug.aar and cronet-release.aar: run `./native/build_cronet.sh debug` and `./native/build_cronet.sh release`
2. Rebuild Envoy: `./android/build-envoy.sh`
3. Rebuild demo apps: `./apps/build-apps.sh`

Building everything from source requires around 100GB of disk space and time. The whole release process is also scripted in the [_.gitlab-ci.yml_](.gitlab-ci.yml). This can also be run using [Vagrant](https://www.vagrantup.com/) by running `vagrant up --provision --no-destroy-on-error`.


## History
1. [Google to reimplement curl in libcrurl | daniel.haxx.se](https://daniel.haxx.se/blog/2019/06/19/google-to-reimplement-curl-in-libcrurl/), [Simplified Chinese](https://www.oschina.net/news/107711/google-to-reimplement-curl-in-libcrurl)
2. [973603 - [Cronet] libcurl wrapper library using Cronet API - chromium](https://bugs.chromium.org/p/chromium/issues/detail?id=973603)
