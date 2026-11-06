package app.ivynote.core

import kotlinx.serialization.Serializable

/** `GET /info`: what the server allows before anyone signs in. */
@Serializable
data class ServerInfo(
    val siteName: String = "",
    val allowRegistration: Boolean = false,
    val hasUsers: Boolean = true,
    val version: String = "",
    val maxUploadMB: Int = 200,
) {
    /** The first account on an empty server becomes its admin, so sign-up is always open until then. */
    val canRegister: Boolean get() = allowRegistration || !hasUsers
}

/** The signed-in user, as `GET /me` and the auth endpoints return it. */
@Serializable
data class User(
    val id: String,
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = "user",
    val status: String = "active",
    val aiAvailable: Boolean = false,
) {
    val name: String get() = displayName.ifEmpty { username }
    val isAdmin: Boolean get() = role == "admin"
}

@Serializable
data class Health(val ok: Boolean = false, val version: String = "")

@Serializable
internal data class AuthResponse(val token: String, val user: User)

/** The signed-in account of a server library, kept in secure storage. */
@Serializable
data class StoredAccount(val server: ServerAddress, val token: String, val user: User)

/** Client-side checks matching the server's field limits (guide 4.3). */
object Validation {
    private val usernamePattern = Regex("[A-Za-z0-9][A-Za-z0-9_.\\-]{1,31}")

    fun username(s: String): String? =
        if (usernamePattern.matches(s)) null else "用户名为 2–32 位字母、数字、_ . -，以字母或数字开头"

    fun password(s: String): String? = when {
        s.codePointCount(0, s.length) < 8 -> "密码至少 8 个字符"
        s.toByteArray(Charsets.UTF_8).size > 72 -> "密码最多 72 字节"
        else -> null
    }
}
