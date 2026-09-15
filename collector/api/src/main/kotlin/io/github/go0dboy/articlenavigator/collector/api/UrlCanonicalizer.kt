package io.github.go0dboy.articlenavigator.collector.api

import java.net.URI

/**
 * Conservative URL canonicalization for identity/deduplication.
 *
 * It deliberately preserves query parameters because removing an apparently
 * tracking-like parameter can also remove content identity on some sites.
 */
object UrlCanonicalizer {
    fun canonicalize(url: String, baseUrl: String? = null): String? {
        val resolved = runCatching {
            if (baseUrl == null) URI(url) else URI(baseUrl).resolve(url)
        }.getOrNull()?.normalize() ?: return null

        val scheme = resolved.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = resolved.host?.lowercase() ?: return null
        val port = when {
            resolved.port == -1 -> -1
            scheme == "http" && resolved.port == 80 -> -1
            scheme == "https" && resolved.port == 443 -> -1
            else -> resolved.port
        }
        val hostText = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val path = resolved.rawPath?.ifBlank { "/" } ?: "/"

        return buildString {
            append(scheme)
            append("://")
            append(hostText)
            if (port != -1) append(':').append(port)
            append(path)
            resolved.rawQuery?.let { append('?').append(it) }
        }
    }
}
