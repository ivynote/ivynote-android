package app.ivynote.core

/**
 * The official sync service: one node for mainland China, one for everywhere
 * else. The two are separate services; an account lives on one of them. Any
 * other server is a custom, self-hosted one.
 */
enum class SyncRegion(val title: String, address: String) {
    CN("中国大陆", "https://web.ivynote.net"),
    GLOBAL("全球", "https://web.ivynote.app");

    val server: ServerAddress = ServerAddress.parse(address)

    companion object {
        /** The official region of a server, if it is one. */
        fun of(server: ServerAddress?): SyncRegion? = server?.let { s -> entries.firstOrNull { it.server == s } }

        /**
         * The node to preselect, by where the device is, not by its language:
         * Chinese speakers abroad belong on the global node.
         */
        fun preferred(region: String?, timeZone: String?): SyncRegion {
            if (!region.isNullOrEmpty()) return if (region.uppercase() == "CN") CN else GLOBAL
            if (timeZone != null) return if (timeZone in cnTimeZones) CN else GLOBAL
            return GLOBAL
        }

        // Hong Kong, Macau and Taipei are not here: they are served from the global node.
        private val cnTimeZones = setOf("Asia/Shanghai", "Asia/Urumqi", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin", "Asia/Kashgar", "PRC")
    }
}
