package local.abu

import kotlinx.coroutines.test.runTest
import local.abu.data.AppCatalogEntry
import local.abu.data.ChatHistory
import local.abu.data.ChatMessage
import local.abu.data.ChatSessionInfo
import local.abu.data.DEFAULT_SERVER_BASE_URL
import local.abu.data.DEFAULT_SESSION_ID
import local.abu.data.GeneratedSnapshot
import local.abu.data.parseGeneratedDocument
import local.abu.miniapps.GeneratedCache
import local.abu.miniapps.GeneratedJournal
import local.abu.miniapps.GeneratedTimed
import local.abu.miniapps.TodoCache
import local.abu.miniapps.todoIdentity
import local.abu.ui.ChatSession
import local.abu.ui.ChatState
import local.abu.ui.model.ConnectionApplyResult
import local.abu.ui.model.ConnectionPreference
import local.abu.ui.model.InMemoryConnectionStorage
import local.abu.ui.model.InMemoryDraftStorage
import local.abu.ui.model.StoredConnection
import local.abu.ui.model.serverScopedCacheDir
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ConnectionAndSessionUiTest {
    private val validToken = "a".repeat(32)

    @Test
    fun connectionPreferenceEnforcesCleartextPolicyPersistsAndClears() {
        val storage = InMemoryConnectionStorage()
        val releasePref = ConnectionPreference(storage, allowCleartext = false)
        assertEquals("", releasePref.serverUrl)
        assertEquals("", releasePref.token)

        val rejected = releasePref.apply("http://127.0.0.1:8080", validToken)
        assertTrue(rejected is ConnectionApplyResult.Invalid)
        assertEquals("", releasePref.token)

        val shortToken = releasePref.apply("https://abu.example.com:8443/", "too-short")
        assertTrue(shortToken is ConnectionApplyResult.Invalid)

        val applied = releasePref.apply("https://abu.example.com:8443/", "  $validToken  ")
        assertTrue(applied is ConnectionApplyResult.Applied)
        assertEquals("https://abu.example.com:8443", releasePref.serverUrl)
        assertEquals(validToken, releasePref.token)
        assertTrue(releasePref.persisted)

        // Reopening restores the saved URL and token automatically.
        val reopened = ConnectionPreference(storage, allowCleartext = false)
        assertEquals("https://abu.example.com:8443", reopened.serverUrl)
        assertEquals(validToken, reopened.token)
        assertTrue(reopened.persisted)

        reopened.clearToken()
        assertEquals("", reopened.token)
        assertFalse(reopened.persisted)
        assertEquals("", ConnectionPreference(storage, allowCleartext = false).token)

        // Debug allows cleartext HTTP and defaults to loopback.
        val debugPref = ConnectionPreference(InMemoryConnectionStorage(), allowCleartext = true)
        assertEquals(DEFAULT_SERVER_BASE_URL, debugPref.serverUrl)
        assertTrue(debugPref.apply("http://192.168.1.5:8080/", validToken) is ConnectionApplyResult.Applied)
        assertEquals("http://192.168.1.5:8080", debugPref.serverUrl)

        // Stored HTTP URL is ignored if opened in release mode.
        val releaseRejectsStoredHttp = ConnectionPreference(
            InMemoryConnectionStorage(StoredConnection("http://127.0.0.1:8080", validToken)),
            allowCleartext = false,
        )
        assertEquals("", releaseRejectsStoredHttp.serverUrl)
        assertEquals("", releaseRejectsStoredHttp.token)
    }

    @Test
    fun draftStoragePersistsPerBotAndSessionAndClearsOnRemoval() {
        val drafts = InMemoryDraftStorage()
        val firstSession = ChatSession(validToken, drafts)
        firstSession.forSession("abu", DEFAULT_SESSION_ID).draft = "默认草稿"
        firstSession.forSession("abu", "s2").draft = "会话二草稿"
        firstSession.forSession("leaf", DEFAULT_SESSION_ID).draft = "小叶草稿"

        // Reopening with the same draft store restores each session's draft cleanly.
        val reopened = ChatSession(validToken, drafts)
        assertEquals("默认草稿", reopened.forSession("abu", DEFAULT_SESSION_ID).draft)
        assertEquals("会话二草稿", reopened.forSession("abu", "s2").draft)
        assertEquals("小叶草稿", reopened.forSession("leaf", DEFAULT_SESSION_ID).draft)

        reopened.removeSession("abu", "s2")
        assertEquals("", drafts.load("abu", "s2"))
        assertEquals("默认草稿", drafts.load("abu", DEFAULT_SESSION_ID))

        reopened.removeBot("abu")
        assertEquals("", drafts.load("abu", DEFAULT_SESSION_ID))
        assertEquals("小叶草稿", drafts.load("leaf", DEFAULT_SESSION_ID))
    }

    @Test
    fun chatSessionTracksActiveSessionAndSearchesAcrossLoadedSessions() {
        val session = ChatSession(validToken)
        session.setSessions(
            "abu",
            listOf(
                ChatSessionInfo(DEFAULT_SESSION_ID, "abu", "默认对话", "", ""),
                ChatSessionInfo("s2", "abu", "工作计划", "", ""),
            ),
        )
        assertEquals(DEFAULT_SESSION_ID, session.activeSessionId("abu"))
        session.forSession("abu", DEFAULT_SESSION_ID).update(
            ChatMessage("m1", "默认会话消息", "2026-11-24T08:00:00Z", botId = "abu", sessionId = DEFAULT_SESSION_ID),
        )
        session.selectSession("abu", "s2")
        assertEquals("s2", session.activeSessionId("abu"))
        session.forBot("abu").update(
            ChatMessage("m2", "工作会话消息", "2026-11-24T09:00:00Z", botId = "abu", sessionId = "s2"),
        )
        assertEquals(listOf("m1", "m2"), session.allLoadedMessagesForBot("abu").map { it.id })

        // Deleting the active session falls back to default.
        session.removeSession("abu", "s2")
        assertEquals(DEFAULT_SESSION_ID, session.activeSessionId("abu"))
        assertEquals(listOf(DEFAULT_SESSION_ID), session.sessionsForBot("abu").map { it.id })
    }

    @Test
    fun loadOlderHistoryPrependsDeduplicatedMessagesAndUpdatesCursor() = runTest {
        val state = ChatState(botId = "abu", sessionId = "s1")
        state.refreshHistory {
            ChatHistory(
                messages = listOf(
                    ChatMessage("m3", "第三条", "2026-11-24T09:00:00Z", botId = "abu", sessionId = "s1"),
                    ChatMessage("m4", "第四条", "2026-11-24T10:00:00Z", botId = "abu", sessionId = "s1"),
                ),
                modelConfigured = true,
                sessionId = "s1",
                hasMore = true,
                nextBefore = "m3",
            )
        }
        assertTrue(state.hasMore)
        assertEquals("m3", state.nextBefore)

        val added = state.loadOlderHistory { before ->
            assertEquals("m3", before)
            ChatHistory(
                messages = listOf(
                    ChatMessage("m1", "第一条", "2026-11-24T07:00:00Z", botId = "abu", sessionId = "s1"),
                    ChatMessage("m2", "第二条", "2026-11-24T08:00:00Z", botId = "abu", sessionId = "s1"),
                    ChatMessage("m3", "重复项应去重", "2026-11-24T09:00:00Z", botId = "abu", sessionId = "s1"),
                ),
                modelConfigured = true,
                sessionId = "s1",
                hasMore = false,
                nextBefore = null,
            )
        }
        assertEquals(2, added)
        assertEquals(listOf("m1", "m2", "m3", "m4"), state.messages.map { it.id })
        assertFalse(state.hasMore)
        assertNull(state.nextBefore)
    }

    @Test
    fun miniAppCachesAreIsolatedAcrossServersEvenWithTheSameToken() {
        val root = createTempDirectory("abu-server-cache").toFile()
        try {
            val serverA = DEFAULT_SERVER_BASE_URL
            val serverB = "https://server-b.example:8443"
            val serverC = "https://server-c.example:8443"

            val todoDirA = serverScopedCacheDir(root, "miniapps", serverA)
            val todoDirB = serverScopedCacheDir(root, "miniapps", serverB)
            val todoDirBUpper = serverScopedCacheDir(root, "miniapps", "HTTPS://SERVER-B.EXAMPLE:8443/")
            val todoDirC = serverScopedCacheDir(root, "miniapps", serverC)
            val todoDirEmpty = serverScopedCacheDir(root, "miniapps", "")

            assertEquals(root.resolve("miniapps"), todoDirA)
            assertEquals(todoDirB, todoDirBUpper)
            assertNotEquals(todoDirA, todoDirB)
            assertNotEquals(todoDirB, todoDirC)
            assertNotEquals(todoDirA, todoDirEmpty)

            val identity = todoIdentity(validToken)
            val cacheA = TodoCache(todoDirA)
            val cacheB = TodoCache(todoDirB)
            val catalogA = AppCatalogEntry("personal-todo", "个人待办", "v3", listOf("v3"))
            cacheA.saveCatalog(identity, catalogA)

            // Same token on server B must not see server A's cached catalog.
            assertEquals(catalogA, cacheA.catalog(identity))
            assertNull(cacheB.catalog(identity))

            val genDirA = serverScopedCacheDir(root, "generated-miniapps", serverA)
            val genDirB = serverScopedCacheDir(root, "generated-miniapps", serverB)
            assertNotEquals(genDirA, genDirB)
            val genCacheA = GeneratedCache(genDirA)
            val genCacheB = GeneratedCache(genDirB)
            val journalA = GeneratedJournal(
                timed = GeneratedTimed(
                    GeneratedSnapshot(1, parseGeneratedDocument("""{"server":"A"}""")),
                    1_700_000_000_000L,
                ),
            )
            genCacheA.storeJournal(identity, "gen-app-a", journalA)

            // Same token on server B must not see server A's cached generated app offline snapshot.
            assertEquals(journalA, genCacheA.journal(identity, "gen-app-a"))
            assertNull(genCacheB.journal(identity, "gen-app-a").timed)
        } finally {
            root.deleteRecursively()
        }
    }
}
