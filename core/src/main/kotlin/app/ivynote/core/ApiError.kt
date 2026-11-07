package app.ivynote.core

/** What can go wrong talking to a server (guide 5.1). */
sealed class ApiError : Exception() {
    /** The server answered with an error status and `{"error": "..."}`. */
    class Server(val code: Int, val serverMessage: String) : ApiError() {
        override val message: String get() = describe(code, serverMessage)
    }

    /** No answer: offline, DNS, TLS, timeout. Work offline, retry later. */
    class Unreachable(val detail: String) : ApiError() {
        override val message: String get() = L10n.s.unreachable(detail)
    }

    /** The server answered, but not with what an Ivynote server sends. */
    class InvalidResponse : ApiError() {
        override val message: String get() = L10n.s.notIvynote
    }

    val status: Int? get() = (this as? Server)?.code

    /** 401/403: the session is over (token expired or revoked, account disabled). */
    val endsSession: Boolean get() = status == 401 || status == 403

    /** Network failures and 5xx: keep working with local data and retry. */
    val isTransient: Boolean
        get() = when (this) {
            is Unreachable -> true
            is Server -> code >= 500
            is InvalidResponse -> false
        }

    companion object {
        private fun describe(status: Int, message: String): String = when {
            status == 429 -> L10n.s.tooManyAttempts
            status >= 500 -> L10n.s.serverUnavailable(status)
            message.isEmpty() -> L10n.s.requestFailedStatus(status)
            else -> message
        }
    }
}
