package local.abu.ui.model

import android.content.Context
import local.abu.data.DEFAULT_SESSION_ID

internal interface DraftStorage {
    fun load(botId: String, sessionId: String = DEFAULT_SESSION_ID): String
    fun save(botId: String, sessionId: String = DEFAULT_SESSION_ID, text: String)
    fun removeSession(botId: String, sessionId: String)
    fun removeBot(botId: String)
    fun clearAll()
}

internal class InMemoryDraftStorage : DraftStorage {
    private val entries = mutableMapOf<Pair<String, String>, String>()

    override fun load(botId: String, sessionId: String): String = entries[botId to sessionId].orEmpty()

    override fun save(botId: String, sessionId: String, text: String) {
        if (text.isEmpty()) entries.remove(botId to sessionId)
        else entries[botId to sessionId] = text
    }

    override fun removeSession(botId: String, sessionId: String) {
        entries.remove(botId to sessionId)
    }

    override fun removeBot(botId: String) {
        entries.keys.removeAll { it.first == botId }
    }

    override fun clearAll() {
        entries.clear()
    }
}

internal class SharedPrefsDraftStorage(context: Context) : DraftStorage {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(botId: String, sessionId: String): String =
        prefs.getString(keyOf(botId, sessionId), "").orEmpty()

    override fun save(botId: String, sessionId: String, text: String) {
        val key = keyOf(botId, sessionId)
        if (text.isEmpty()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, text).apply()
    }

    override fun removeSession(botId: String, sessionId: String) {
        prefs.edit().remove(keyOf(botId, sessionId)).apply()
    }

    override fun removeBot(botId: String) {
        val prefix = "$botId:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun keyOf(botId: String, sessionId: String): String = "$botId:$sessionId"

    private companion object {
        const val PREFS_NAME = "abu_chat_drafts"
    }
}
