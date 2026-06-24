package com.duq.android.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android-движок: OkHttp. DoH (обход «Unable to resolve host on-za-menya.online») —
 * через [DohDns] на самом OkHttp-движке (`config { dns(DohDns) }`): системный резолвер
 * с fallback на DNS-over-HTTPS Cloudflare. Никакого expect/actual — резолвер живёт в
 * engine-конфиге Android-движка, commonMain про DoH не знает.
 */
actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config { dns(DohDns) }
        }
        block()
    }
