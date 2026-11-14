package io.github.tianli0.switchtether

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform VectorDrawable parser (used by SystemUI for notification icons, e.g. the Live
 * Update tracker) rejects compact SVG arc flags such as `0 01-.104`, which Compose accepts. Every
 * arc in our vectors must therefore spell out its seven numbers.
 */
class VectorPathDataTest {
    private val number = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")

    @Test
    fun arcsHaveSevenSeparatedNumbers() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val vectors = res.walkTopDown().filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("drawable") }.toList()
        assertTrue("no drawables found under $res", vectors.isNotEmpty())
        val bad = mutableListOf<String>()
        vectors.forEach { f ->
            Regex("""pathData="([^"]+)"""").findAll(f.readText()).forEach { m ->
                Regex("""[aA]([^a-zA-Z]*)""").findAll(m.groupValues[1]).forEach { arc ->
                    val count = number.findAll(arc.groupValues[1]).count()
                    if (count == 0 || count % 7 != 0) bad += "${f.name}: ${arc.value.take(40)}"
                }
            }
        }
        assertTrue("compact arc flags (platform PathParser fails): $bad", bad.isEmpty())
    }
}
