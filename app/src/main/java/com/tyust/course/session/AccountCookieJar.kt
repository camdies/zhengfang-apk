package com.tyust.course.session

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Account-isolated RFC CookieJar.
 *
 * Cookies are kept as a full account collection and filtered with
 * Cookie.matches(url); this preserves parent-domain and host-only semantics.
 */
class AccountCookieJar : CookieJar {
    private val lock = Any()
    private val stores = linkedMapOf<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        saveFromResponse(url, cookies, requestAccountKey.get())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        loadForRequest(url, requestAccountKey.get())

    fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>, accountStorageKey: String?) {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        synchronized(lock) {
            val target = stores.getOrPut(key) { mutableListOf() }
            cookies.forEach { cookie ->
                replaceOrRemove(target, cookie)
            }
            purgeExpired(target)
        }
    }

    fun loadForRequest(url: HttpUrl, accountStorageKey: String?): List<Cookie> {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        synchronized(lock) {
            val source = stores[key] ?: return emptyList()
            purgeExpired(source)
            return source.filter { it.matches(url) }
        }
    }

    fun addCookie(url: HttpUrl, cookie: Cookie, accountStorageKey: String?) {
        saveFromResponse(url, listOf(cookie), accountStorageKey)
    }

    fun installBundle(accountStorageKey: String, bundle: SessionArtifact.RfcCookieBundle) {
        synchronized(lock) {
            val target = stores.getOrPut(SessionRegistry.normalizeAccountKey(accountStorageKey)) {
                mutableListOf()
            }
            target.clear()
            bundle.cookies
                .filter(bundle::matchesAnyServiceAudience)
                .mapNotNull(PersistedCookie::toCookie)
                .forEach { replaceOrRemove(target, it) }
            purgeExpired(target)
        }
    }

    fun clearAccount(accountStorageKey: String?) {
        synchronized(lock) {
            stores.remove(SessionRegistry.normalizeAccountKey(accountStorageKey))
        }
    }

    fun clear(url: HttpUrl, accountStorageKey: String?) {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        synchronized(lock) {
            val target = stores[key] ?: return
            target.removeAll { it.matches(url) }
            if (target.isEmpty()) stores.remove(key)
        }
    }

    fun snapshotForAccount(accountStorageKey: String?): List<Cookie> {
        val key = SessionRegistry.normalizeAccountKey(accountStorageKey)
        synchronized(lock) {
            val source = stores[key] ?: return emptyList()
            purgeExpired(source)
            return source.toList()
        }
    }

    private fun replaceOrRemove(target: MutableList<Cookie>, candidate: Cookie) {
        target.removeAll {
            it.name == candidate.name &&
                it.domain == candidate.domain &&
                it.path == candidate.path &&
                it.hostOnly == candidate.hostOnly
        }
        if (candidate.expiresAt > System.currentTimeMillis()) {
            target.add(candidate)
        }
    }

    private fun purgeExpired(cookies: MutableList<Cookie>) {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
    }

    companion object {
        private val requestAccountKey = ThreadLocal.withInitial { "default" }

        @JvmStatic
        fun setRequestAccountKey(accountStorageKey: String?) {
            requestAccountKey.set(SessionRegistry.normalizeAccountKey(accountStorageKey))
        }

        @JvmStatic
        fun clearRequestAccountKey() {
            requestAccountKey.remove()
        }
    }
}
