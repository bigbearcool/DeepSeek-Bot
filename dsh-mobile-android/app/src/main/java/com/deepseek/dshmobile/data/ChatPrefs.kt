package com.deepseek.dshmobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What this phone remembers about the list on its own (the computer has no place for it):
 * pinned chats, hidden chats (with when they were hidden), chats marked unread by hand, and the look.
 */
data class ChatPrefs(
    val pinned: Set<String> = emptySet(),
    val hidden: Map<String, Long> = emptyMap(),
    val unread: Set<String> = emptySet(),
    val theme: String = THEME_SYSTEM,
    /** Chats whose 通知 switch is off: their new-message count shows gray instead of black. */
    val muted: Set<String> = emptySet(),
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
    }
}

object ChatPrefsLogic {
    fun key(c: ChatEntry): String = c.kind + ":" + c.id

    /** Pinned first (in list order), hidden ones left out until something new arrives after hiding. */
    fun apply(chats: List<ChatEntry>, p: ChatPrefs): List<ChatEntry> {
        val visible = chats.filter { c ->
            val at = p.hidden[key(c)] ?: return@filter true
            (c.lastAt ?: 0L) > at
        }
        val (pin, rest) = visible.partition { key(it) in p.pinned }
        return pin + rest
    }

    fun isPinned(c: ChatEntry, p: ChatPrefs) = key(c) in p.pinned
    fun markedUnread(c: ChatEntry, p: ChatPrefs) = key(c) in p.unread

    fun togglePin(p: ChatPrefs, c: ChatEntry): ChatPrefs =
        if (key(c) in p.pinned) p.copy(pinned = p.pinned - key(c)) else p.copy(pinned = p.pinned + key(c))

    fun hide(p: ChatPrefs, c: ChatEntry, now: Long): ChatPrefs =
        p.copy(hidden = p.hidden + (key(c) to maxOf(now, c.lastAt ?: 0L)), pinned = p.pinned - key(c))

    fun unhideAll(p: ChatPrefs): ChatPrefs = p.copy(hidden = emptyMap())

    fun toggleUnread(p: ChatPrefs, c: ChatEntry): ChatPrefs =
        if (key(c) in p.unread) p.copy(unread = p.unread - key(c)) else p.copy(unread = p.unread + key(c))

    /** Opening a chat clears the hand-made unread mark. */
    fun opened(p: ChatPrefs, kind: String, id: String): ChatPrefs = p.copy(unread = p.unread - "$kind:$id")

    /** How many hidden chats are still hidden right now. */
    fun hiddenCount(chats: List<ChatEntry>, p: ChatPrefs): Int = chats.size - chats.count { c ->
        val at = p.hidden[key(c)] ?: return@count true
        (c.lastAt ?: 0L) > at
    }

    fun encodeHidden(m: Map<String, Long>): Set<String> = m.map { (k, v) -> "$v|$k" }.toSet()
    fun decodeHidden(s: Set<String>): Map<String, Long> = s.mapNotNull { e ->
        val i = e.indexOf('|'); if (i <= 0) null else e.substring(i + 1) to (e.substring(0, i).toLongOrNull() ?: return@mapNotNull null)
    }.toMap()

    fun normTheme(t: String?): String = when (t) { ChatPrefs.THEME_LIGHT, ChatPrefs.THEME_DARK -> t; else -> ChatPrefs.THEME_SYSTEM }
}

private val Context.prefsStore by preferencesDataStore("dsh_mobile_prefs")

class ChatPrefsStore(private val context: Context) {
    private val pinned = stringSetPreferencesKey("pinned")
    private val hidden = stringSetPreferencesKey("hidden")
    private val unread = stringSetPreferencesKey("unread")
    private val theme = stringPreferencesKey("theme")
    private val muted = stringSetPreferencesKey("muted")

    val flow: Flow<ChatPrefs> = context.prefsStore.data.map {
        ChatPrefs(
            pinned = it[pinned].orEmpty(),
            hidden = ChatPrefsLogic.decodeHidden(it[hidden].orEmpty()),
            unread = it[unread].orEmpty(),
            theme = ChatPrefsLogic.normTheme(it[theme]),
            muted = it[muted].orEmpty(),
        )
    }

    suspend fun save(p: ChatPrefs) {
        context.prefsStore.edit {
            it[pinned] = p.pinned
            it[hidden] = ChatPrefsLogic.encodeHidden(p.hidden)
            it[unread] = p.unread
            it[theme] = p.theme
            it[muted] = p.muted
        }
    }
}
