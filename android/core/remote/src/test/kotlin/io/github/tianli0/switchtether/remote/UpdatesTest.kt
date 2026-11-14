package io.github.tianli0.switchtether.remote

import io.github.tianli0.switchtether.remote.update.GithubReleases
import io.github.tianli0.switchtether.remote.update.ReleaseInfo
import io.github.tianli0.switchtether.remote.update.SemVer
import io.github.tianli0.switchtether.remote.update.Sha256Sums
import io.github.tianli0.switchtether.remote.update.chooseUpdate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun v(s: String) = SemVer.parse(s)!!
    private val fixture = javaClass.getResource("/update/release-latest.json")!!.readText()

    @Test
    fun semverOrdering() {
        assertTrue(v("v0.2.0") > v("0.1.9"))
        assertTrue(v("1.10.0") > v("1.9.3"))
        assertTrue(v("1.0.0") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-rc.2") > v("1.0.0-rc.1"))
        assertTrue(v("1.0.0-beta.11") > v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha.9"))
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))
        assertEquals(0, v("v1.2.3+build.5").compareTo(v("1.2.3")))
        assertEquals(v("0.1.0"), v("0.1"))
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("1.2.3.4"))
    }

    @Test
    fun releaseJsonAndAssetContract() {
        val r = ReleaseInfo.parseAll(fixture).single()
        assertEquals("v0.2.0", r.tag)
        assertEquals(v("0.2.0"), r.version)
        assertEquals("SwitchTether-0.2.0-android.apk", r.apk!!.name)
        assertEquals(7012345L, r.apk!!.size)
        assertNotNull(r.checksums)
        assertTrue(r.notes.contains("在手机上接续会话"))
        assertEquals(r, chooseUpdate(listOf(r), v("0.1.0"), includePrerelease = false))
        assertNull(chooseUpdate(listOf(r), v("0.2.0"), includePrerelease = false))
        assertNull(chooseUpdate(listOf(r), v("0.3.0-beta.1"), includePrerelease = false))
    }

    @Test
    fun prereleasesOnlyWhenOptedIn() {
        val list = ReleaseInfo.parseAll(
            """[{"tag_name":"v0.3.0-beta.1","prerelease":true,"draft":false,"assets":[]},
                {"tag_name":"v0.2.0","prerelease":false,"draft":false,"assets":[]},
                {"tag_name":"v0.4.0","prerelease":false,"draft":true,"assets":[]}]""",
        )
        assertEquals(2, list.size) // drafts ignored
        assertEquals("v0.2.0", chooseUpdate(list, v("0.1.0"), includePrerelease = false)?.tag)
        assertEquals("v0.3.0-beta.1", chooseUpdate(list, v("0.1.0"), includePrerelease = true)?.tag)
    }

    @Test
    fun checksumFileParsing() {
        val sums = Sha256Sums.parse(
            """
            # SwitchTether 0.2.0
            9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  SwitchTether-0.2.0-android.apk
            E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855 *SwitchTether_0.2.0_x64-setup.exe
            not a checksum line
            """.trimIndent(),
        )
        assertEquals(2, sums.size)
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", sums["SwitchTether-0.2.0-android.apk"])
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sums["SwitchTether_0.2.0_x64-setup.exe"])
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", Sha256Sums.of("test".byteInputStream()))
    }

    @Test
    fun etagIsSentAndNotModifiedIsReported() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"abc\"").body(fixture).build())
            server.enqueue(MockResponse.Builder().code(304).addHeader("ETag", "\"abc\"").build())
            server.start()
            val gh = GithubReleases(OkHttpClient(), baseUrl = server.url("/").toString().trimEnd('/'))
            val first = gh.fetch(includePrerelease = false, etag = null)
            assertEquals(200, first.code)
            assertEquals("\"abc\"", first.etag)
            val second = gh.fetch(includePrerelease = false, etag = first.etag)
            assertEquals(304, second.code)
            server.takeRequest()
            val req2 = server.takeRequest()
            assertEquals("\"abc\"", req2.headers["If-None-Match"])
            assertEquals("/repos/TIANLI0/SwitchTether/releases/latest", req2.url.encodedPath)
            assertTrue(req2.headers["User-Agent"]!!.startsWith("SwitchTether-Android"))
        }
    }
}
