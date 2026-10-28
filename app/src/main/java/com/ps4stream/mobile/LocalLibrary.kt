package com.ps4stream.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Private device library. No account or permissions required. */
class LocalLibrary(context: Context) {
    private val p=context.getSharedPreferences("ps4stream_library", Context.MODE_PRIVATE)
    fun read(key: String): List<String> {
        val a=runCatching { JSONArray(p.getString(key,"[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }
    fun has(key:String,url:String) = read(key).contains(url)
    fun toggle(key: String, url: String): Boolean {
        val before=read(key).toMutableList()
        val added=!before.contains(url)
        if (added) before.add(0,url) else before.remove(url)
        set(key,before.take(200))
        return added
    }
    fun push(key:String,value:String) {
        val values=(listOf(value)+read(key).filterNot{it==value}).take(70)
        set(key,values)
    }
    fun set(key:String,items:List<String>) {
        val a=JSONArray();items.forEach{a.put(it)}
        p.edit().putString(key,a.toString()).apply()
    }
    fun getString(key:String,default:String="")=p.getString(key,default) ?: default
    fun putString(key:String,v:String)=p.edit().putString(key,v).apply()
    fun storeMovie(m:CatalogClient.Movie) {
        val encoded=JSONObject().put("url",m.url).put("title",m.title).put("poster",m.poster)
            .put("year",m.year).put("country",m.country).put("rating",m.rating)
            .put("genre",m.genre).put("category",m.category)
        p.edit().putString("movie_${digest(m.url)}",encoded.toString()).apply()
    }
    private fun digest(value:String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun movie(url:String):CatalogClient.Movie? = runCatching {
        val o=JSONObject(p.getString("movie_${digest(url)}", "") ?: "")
        CatalogClient.Movie(url,o.getString("title"),o.optString("poster"),o.optString("year"),
            o.optString("country"),o.optString("rating"),o.optString("genre"),o.optString("category"))
    }.getOrNull()
    fun exportJson():String {
        val data=JSONObject().put("version",1)
        for (key in listOf("favorites","watchlist","history","searches","sent")) {
            val a=JSONArray();read(key).forEach{a.put(it)};data.put(key,a)
        }
        val movies=JSONObject()
        (read("favorites")+read("watchlist")+read("history")).distinct().take(300).forEach { url ->
            val o=p.getString("movie_${digest(url)}",null)
            if(o!=null) movies.put(url,JSONObject(o))
        }
        data.put("movies",movies)
        return data.toString(2)
    }
    fun restoreJson(json:String) {
        val doc=JSONObject(json)
        require(doc.optInt("version")==1) {"Неподдерживаемый файл библиотеки"}
        for (key in listOf("favorites","watchlist","history","searches","sent")) {
            val a=doc.optJSONArray(key) ?: JSONArray()
            set(key,(0 until a.length()).map { a.optString(it) }.filter{it.length<=700}.take(200))
        }
        val movies=doc.optJSONObject("movies") ?: JSONObject()
        val keys=movies.keys()
        while(keys.hasNext()) {
            val url=keys.next()
            if(url.startsWith("https://") && url.length<=700) {
                val o=movies.optJSONObject(url)
                if(o!=null) p.edit().putString("movie_${digest(url)}",o.toString()).apply()
            }
        }
    }
}
