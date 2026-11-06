package app.ivynote.core

/**
 * The official sync service: one node for mainland China, one for everywhere
 * else. The two are separate services; an account lives on one of them. Any
 * other server is a custom, self-hosted one.
 *
 * The addresses here are built in; the website can move a node to another
 * address without a client release ([OfficialRegions]). [server] is the
 * address in effect now.
 */
enum class SyncRegion(val id: String, val title: String, address: String) {
    CN("cn", "中国大陆", "https://web.ivynote.net"),
    GLOBAL("global", "全球", "https://web.ivynote.app");

    /** Built into the client; used whenever the website has said nothing else. */
    val defaultServer: ServerAddress = ServerAddress.parse(address)

    /** The address new connections to this region use: the website's, else [defaultServer]. */
    val server: ServerAddress get() = OfficialRegions.current.getValue(this).server

    companion object {
        /**
         * The official region of a server, if it is one: its current address,
         * an alias the website listed, or the built-in address.
         */
        fun of(server: ServerAddress?): SyncRegion? = server?.let { OfficialRegions.regionOf(it) }

        fun byId(id: String?): SyncRegion? = entries.firstOrNull { it.id == id }

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
