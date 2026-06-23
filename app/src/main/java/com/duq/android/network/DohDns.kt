package com.duq.android.network

import com.duq.android.config.AppConfig
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Единая точка резолва имён для всех OkHttp-клиентов приложения.
 *
 * Проблема: системный DNS телефона на некоторых сетях (оператор/роутер/частный
 * DNS-сервер) НЕ резолвит наш домен `on-za-menya.online` (а также `api.github.com`,
 * `huggingface.co`) — «Unable to resolve host … No address associated with hostname».
 * Chrome при этом ходит, потому что тащит собственный DoH. OkHttp по умолчанию берёт
 * системный резолвер ([Dns.SYSTEM]) → падает.
 *
 * Решение: сначала пробуем системный резолвер (быстро, без сети, работает в норме),
 * и ТОЛЬКО при [UnknownHostException] делаем fallback на DNS-over-HTTPS (Cloudflare).
 * DoH идёт по HTTPS на IP-литералы bootstrap-резолвера ([AppConfig.DOH_BOOTSTRAP_IPS]),
 * поэтому сам DoH-хост не нужно резолвить системным DNS (нет курицы-яйца).
 *
 * Применяется единообразно ко всем клиентам через [withDuqDns]. Ленивая инициализация:
 * DoH-клиент строится только при первом реальном fallback'е (в норме не создаётся).
 *
 * Один общий инстанс (object) — DoH-резолвер кэширует ответы внутри своего OkHttp-пула.
 */
object DohDns : Dns {

    // DoH-резолвер собирается лениво при первом fallback'е. Свой минимальный OkHttp-клиент.
    // bootstrapDnsHosts = жёсткие IP DoH-эндпоинта (литералы, InetAddress.getByName на IP не
    // ходит в сеть) → сам DoH-хост не резолвится системным DNS (нет курицы-яйца).
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
        // 1. Системный резолвер — норма (мгновенно, без сети).
        runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // 2. Fallback: DoH через Cloudflare по bootstrap-IP.
        return runCatching { doh.lookup(hostname) }
            .getOrElse { emptyList() }
            .ifEmpty { throw UnknownHostException("$hostname (system + DoH failed)") }
    }
}

/** Подключает [DohDns] к билдеру — единая точка для всех клиентов приложения. */
fun OkHttpClient.Builder.withDuqDns(): OkHttpClient.Builder = dns(DohDns)
