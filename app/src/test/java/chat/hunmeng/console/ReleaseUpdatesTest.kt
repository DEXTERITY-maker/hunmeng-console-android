package chat.hunmeng.console

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReleaseUpdatesTest {
    private val approvedUrl =
        "https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/download/v0.0.5-beta/Hunmeng-Console-0.0.5-beta.apk"

    private fun release(
        tag: String,
        apk: String = approvedUrl,
        draft: Boolean = false,
        uploaded: Boolean = true,
    ): String = """
        {
          "tag_name":"$tag",
          "draft":$draft,
          "html_url":"https://github.com/DEXTERITY-maker/hunmeng-console-android/releases/tag/$tag",
          "body":"New features",
          "assets":[{
            "name":"Hunmeng-Console.apk",
            "state":"${if (uploaded) "uploaded" else "new"}",
            "size":4567,
            "browser_download_url":"$apk"
          }]
        }
    """.trimIndent()

    @Test fun findsNewerBetaAndroidRelease() {
        val data = JSONArray("[" + release("v0.0.5-beta") + "]")
        val found = findLatestAndroidUpdate(data, "0.0.4-beta")
        assertEquals("v0.0.5-beta", found?.version)
        assertEquals(approvedUrl, found?.apkUrl)
        assertEquals("New features", found?.notes)
    }

    @Test fun sameNumberWithDifferentTagIsNotAFalseUpdate() {
        val data = JSONArray("[" + release("v0.0.4-beta-sync") + "]")
        assertNull(findLatestAndroidUpdate(data, "0.0.4-beta"))
    }

    @Test fun selectsHighestVersionRegardlessOfServerOrder() {
        val data = JSONArray("[" + release("v0.0.6-beta") + "," + release("v0.0.5-beta") + "]")
        assertEquals("v0.0.6-beta", findLatestAndroidUpdate(data, "0.0.4-beta")?.version)
    }

    @Test fun skipsDraftsUnuploadedApksAndUntrustedUrls() {
        val unsafe = "https://example.com/fake.apk"
        val data = JSONArray("[" +
            release("v0.0.8-beta", unsafe) + "," +
            release("v0.0.7-beta", draft = true) + "," +
            release("v0.0.6-beta", uploaded = false) + "," +
            release("v0.0.5-beta") + "]")
        assertEquals("v0.0.5-beta", findLatestAndroidUpdate(data, "0.0.4-beta")?.version)
    }

    @Test fun ignoresReleasesWithoutAnApk() {
        val data = JSONArray("""[{"tag_name":"v0.0.9-beta","draft":false,"assets":[]}]""")
        assertNull(findLatestAndroidUpdate(data, "0.0.4-beta"))
    }

    @Test fun networkCheckReadsPublicReleases() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[" + release("v0.0.5-beta") + "]"))
            server.start()
            val source = GithubReleaseSource(endpoint = server.url("/releases").toString())
            assertEquals("v0.0.5-beta", source.check("0.0.4-beta")?.version)
            assertEquals("/releases", server.takeRequest().path)
        }
    }

    @Test fun networkFailureIsNotReportedAsUpToDate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.start()
            val source = GithubReleaseSource(endpoint = server.url("/releases").toString())
            try {
                source.check("0.0.4-beta")
                fail("HTTP errors must be reported")
            } catch (_: IOException) {
                // Expected; the UI will display an error instead of "up to date".
            }
        }
    }
}
