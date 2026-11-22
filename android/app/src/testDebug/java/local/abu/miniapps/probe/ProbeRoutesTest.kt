package local.abu.miniapps.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProbeRoutesTest {
    @Test fun versionsShareOriginAndUrlButSelectDifferentPackagedPages() {
        assertEquals(ProbePage.A1.url, ProbePage.A2.url)
        assertEquals("a-v1.html", ProbePage.A1.assetFor("GET", ProbePage.A1.url))
        assertEquals("a-v2.html", ProbePage.A2.assetFor("GET", ProbePage.A1.url))
        assertEquals("b.html", ProbePage.B.assetFor("GET", ProbePage.B.url))
        assertEquals("probe.js", ProbePage.B.assetFor("GET", "https://b.abu.invalid/probe.js"))
        assertEquals("worker.js", ProbePage.workerAssetFor("GET", "https://a.abu.invalid/worker.js"))
        assertNull(ProbePage.workerAssetFor("GET", "https://b.abu.invalid/worker.js"))
        assertNull(ProbePage.workerAssetFor("POST", "https://a.abu.invalid/worker.js"))
        assertNull(ProbePage.workerAssetFor("GET", ProbePage.A1.url))
        assertNull(ProbePage.workerAssetFor("GET", "https://a.abu.invalid/offline.html"))
        assertNull(ProbePage.A1.assetFor("GET", "https://a.abu.invalid/offline.html"))
    }

    @Test fun onlyExactGetUrlsOnTheSelectedOriginCanReadAssets() {
        for (url in listOf(
            ProbePage.B.url,
            "https://a.abu.invalid/anything",
            "https://a.abu.invalid/%69ndex.html",
            "https://a.abu.invalid/path/../index.html",
            "https://a.abu.invalid/index.html?inject=1",
            "https://a.abu.invalid/index.html#marker",
            "http://a.abu.invalid/index.html",
            "https://a.abu.invalid:443/index.html",
            "https://a.abu.invalid:8080/index.html",
            "https://a.abu.invalid.evil.invalid/index.html",
            "https://a.abu.invalid@blocked.invalid/index.html",
            "https://127.0.0.1:8080/",
            "file:///abu-probe-nothing",
            "content://local.abu.probe.nothing/index.html"
        )) {
            assertNull(ProbePage.A1.assetFor("GET", url), url)
        }
        assertNull(ProbePage.A1.assetFor("POST", ProbePage.A1.url))
    }
}
