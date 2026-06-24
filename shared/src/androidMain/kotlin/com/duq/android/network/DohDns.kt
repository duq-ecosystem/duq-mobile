package com.duq.android.network

import com.duq.android.config.AppConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Единая точка резолва имён для OkHttp-движка Ktor (Android).
 *
 * Проблема: системный DNS телефона на некоторых сетях НЕ резолвит наш домен
 * `on-za-menya.online` (а также `api.github.com`, `huggingface.co`) — «Unable to
 * resolve host». Chrome ходит, потому что тащит собственный DoH. OkHttp по умолчанию
 * берёт системный резолвер ([Dns.SYSTEM]) → падает.
 *
 * Решение: сначала системный резолвер (быстро, без сети, работает в норме), и ТОЛЬКО
 * при [UnknownHostException] fallback на DNS-over-HTTPS (Cloudflare) по bootstrap-IP
 * (литералы — не нужно резолвить сам DoH-хост, нет курицы-яйца). Ленивая инициализация:
 * DoH-клиент строится только при первом реальном fallback'е.
 */
object DohDns : Dns {

    private val doh: DnsOverHttps by lazy {
        val bootstrapIps = AppConfig.DOH_BOOTSTRAP_IPS.mapNotNull { ip ->
            runCatching { InetAddress.getByName(ip) }.getOrNull()
        }
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url(AppConfig.DOH_RESOLVER_URL.toHttpUrl())
            .bootstrapDnsHosts(bootstrapIps)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return runCatching { doh.lookup(hostname) }
            .getOrElse { emptyList() }
            .ifEmpty { throw UnknownHostException("$hostname (system + DoH failed)") }
    }
}
