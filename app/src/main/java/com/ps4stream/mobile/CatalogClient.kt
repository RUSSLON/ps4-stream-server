package com.ps4stream.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStreamEncoder
import java.net.URL
import java.io.ByteArrayOutputStream

/** All calls run off the main thread. Catalog service is configured in Settings. */
object CatalogClient {
    data class Movie(
        val url: String, val title: String, val poster: String, val year: String,
        val country: String, val rating: String, val genre: String, val category: String
    )
    data class Detail(
        val movie: Movie, val description: String, val originalTitle: String,
        val director: String, val actors: List<String>, val trailerUrl: String,
        val voices: List<String>, val serial: Boolean, val recommended: List<Movie>
    )
    data class Source(val title: String, val quality: String, val url: String, val pageUrl: String)
    data class Page(val items: List<Movie>, val hasMore: Boolean)
    data class Home(val fresh: List<Movie>, val films: List<Movie>, val series: List<Movie>)

    fun baseUrl(raw: String): String {
        val uri = URI(raw.trim().trimEnd('/'))
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.path.orEmpty() in listOf("", "/") &&
            uri.query == null && uri.fragment == null) { "Укажи HTTPS-адрес сервера каталога, например https://имя.onrender.com" }
        return "https://${uri.host.lowercase()}"
    }
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun request(base: String, route: String, key: String=""): JSONObject {
        val target = URL(baseUrl(base) + route)
        val c = target.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 24000
        c.instanceFollowRedirects = false
        c.setRequestProperty("Accept", "application/json")
        if(key.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = c.responseCode
            val source = if (code in 200..299) c.inputStream else c.errorStream
            val bytes = source?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 131072) { "Слишком большой ответ сервера" }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (code !in 200..299) throw IllegalStateException(json.optString("error", "HTTP $code"))
            return json
        } finally { c.disconnect() }
    }
    private fun movie(o: JSONObject) = Movie(
        o.optString("url"), o.optString("title"), o.optString("poster"),
        o.optString("year"), o.optString("country"), o.optString("rating"),
        o.optString("genre"), o.optString("category")
    )
    private fun items(a: JSONArray?): List<Movie> = if (a == null) emptyList() else (0 until a.length())
        .mapNotNull { a.optJSONObject(it)?.let(::movie) }.filter { it.url.startsWith("https://") }
    fun home(base: String): Home {
        val o=request(base,"/api/home")
        return Home(items(o.optJSONArray("new")), items(o.optJSONArray("films")), items(o.optJSONArray("series")))
    }
    fun browse(base: String, category: String, page: Int): Page {
        val o=request(base,"/api/catalog?category=${enc(category)}&page=$page")
        return Page(items(o.optJSONArray("items")), o.optBoolean("hasMore", false))
    }
    fun search(base: String, q: String, page: Int): Page {
        val o=request(base,"/api/search?q=${enc(q)}&page=$page")
        return Page(items(o.optJSONArray("items")), o.optBoolean("hasMore", false))
    }
    fun detail(base: String, url: String): Detail {
        val o=request(base,"/api/detail?url=${enc(url)}")
        val voices = o.optJSONArray("voices")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
        } ?: emptyList()
        val actors = o.optJSONArray("actors")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        } ?: emptyList()
        return Detail(movie(o), o.optString("description"), o.optString("originalTitle"),
            o.optString("director"), actors, o.optString("trailerUrl"), voices,
            o.optBoolean("serial"), items(o.optJSONArray("recommended")))
    }
    fun source(base: String, url: String, quality: String, voice: String, apiKey: String): Source {
        val o=request(base,"/api/stream?url=${enc(url)}&quality=${enc(quality)}&voice=${enc(voice)}", apiKey)
        val direct = o.optString("url")
        val parsed = URI(direct)
        require(parsed.scheme == "https" && parsed.host != null) { "Источник не вернул HTTPS-видеоссылку" }
        return Source(o.optString("title"), o.optString("quality"), direct, o.optString("pageUrl"))
    }
}
