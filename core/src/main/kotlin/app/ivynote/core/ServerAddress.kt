package app.ivynote.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The address of a self-hosted Ivynote server, normalized from what the user typed.
 *
 * [base] never ends with a slash and never includes `/api/v1`, so
 * `https://notes.example.com/`, `notes.example.com` and
 * `https://notes.example.com/api/v1` all become `https://notes.example.com`.
 */
@Serializable(with = ServerAddressSerializer::class)
class ServerAddress private constructor(val base: String, val host: String, val scheme: String) {

    sealed class ParseError : Exception() {
        data object Empty : ParseError()
        data object Invalid : ParseError()
        data class UnsupportedScheme(val scheme: String) : ParseError()
        data class InsecureRemote(val host: String) : ParseError()

        override val message: String
            get() = when (this) {
                Empty -> L10n.s.serverEmpty
                Invalid -> L10n.s.serverInvalid
                is UnsupportedScheme -> L10n.s.unsupportedScheme(scheme)
                is InsecureRemote -> L10n.s.insecureRemote(host)
            }
    }

    /** `{server}/api/v1`. */
    val apiBase: String get() = "$base/api/v1"

    /**
     * Plain http to anything but this device: the user should be warned that
     * the password and the notes travel unencrypted.
     */
    val isInsecure: Boolean get() = scheme == "http" && !isLoopback(host)

    override fun toString(): String = base
    override fun equals(other: Any?): Boolean = other is ServerAddress && other.base == base
    override fun hashCode(): Int = base.hashCode()

    companion object {
        @Throws(ParseError::class)
        fun parse(input: String): ServerAddress {
            var text = input.trim()
            if (text.isEmpty()) throw ParseError.Empty
            if (!text.contains("://")) {
                // No scheme: local addresses default to http, everything else to https.
                val host = text.substringBefore('/')
                text = (if (isLocalNetwork(stripPort(host))) "http://" else "https://") + text
            }
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") throw ParseError.UnsupportedScheme(scheme)
            val url = (scheme + "://" + text.substringAfter("://")).toHttpUrlOrNull() ?: throw ParseError.Invalid
            val host = url.host.lowercase()
            if (host.isEmpty()) throw ParseError.Invalid
            // Plain http is only accepted on the local network (guide 8.2).
            if (scheme == "http" && !isLocalNetwork(host)) throw ParseError.InsecureRemote(host)

            var path = url.encodedPath.trimEnd('/')
            if (path.lowercase().endsWith("/api/v1")) path = path.dropLast("/api/v1".length)
            path = path.trimEnd('/')
            val clean = url.newBuilder()
                .username("").password("").query(null).fragment(null)
                .encodedPath(path.ifEmpty { "/" })
                .build()
                .toString()
            return ServerAddress(if (path.isEmpty()) clean.trimEnd('/') else clean, host, scheme)
        }

        fun parseOrNull(input: String): ServerAddress? = try {
            parse(input)
        } catch (_: ParseError) {
            null
        }

        internal fun isLoopback(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            return h == "localhost" || h.endsWith(".localhost") || h == "::1" || h.startsWith("127.")
        }

        /** Unqualified names, `.local`, and IP literals: where plain http is tolerated. */
        internal fun isLocalNetwork(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (isLoopback(h) || !h.contains('.') || h.endsWith(".local")) return true
            if (h.contains(':')) return true // IPv6 literal
            val octets = h.split('.')
            return octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true && o.isNotEmpty() }
        }

        private fun stripPort(host: String): String =
            if (host.startsWith("[")) host else host.substringBefore(':')
    }
}

object ServerAddressSerializer : KSerializer<ServerAddress> {
    override val descriptor = PrimitiveSerialDescriptor("ServerAddress", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ServerAddress) = encoder.encodeString(value.base)
    override fun deserialize(decoder: Decoder): ServerAddress = ServerAddress.parse(decoder.decodeString())
}
