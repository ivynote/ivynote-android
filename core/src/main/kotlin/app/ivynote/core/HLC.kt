package app.ivynote.core

/**
 * Hybrid logical clock compatible with the server (guide 6.2):
 * `<13-digit ms>-<4-hex counter>-<node>`, compared as strings.
 */
class HLC(val node: String) {
    var wall: Long = 0
        private set
    var counter: Int = 0
        private set

    fun tick(now: Long): String {
        if (now > wall) {
            wall = now
            counter = 0
        } else {
            counter += 1
        }
        return format(wall, counter, node)
    }

    fun observe(ts: String) {
        val p = parse(ts) ?: return
        if (p.wall > wall || (p.wall == wall && p.counter > counter)) {
            wall = p.wall
            counter = p.counter
        }
    }

    data class Parts(val wall: Long, val counter: Int, val node: String)

    companion object {
        fun format(wall: Long, counter: Int, node: String): String =
            wall.toString().padStart(13, '0') + "-" + (counter and 0xffff).toString(16).padStart(4, '0') + "-" + node

        fun parse(ts: String): Parts? {
            val parts = ts.split('-', limit = 3)
            if (parts.size != 3 || parts[0].length != 13 || parts[1].length != 4) return null
            val wall = parts[0].toLongOrNull() ?: return null
            val counter = parts[1].toIntOrNull(16) ?: return null
            return Parts(wall, counter, parts[2])
        }
    }
}
