package thinkos.simulator.reference

/** Line-oriented reference runner; all geometry and colour decisions come from the original Kotlin files. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "slot" -> println(slot(args[1], args[2], args[3].toInt(), args[4].toInt(), args[5].toDouble(), args[6].toBoolean(), args[7].toBoolean()))
        "tiles" -> println(tiles(args[1], args[2].toInt(), args[3].toInt(), args[4].toDouble(), args[5].toBoolean()))
        "vertical" -> println(vertical(args[1].toInt(), args[2], args[3], args[4].toInt()))
        "panel" -> println(panel(args[1].toInt(), args[2].toInt(), args[3].toDouble(), args[4].toBoolean(), args[5].toBoolean(), args[6].toInt(), args[7].toBoolean(), args[8].toBoolean(), args[9].toBoolean(), args[10].toInt()))
        "colors" -> println(foreground(args[1], args[2].toDouble()))
        else -> error("Expected slot, tiles, vertical, panel or colors")
    }
}
