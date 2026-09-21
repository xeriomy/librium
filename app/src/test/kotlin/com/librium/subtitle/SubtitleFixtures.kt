package com.librium.subtitle

/** Loads the subtitle fixtures under src/test/resources as UTF-8 text. */
object SubtitleFixtures {
    fun text(name: String): String {
        val stream = SubtitleFixtures::class.java.classLoader
            ?.getResourceAsStream("subtitles/$name")
            ?: error("missing fixture: $name")
        return stream.bufferedReader(Charsets.UTF_8).readText()
    }
}
