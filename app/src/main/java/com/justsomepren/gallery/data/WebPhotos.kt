package com.justsomepren.gallery.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The whole Google Photos library, read the same way the photos.google.com page reads it, with the
 * login from the app's own browser window. Google gives apps no official way to list the library,
 * so this can stop working when Google changes the page. It only reads and keeps small previews.
 */
object WebPhotos {
    data class Item(val key: String, val thumb: String, val taken: Long, val video: Boolean)
    data class Page(val items: List<Item>, val next: String?)
    data class Progress(
        val listed: Int = 0,
        val added: Int = 0,
        val failed: Int = 0,
        /** Why the last preview could not be saved. */
        val reason: String? = null,
        val done: Boolean = false,
        val error: String? = null,
    )

    val progress = MutableStateFlow<Progress?>(null)

    @VisibleForTesting
    var site = "https://photos.google.com"
    val login get() = "https://accounts.google.com/ServiceLogin?continue=" + URLEncoder.encode("$site/", "UTF-8")

    const val PAGE_SIZE = 500
    private const val PREVIEW = "=w300-h300"
    private const val PARALLEL = 8
    private const val GIVE_UP = 24

    /** Google refuses to sign in when the browser says it is a window inside an app. */
    fun agent(context: Context): String = WebSettings.getDefaultUserAgent(context).replace("; wv", "")

    /** Removes the login and everything else the browser window kept. */
    fun forgetLogin() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    fun logout() {
        forgetLogin()
        progress.value = null
    }

    /**
     * Goes through the library from the newest photo and adds previews that are missing.
     * Skips photos that are in the phone ([onPhone]: name and minute); when one is deleted from the phone
     * later, the next run adds it. Previews of photos deleted in Google Photos are removed.
     */
    suspend fun sync(
        session: WebSession,
        library: CloudLibrary,
        names: WebNames,
        onPhone: Set<Pair<String, Long>>,
        stopped: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Progress = coroutineScope {
        val seen = HashSet<String>()
        var progress = Progress()
        var next: String? = null
        do {
            val page = session.page(next)
            names.add(session.names(page.items.map { it.key }.filter { names[it] == null }))
            val missing = page.items.filter { item ->
                val name = names[item.key] ?: return@filter false
                seen += id(item)
                !library.has(id(item)) && (name to item.taken / 60_000) !in onPhone
            }
            for (group in missing.chunked(PARALLEL)) {
                if (stopped()) return@coroutineScope progress
                val previews = group.map { async(Dispatchers.IO) { runCatching { session.preview(sized(it.thumb)) } } }.awaitAll()
                group.zip(previews).forEach { (item, preview) ->
                    val name = names[item.key]!!
                    val mime = Takeout.types[name.substringAfterLast('.', "").lowercase()] ?: if (item.video) "video/mp4" else "image/jpeg"
                    preview.onSuccess { library.add(CloudPhoto(id(item), item.taken, name, mime), it) }
                }
                val added = previews.count { it.isSuccess }
                val reason = previews.firstNotNullOfOrNull { it.exceptionOrNull() }?.message ?: progress.reason
                progress = progress.copy(added = progress.added + added, failed = progress.failed + group.size - added, reason = reason)
                // Nothing gets through: asking Google thousands of times more would not help
                if (progress.added == 0 && progress.failed >= GIVE_UP) throw IOException("Náhledy nejdou stáhnout: $reason")
            }
            progress = progress.copy(listed = progress.listed + page.items.size)
            onProgress(progress)
            next = page.next
        } while (next != null)
        library.remove(library.ids().filter { it.startsWith("web:") && it !in seen })
        progress.copy(done = true)
    }

    private fun id(item: Item) = "web:${item.key}"

    /** The address ends with the size after "="; the page's own size is replaced by a small one. */
    private fun sized(thumb: String) = thumb.substringBefore('=') + PREVIEW

    /** One page of the library, as the page's "lcxiM" call returns it. */
    fun parsePage(data: JSONArray): Page {
        val list = data.optJSONArray(0) ?: JSONArray()
        val items = (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONArray(i) ?: return@mapNotNull null
            val key = item.text(0) ?: return@mapNotNull null
            val thumb = item.optJSONArray(1)?.text(0) ?: return@mapNotNull null
            // The last field holds extras, this one only for videos
            val video = item.optJSONObject(item.length() - 1)?.has("76647426") == true
            Item(key, thumb, item.optLong(2), video)
        }
        return Page(items, data.text(1))
    }

    /** File names by photo key, as the page's "EWgK9e" call returns them. */
    fun parseNames(data: JSONArray): Map<String, String> {
        val list = data.optJSONArray(0)?.optJSONArray(1) ?: return emptyMap()
        return (0 until list.length()).mapNotNull { i ->
            val item = list.optJSONArray(i) ?: return@mapNotNull null
            val name = item.optJSONArray(1)?.text(3) ?: return@mapNotNull null
            (item.text(0) ?: return@mapNotNull null) to name
        }.toMap()
    }

    // optString gives the text "null" for an empty field
    private fun JSONArray.text(index: Int): String? = if (isNull(index)) null else optString(index).ifEmpty { null }
}

/** The login into Google Photos is missing or has run out. */
class NeedsLogin : IOException("Přihlas se znovu do Google Fotek")

/** File names of photos seen before, so Google is asked for each only once. */
class WebNames(private val file: File) {
    private val names = HashMap<String, String>()

    init {
        if (file.exists()) file.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size == 2) names[parts[0]] = parts[1]
        }
    }

    operator fun get(key: String) = names[key]

    fun add(found: Map<String, String>) {
        if (found.isEmpty()) return
        val clean = found.mapValues { it.value.replace(Regex("[\t\n]"), " ") }
        names += clean
        file.appendText(clean.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }
}

/**
 * A signed-in visit of photos.google.com. [cookies] gives the login cookies for an address and [keep]
 * saves those an address sets; opening fails with [NeedsLogin] when Google does not see a signed-in user.
 */
class WebSession(
    private val cookies: (String) -> String?,
    private val agent: String,
    private val keep: (String, String) -> Unit = { _, _ -> },
) {
    private val home = String(read(get(WebPhotos.site + "/")))
    private val token = field("SNlM0e") ?: throw NeedsLogin()
    private val path = field("eptZe") ?: "/_/PhotosUi/"
    private val query = listOf("source-path" to "/", "f.sid" to field("FdrFJe"), "bl" to field("cfb2h"), "pageId" to "none", "rt" to "c")
        .filter { it.second != null }
        .joinToString("") { "&${it.first}=${encode(it.second!!)}" }

    fun page(next: String?): WebPhotos.Page =
        WebPhotos.parsePage(call("lcxiM", JSONArray(listOf(next, null, WebPhotos.PAGE_SIZE, null, 1, 1)).toString()))

    fun names(keys: List<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val wanted = JSONArray(keys.map { JSONArray(listOf(it)) })
        return WebPhotos.parseNames(call("EWgK9e", "[[[$wanted],[[$NAME_FIELDS]]]]"))
    }

    /** A small JPEG of the photo; fails when Google sends something else than a picture. */
    fun preview(url: String): ByteArray {
        val connection = get(url)
        val bytes = read(connection)
        return CloudLibrary.shrink(bytes)
            ?: throw IOException("Google místo obrázku poslal ${connection.contentType} (${connection.url.host}, ${bytes.size / 1024} kB)")
    }

    /**
     * Follows redirects by hand, as a browser does: each address gets its own cookies and those it sets
     * are kept. The pictures are on another address than the page and Google signs it in on the way.
     */
    private fun get(url: String): HttpURLConnection {
        var address = url
        repeat(10) {
            val connection = open(address).apply { instanceFollowRedirects = false }
            connection.headerFields.forEach { (name, values) ->
                if (name.equals("Set-Cookie", ignoreCase = true)) values.forEach { keep(address, it) }
            }
            val next = connection.getHeaderField("Location")
            if (connection.responseCode !in 300..399 || next == null) return connection
            address = URL(URL(address), next).toString()
        }
        throw IOException("Příliš mnoho přesměrování (${URL(address).host})")
    }

    /** One call of the page's own interface; the answer is wrapped in a line starting with "wrb.fr". */
    private fun call(id: String, data: String): JSONArray {
        val request = JSONArray().put(JSONArray().put(JSONArray(listOf(id, data, null, "generic"))))
        val connection = open("${WebPhotos.site}${path}data/batchexecute?rpcids=$id$query").apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        }
        connection.outputStream.use { it.write("f.req=${encode(request.toString())}&at=${encode(token)}&".toByteArray()) }
        val line = String(read(connection)).lineSequence().firstOrNull { "wrb.fr" in it }
        val answer = runCatching { JSONArray(line).getJSONArray(0) }.getOrNull()
        if (answer == null || answer.isNull(2)) throw IOException("Google Fotky odpověděly jinak, než aplikace čeká ($id)")
        return JSONArray(answer.getString(2))
    }

    private fun field(name: String): String? =
        Regex("\"$name\":\"([^\"]*)\"").find(home)?.groupValues?.get(1)?.replace("\\/", "/")?.ifEmpty { null }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 60_000
        setRequestProperty("User-Agent", agent)
        setRequestProperty("Referer", WebPhotos.site + "/")
        cookies(url)?.let { setRequestProperty("Cookie", it) }
    }

    private fun read(connection: HttpURLConnection): ByteArray {
        if (connection.responseCode !in 200..299) throw HttpException(connection.responseCode, connection.url.host)
        return connection.inputStream.use { it.readBytes() }
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private companion object {
        // Which fields of a photo the page asks for; taken over as the page sends it
        val NAME_FIELDS = (List(24) { "null" } + "[]" + List(10) { "null" } + "[]").joinToString(",")
    }
}

/** Reads the library once after the login; tried again daily on Wi-Fi and when the app is opened until it gets through. */
class WebPhotosWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!Prefs.get(context).webPhotos.value || !onWifi(context)) return@withContext Result.success()
        runCatching {
            setForeground(
                context.progressNotice(
                    "web-photos", "Náhledy z Google Fotek", 3, "Načítám náhledy z Google Fotek", "Ukládám jen malé náhledy", 0, 0,
                    android.R.drawable.stat_sys_download,
                ),
            )
        }
        val library = CloudLibrary(context)
        if (library.repair()) CloudPhotos.version.value++
        val onPhone = MediaRepository(context).loadAll().map { it.name to it.date / 60_000 }.toSet()
        WebPhotos.progress.value = WebPhotos.Progress()
        val result = try {
            val jar = CookieManager.getInstance()
            val session = WebSession(jar::getCookie, WebPhotos.agent(context)) { url, cookie -> jar.setCookie(url, cookie) }
            WebPhotos.sync(session, library, WebNames(File(context.filesDir, "cloud/web-names.tsv")), onPhone, { isStopped }) {
                WebPhotos.progress.value = it
                // New previews show up while the rest is still loading
                if (it.added > 0) CloudPhotos.version.value++
            }
        } catch (e: Exception) {
            WebPhotos.Progress(done = true, error = e.message?.take(120) ?: "Nepodařilo se načíst")
        }
        // The login is needed only until the whole library was read once
        if (result.done && result.error == null) {
            WebPhotos.forgetLogin()
            Prefs.get(context).setWebPhotos(false)
        }
        // Stopped halfway: the next run goes on, there is nothing to report
        WebPhotos.progress.value = result.takeIf { it.done }
        CloudPhotos.version.value++
        Result.success()
    }

    companion object {
        fun schedule(context: Context) = scheduleOnWifi<WebPhotosWorker>(context, "web-photos", 24, Prefs.get(context).webPhotos.value)
    }
}
