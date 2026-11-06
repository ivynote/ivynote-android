package app.ivynote.core

/** What can go wrong talking to a server (guide 5.1). */
sealed class ApiError(message: String) : Exception(message) {
    /** The server answered with an error status and `{"error": "..."}`. */
    class Server(val code: Int, val serverMessage: String) : ApiError(describe(code, serverMessage))

    /** No answer: offline, DNS, TLS, timeout. Work offline, retry later. */
    class Unreachable(val detail: String) : ApiError("无法连接服务器：$detail")

    /** The server answered, but not with what an Ivynote server sends. */
    class InvalidResponse : ApiError("这个地址不是常青笔记服务器")

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
            status == 429 -> "尝试次数过多，请 15 分钟后再试"
            status >= 500 -> "服务器暂时不可用（$status）"
            message.isEmpty() -> "请求失败（$status）"
            else -> message
        }
    }
}
