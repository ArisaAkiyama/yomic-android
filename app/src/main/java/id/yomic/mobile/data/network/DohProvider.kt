package id.yomic.mobile.data.network

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress

enum class DohProvider(
    val settingsKey: String,
    val dohUrl: String?,
    val bootstrapIps: List<String>
) {
    CLOUDFLARE(
        settingsKey = "Cloudflare (1.1.1.1)",
        dohUrl = "https://cloudflare-dns.com/dns-query",
        bootstrapIps = listOf("1.1.1.1", "1.0.0.1")
    ),
    GOOGLE(
        settingsKey = "Google (8.8.8.8)",
        dohUrl = "https://dns.google/dns-query",
        bootstrapIps = listOf("8.8.8.8", "8.8.4.4")
    ),
    QUAD9(
        settingsKey = "Quad9 (9.9.9.9)",
        dohUrl = "https://dns.quad9.net/dns-query",
        bootstrapIps = listOf("9.9.9.9", "149.112.112.112")
    ),
    ADGUARD(
        settingsKey = "AdGuard DNS",
        dohUrl = "https://dns.adguard-dns.com/dns-query",
        bootstrapIps = listOf("94.140.14.14", "94.140.15.15")
    ),
    SYSTEM(
        settingsKey = "System Default",
        dohUrl = null,
        bootstrapIps = emptyList()
    );

    fun createDns(bootstrapClient: OkHttpClient): Dns {
        val endpoint = dohUrl ?: return Dns.SYSTEM
        return runCatching {
            val ips = bootstrapIps.map { InetAddress.getByName(it) }
            val doh = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url(endpoint.toHttpUrl())
                .bootstrapDnsHosts(ips)
                .includeIPv6(false)
                .build()
            FallbackDns(primary = doh, fallback = Dns.SYSTEM)
        }.getOrDefault(Dns.SYSTEM)
    }

    companion object {
        fun fromSettingsKey(raw: String?): DohProvider {
            val trimmed = raw?.trim().orEmpty()
            return entries.firstOrNull { it.settingsKey.equals(trimmed, ignoreCase = true) }
                ?: when {
                    trimmed.contains("google", ignoreCase = true) -> GOOGLE
                    trimmed.contains("quad9", ignoreCase = true) -> QUAD9
                    trimmed.contains("adguard", ignoreCase = true) -> ADGUARD
                    trimmed.contains("system", ignoreCase = true) || trimmed.contains("nonaktif", ignoreCase = true) -> SYSTEM
                    else -> CLOUDFLARE
                }
        }
    }
}

private class FallbackDns(
    private val primary: Dns,
    private val fallback: Dns
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname == "localhost" || hostname == "127.0.0.1" || hostname == "::1") {
            return fallback.lookup(hostname)
        }
        return runCatching { primary.lookup(hostname) }
            .getOrElse { fallback.lookup(hostname) }
    }
}
