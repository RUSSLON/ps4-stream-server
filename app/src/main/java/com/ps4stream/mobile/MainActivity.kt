package com.ps4stream.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private val Purple=Color(0xFF9D78FC)
private val Black=Color(0xFF0E0E19)
private val TileBg=Color(0xFF1B1B2B)
private val Dim=Color(0xFFB7B3C8)
private val Categories=listOf("films" to "Фильмы", "series" to "Сериалы", "cartoons" to "Мультфильмы", "anime" to "Аниме")
private val QualityChoices=listOf("1080p Ultra","1080p","720p","480p","360p")

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=android.graphics.Color.rgb(14,14,25)
        window.navigationBarColor=android.graphics.Color.rgb(14,14,25)
        setContent { PS4Mobile(this) }
    }
    fun legacy(url:String="") {
        startActivity(Intent(this,LegacyActivity::class.java).putExtra("pageUrl",url))
    }
    fun toast(msg:String) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show() }
}

@Composable
private fun PS4Mobile(activity:MainActivity) {
    val library=remember { LocalLibrary(activity) }
    val secrets=remember { SecureKeyStore(activity) }
    val prefs=remember { activity.getSharedPreferences("ps4_mobile_v3",Activity.MODE_PRIVATE) }
    var catalogUrl by remember { mutableStateOf(prefs.getString("catalog_server", "") ?: "") }
    var apiKey by remember { mutableStateOf(secrets.read()) }
    var tab by remember { mutableIntStateOf(0) }
    var currentFilm by remember { mutableStateOf<CatalogClient.Movie?>(null) }
    var detail by remember { mutableStateOf<CatalogClient.Detail?>(null) }
    var category by remember { mutableStateOf("films") }
    var query by remember { mutableStateOf("") }
    var home by remember { mutableStateOf<CatalogClient.Home?>(null) }
    var results by remember { mutableStateOf<List<CatalogClient.Movie>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var more by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var favoriteVersion by remember { mutableIntStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var pinIssued by remember { mutableLongStateOf(0L) }
    var genre by remember { mutableStateOf(library.getString("filter_genre")) }
    var year by remember { mutableStateOf(library.getString("filter_year")) }
    var country by remember { mutableStateOf(library.getString("filter_country")) }
    var rating by remember { mutableStateOf(library.getString("filter_rating")) }
    var voiceFilter by remember { mutableStateOf(library.getString("filter_voice")) }
    var filtersOpen by remember { mutableStateOf(false) }
    var lastMovie by remember { mutableStateOf<CatalogClient.Movie?>(null) }
    val movies=remember { mutableStateMapOf<String,CatalogClient.Movie>() }
    val scope=rememberCoroutineScope()
    val configured=catalogUrl.isNotBlank()
    val cols=max(2,(LocalConfiguration.current.screenWidthDp/158))

    fun openMovie(m:CatalogClient.Movie) {
        movies[m.url]=m
        library.storeMovie(m)
        currentFilm=m; detail=null;error=""
        library.push("history",m.url)
        favoriteVersion++
    }
    fun saveFilter() {
        listOf("filter_genre" to genre,"filter_year" to year,"filter_country" to country,
            "filter_rating" to rating,"filter_voice" to voiceFilter).forEach { (k,v)->library.putString(k,v) }
    }
    fun startRequest(block:suspend ()->Unit) {
        scope.launch {
            loading=true;error=""
            try {block()} catch(e:Exception) {error=e.message ?: "Не удалось загрузить данные"}
            finally {loading=false}
        }
    }
    fun loadCategory(next:Int=1) {
        if (!configured) return
        startRequest {
            val response=withContext(Dispatchers.IO){CatalogClient.browse(catalogUrl,category,next)}
            response.items.forEach{movies[it.url]=it}
            if (next==1) results=response.items else results=(results+response.items).distinctBy{it.url}
            page=next;more=response.hasMore
        }
    }
    fun playOnPhone(d:CatalogClient.Detail, voice:String, quality:String) {
        if (apiKey.length < 12) {activity.toast("Сначала сохрани API_KEY в разделе PS4");tab=4;return}
        startRequest {
            val src=withContext(Dispatchers.IO){CatalogClient.source(catalogUrl,d.movie.url,quality,voice,apiKey)}
            library.push("history",d.movie.url);library.putString("quality",quality)
            library.putString("voice_${d.movie.url}",voice)
            VlcLauncher.open(activity,src.url)
        }
    }
    fun sendPS4(d:CatalogClient.Detail, voice:String, quality:String) {
        if (apiKey.isBlank()) { activity.toast("В настройках нужен API_KEY от Render");tab=4;return }
        startRequest {
            val src=withContext(Dispatchers.IO){CatalogClient.source(catalogUrl,d.movie.url,quality,voice,apiKey)}
            val result=withContext(Dispatchers.IO){ RenderClient.send(
                RenderClient.Media(src.title,src.quality,src.url, emptyList(),src.pageUrl),
                emptyList(),apiKey) }
            pin=result.pin;pinIssued=System.currentTimeMillis()
            library.push("sent", "${System.currentTimeMillis()}|${d.movie.title}|${result.pin}")
            library.push("history",d.movie.url)
            lastMovie=d.movie
            activity.toast("Видео отправлено. PIN: ${result.pin}")
            tab=4
            currentFilm=null
        }
    }

    LaunchedEffect(catalogUrl) {
        if (configured) {
            loading=true;error=""
            try {
                val data=withContext(Dispatchers.IO){CatalogClient.home(catalogUrl)}
                home=data; (data.fresh+data.films+data.series).forEach {movies[it.url]=it}
            } catch(e:Exception) {error=e.message ?: "Ошибка загрузки каталога"}
            finally {loading=false}
        }
    }
    LaunchedEffect(category, tab, catalogUrl) {
        if (configured && tab==1 && currentFilm==null) {
            loading=true;error=""
            try {
                val response=withContext(Dispatchers.IO){CatalogClient.browse(catalogUrl,category,1)}
                results=response.items; response.items.forEach{movies[it.url]=it}
                page=1;more=response.hasMore
            } catch(e:Exception) {error=e.message ?: "Ошибка каталога"}
            finally {loading=false}
        }
    }
    LaunchedEffect(query,tab,catalogUrl) {
        if (tab!=2 || !configured) return@LaunchedEffect
        delay(380)
        if(query.trim().length<2) {results=emptyList();return@LaunchedEffect}
        loading=true;error=""
        try {
            val r=withContext(Dispatchers.IO){CatalogClient.search(catalogUrl,query,1)}
            results=r.items;r.items.forEach{movies[it.url]=it};page=1;more=r.hasMore
        } catch(e:Exception){error=e.message?:"Ошибка поиска"}
        finally{loading=false}
    }
    LaunchedEffect(currentFilm?.url,catalogUrl) {
        val m=currentFilm ?:return@LaunchedEffect
        if (!configured)return@LaunchedEffect
        loading=true;error=""
        try{detail=withContext(Dispatchers.IO){CatalogClient.detail(catalogUrl,m.url)} }
        catch(e:Exception){error=e.message ?: "Детали недоступны"}
        finally{loading=false}
    }
    BackHandler(currentFilm!=null){currentFilm=null;detail=null;error=""}

    val dark= androidx.compose.foundation.isSystemInDarkTheme()
    val bg=if(dark)Black else Color(0xFFF7F4FF)
    val textColor=if(dark)Color.White else Color(0xFF19162C)
    val cardColor=if(dark)TileBg else Color(0xFFEAE4F7)
    MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Purple,background=Black,surface=TileBg,onSurface=Color.White) else lightColorScheme(primary=Color(0xFF6A43D3))) {
        Scaffold(
            containerColor=bg,
            bottomBar={ NavigationBar(containerColor=cardColor) {
                listOf(Triple("Главная",Icons.Rounded.Home,0),Triple("Каталог",Icons.Rounded.GridView,1),
                    Triple("Поиск",Icons.Rounded.Search,2),Triple("Моё",Icons.Rounded.Favorite,3),
                    Triple("PS4",Icons.Rounded.Tv,4)).forEach {(name,ico,index)->
                    NavigationBarItem(selected=(tab==index && currentFilm==null),onClick={tab=index;currentFilm=null;detail=null;error=""},
                        icon={Icon(ico,contentDescription=name)},label={Text(name, fontSize=10.sp)},alwaysShowLabel=true)
                }
            } },
            topBar={Row(modifier=Modifier.fillMaxWidth().background(bg).padding(horizontal=16.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically) {
                if(currentFilm!=null) IconButton(onClick={currentFilm=null;detail=null}){Icon(Icons.Rounded.ArrowBack,"Назад",tint=textColor)}
                Text("◆ PS4 STREAM", color=textColor,fontWeight=FontWeight.Black, fontSize=19.sp, modifier=Modifier.weight(1f))
                IconButton(onClick={currentFilm=null;tab=4}){Icon(Icons.Rounded.Settings,"Настройки",tint=Purple)}
            }}
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets)) {
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Purple)
                if(error.isNotBlank()) Surface(color=Color(0xFF45273C),modifier=Modifier.padding(10.dp),shape=RoundedCornerShape(12.dp)) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Text(error,color=Color.White,modifier=Modifier.weight(1f),fontSize=13.sp)
                        IconButton(onClick={error=""}){Icon(Icons.Rounded.Close,"Закрыть",tint=Color.White)}
                    }
                }
                if(currentFilm!=null) {
                    FilmScreen(currentFilm!!,detail,library,favoriteVersion,
                        color=textColor,cardColor=cardColor,
                        onFavorite={library.toggle("favorites",currentFilm!!.url);favoriteVersion++},
                        onWatchlist={library.toggle("watchlist",currentFilm!!.url);favoriteVersion++},
                        onLegacy={activity.legacy(currentFilm!!.url)},
                        onTrailer={u->runCatching{activity.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(u))) }},
                        onPhone={d,v,q->playOnPhone(d,v,q)},onPS4={d,v,q->sendPS4(d,v,q)},
                        onOpen={openMovie(it)})
                    return@Column
                }
                if(!configured && tab!=4) {
                    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Movie,contentDescription=null,modifier=Modifier.size(68.dp),tint=Purple)
                        Spacer(Modifier.height(16.dp))
                        Text("Каталог почти готов",fontSize=23.sp,fontWeight=FontWeight.Bold,color=textColor)
                        Spacer(Modifier.height(9.dp))
                        Text("Укажи HTTPS-адрес Python-сервера каталога в настройках. PIN-сервер PS4 останется прежним.",color=Dim)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick={tab=4}){Text("Открыть настройки")}
                        TextButton(onClick={activity.legacy()}){Text("Пока открыть сайт в прежнем режиме")}
                    }
                } else when(tab) {
                    0 -> HomeScreen(home,cols,textColor,cardColor,{openMovie(it)},{tab=1},library)
                    1 -> Column {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                            Categories.forEach { (key,name)->FilterChip(selected=category==key,onClick={category=key},label={Text(name)})}
                        }
                        Row(Modifier.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text("Каталог",fontSize=24.sp,fontWeight=FontWeight.Bold,color=textColor,modifier=Modifier.weight(1f))
                            TextButton(onClick={filtersOpen=!filtersOpen}){Icon(Icons.Rounded.Tune,null);Text("Фильтры")}
                        }
                        if(filtersOpen)FilterPanel(genre,year,country,rating,voiceFilter,
                            {genre=it},{year=it},{country=it},{rating=it},{voiceFilter=it},{saveFilter()})
                        val filtered=results.filter { m ->
                            (genre.isBlank()||m.genre.contains(genre,true)) &&
                            (year.isBlank()||m.year.contains(year)) &&
                            (country.isBlank()||m.country.contains(country,true)) &&
                            (rating.isBlank()||m.rating.toDoubleOrNull()?.let{it >= (rating.toDoubleOrNull()?:0.0)}==true)
                        }
                        GridResults(filtered,cols,cardColor,textColor,{openMovie(it)},more&&!loading,{loadCategory(page+1)})
                        if(voiceFilter.isNotBlank())Text("Фильтр озвучки применяется при открытии фильма; каталог не предоставляет эту информацию для всех карточек.",color=Dim,modifier=Modifier.padding(12.dp),fontSize=11.sp)
                    }
                    2 -> Column {
                        OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth().padding(14.dp),
                            placeholder={Text("Название фильма или сериала")},singleLine=true,
                            leadingIcon={Icon(Icons.Rounded.Search,null)},
                            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                            keyboardActions=KeyboardActions(onSearch={if(query.isNotBlank())library.push("searches",query)}))
                        if(query.isBlank()) {
                            Text("История поиска",fontSize=18.sp,fontWeight=FontWeight.Bold,color=textColor,modifier=Modifier.padding(14.dp))
                            library.read("searches").take(12).forEach { q->TextButton(onClick={query=q}){Text("◷  $q")} }
                        } else {
                            GridResults(results,cols,cardColor,textColor,{ library.push("searches",query.trim());openMovie(it) },more&&!loading,
                                {startRequest {val r=withContext(Dispatchers.IO){CatalogClient.search(catalogUrl,query,page+1)};results+=r.items;page++;more=r.hasMore}})
                        }
                    }
                    3 -> {
                        val saved=library.read("favorites")
                        val future=library.read("watchlist")
                        val history=library.read("history")
                        LazyColumn(Modifier.fillMaxSize()) {
                            item{SectionHeading("Моё кино",textColor)}
                            item{SectionHeading("Избранное · ${saved.size}",textColor)}
                            items(saved){url -> SmallSaved(url,movies[url]?:library.movie(url),textColor,{openMovie(movies[url]?:library.movie(url)?:CatalogClient.Movie(url,url.substringAfterLast('/'),"","","","","",""))})}
                            item{SectionHeading("Буду смотреть · ${future.size}",textColor)}
                            items(future){url -> SmallSaved(url,movies[url]?:library.movie(url),textColor,{openMovie(movies[url]?:CatalogClient.Movie(url,url.substringAfterLast('/'),"","","","",""))})}
                            item{SectionHeading("История · ${history.size}",textColor)}
                            items(history.take(30)){url -> SmallSaved(url,movies[url]?:library.movie(url),textColor,{openMovie(movies[url]?:CatalogClient.Movie(url,url.substringAfterLast('/'),"","","","",""))})}
                        }
                    }
                    4 -> SettingsScreen(activity,library,prefs,catalogUrl,{url->catalogUrl=url;home=null;results=emptyList()},
                        apiKey,{apiKey=it;secrets.write(it)},pin,pinIssued, {activity.legacy()},
                        {startRequest{
                            val media=RenderClient.Media("MDN · Демо-видео","MP4", "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",emptyList(),"https://developer.mozilla.org/")
                            val res=withContext(Dispatchers.IO){RenderClient.send(media,emptyList(),apiKey)}
                            pin=res.pin;pinIssued=System.currentTimeMillis();library.push("sent","${pinIssued}|Демо-видео|${pin}")
                        }})
                }
            }
        }
    }
}

@Composable private fun SectionHeading(title:String,color:Color) {
    Text(title,modifier=Modifier.padding(start=16.dp,end=14.dp,top=16.dp,bottom=9.dp),
        color=color,fontSize=21.sp,fontWeight=FontWeight.Bold)
}

@Composable private fun Poster(m:CatalogClient.Movie,cardColor:Color,textColor:Color,onClick:()->Unit,modifier:Modifier=Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(cardColor).clickable(onClick=onClick).padding(bottom=9.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.70f)) {
            AsyncImage(model=m.poster.ifBlank{null},contentDescription=m.title,modifier=Modifier.fillMaxSize().background(Color(0xFF27253B)),contentScale=ContentScale.Crop)
            if(m.rating.isNotBlank()) Text(m.rating.take(12),modifier=Modifier.align(Alignment.TopEnd)
                .padding(7.dp).background(Color(0xD9000000),RoundedCornerShape(8.dp)).padding(horizontal=7.dp,vertical=4.dp),
                color=Color.White,fontWeight=FontWeight.Bold,fontSize=11.sp)
        }
        Text(m.title,modifier=Modifier.padding(start=8.dp,end=8.dp,top=7.dp),color=textColor,fontWeight=FontWeight.SemiBold,
            fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(listOf(m.year,m.country).filter{it.isNotBlank()}.joinToString(" · "),modifier=Modifier.padding(horizontal=8.dp),
            color=Dim,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable private fun HomeScreen(home:CatalogClient.Home?,cols:Int,textColor:Color,cardColor:Color,
    onMovie:(CatalogClient.Movie)->Unit,onCatalog:()->Unit,library:LocalLibrary) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionHeading("Новые фильмы и сериалы",textColor) }
        item {
            if (home?.fresh.isNullOrEmpty()) Text("Здесь появятся новые фильмы после подключения каталога.",color=Dim,modifier=Modifier.padding(16.dp))
            else LazyRow(contentPadding=PaddingValues(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                items(home!!.fresh.take(18)){m->Poster(m,cardColor,textColor,{onMovie(m)},Modifier.width(146.dp))}
            }
        }
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)){SectionHeading("Фильмы",textColor)}
            TextButton(onClick=onCatalog){Text("Все фильмы")}
        }}
        item {LazyRow(contentPadding=PaddingValues(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            items(home?.films.orEmpty().take(12)){m->Poster(m,cardColor,textColor,{onMovie(m)},Modifier.width(146.dp))}
        }}
        item{SectionHeading("Сериалы",textColor)}
        item{LazyRow(contentPadding=PaddingValues(horizontal=14.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
            items(home?.series.orEmpty().take(12)){m->Poster(m,cardColor,textColor,{onMovie(m)},Modifier.width(146.dp))}
        }}
        item {Spacer(Modifier.height(24.dp))}
    }
}

@Composable private fun GridResults(data:List<CatalogClient.Movie>,cols:Int,cardColor:Color,textColor:Color,
    open:(CatalogClient.Movie)->Unit,hasMore:Boolean,loadMore:()->Unit) {
    val state=rememberLazyGridState()
    val shouldLoad by remember(state,hasMore,data.size){derivedStateOf {
        hasMore && data.isNotEmpty() && state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let{it>=data.size-cols*2}==true
    }}
    LaunchedEffect(shouldLoad,data.size) {if(shouldLoad)loadMore()}
    LazyVerticalGrid(columns=GridCells.Fixed(cols),state=state,
        modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        items(data,key={it.url}){m->Poster(m,cardColor,textColor,{open(m)})}
        if(hasMore) item { TextButton(onClick=loadMore){Text("Загрузить ещё")}}
    }
}

@Composable private fun FilterPanel(genre:String,year:String,country:String,rating:String,voice:String,
    setGenre:(String)->Unit,setYear:(String)->Unit,setCountry:(String)->Unit,setRating:(String)->Unit,
    setVoice:(String)->Unit,save:()->Unit) {
    Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value=genre,onValueChange=setGenre,label={Text("Жанр")},singleLine=true)
        OutlinedTextField(value=year,onValueChange=setYear,label={Text("Год")},singleLine=true)
        OutlinedTextField(value=country,onValueChange=setCountry,label={Text("Страна")},singleLine=true)
        OutlinedTextField(value=rating,onValueChange=setRating,label={Text("Рейтинг от")},singleLine=true)
        OutlinedTextField(value=voice,onValueChange=setVoice,label={Text("Предпочтительная озвучка")},singleLine=true)
        Text("Фильтры работают по данным загруженных карточек. Некоторые поля могут отсутствовать в API.",fontSize=11.sp,color=Dim)
        Button(onClick=save){Text("Запомнить фильтры")}
    }
}

@Composable private fun SmallSaved(url:String,m:CatalogClient.Movie?,color:Color,open:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(horizontal=14.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
        AsyncImage(model=m?.poster,contentDescription=null,modifier=Modifier.size(48.dp,65.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Text(m?.title ?: url.substringAfterLast('/').substringBefore('.'),color=color,fontSize=14.sp)
    }
}

@Composable private fun FilmScreen(m:CatalogClient.Movie,detail:CatalogClient.Detail?,library:LocalLibrary,favoritesVersion:Int,
    color:Color,cardColor:Color,onFavorite:()->Unit,onWatchlist:()->Unit,onLegacy:()->Unit,
    onTrailer:(String)->Unit,onPhone:(CatalogClient.Detail,String,String)->Unit,
    onPS4:(CatalogClient.Detail,String,String)->Unit,onOpen:(CatalogClient.Movie)->Unit) {
    var voice by remember(detail?.movie?.url) {mutableStateOf(library.getString("voice_${m.url}"))}
    var quality by remember {mutableStateOf(library.getString("quality","720p"))}
    var voiceExpanded by remember {mutableStateOf(false)}
    var qualityExpanded by remember {mutableStateOf(false)}
    val favorite=remember(favoritesVersion,m.url){library.has("favorites",m.url)}
    val watchlist=remember(favoritesVersion,m.url){library.has("watchlist",m.url)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=32.dp)) {
        Box(Modifier.fillMaxWidth().height(220.dp)) {
            AsyncImage(model=(detail?.movie?.poster?:m.poster),contentDescription=null,
                modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x08000000),Black))))
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(detail?.movie?.title?.ifBlank{m.title}?:m.title,color=color,fontWeight=FontWeight.Bold,fontSize=27.sp)
            Text(listOf(m.year,m.country,detail?.movie?.rating?.ifBlank{m.rating}?:m.rating,m.genre)
                .filter {it.isNotBlank()}.joinToString("   ·   "),color=Dim,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onFavorite){Icon(if(favorite)Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,null);Text(if(favorite)"В избранном" else "В избранное")}
                OutlinedButton(onClick=onWatchlist){Text(if(watchlist)"✓ Буду смотреть" else "+ Буду смотреть")}
            }
            if(detail==null) {CircularProgressIndicator(color=Purple);TextButton(onClick=onLegacy){Text("Открыть через сайт")}}
            else {
                if(detail.description.isNotBlank())Text(detail.description,color=color,fontSize=14.sp,lineHeight=22.sp)
                if(detail.director.isNotBlank())Text("Режиссёр: ${detail.director}",color=Dim,fontSize=13.sp)
                if(detail.actors.isNotEmpty())Text("Актёры: ${detail.actors.take(10).joinToString()}",color=Dim,fontSize=13.sp)
                if(detail.trailerUrl.isNotBlank())OutlinedButton(onClick={onTrailer(detail.trailerUrl)}){Icon(Icons.Rounded.SmartDisplay,null);Text("Трейлер")}
                Divider(color=cardColor)
                Text("ПРОСМОТР",color=Purple,fontSize=13.sp,fontWeight=FontWeight.Bold)
                Box {
                    OutlinedButton(onClick={voiceExpanded=true}){Text("Озвучка: ${voice.ifBlank{"По умолчанию"}}");Icon(Icons.Rounded.ArrowDropDown,null)}
                    DropdownMenu(expanded=voiceExpanded,onDismissRequest={voiceExpanded=false}) {
                        DropdownMenuItem(text={Text("По умолчанию")},onClick={voice="";voiceExpanded=false})
                        detail.voices.forEach { v->DropdownMenuItem(text={Text(v)},onClick={voice=v;voiceExpanded=false;library.putString("voice_${m.url}",v)}) }
                    }
                }
                Box {
                    OutlinedButton(onClick={qualityExpanded=true}){Text("Качество: $quality");Icon(Icons.Rounded.ArrowDropDown,null)}
                    DropdownMenu(expanded=qualityExpanded,onDismissRequest={qualityExpanded=false}) {
                        QualityChoices.forEach { v->DropdownMenuItem(text={Text(v)},onClick={quality=v;qualityExpanded=false;library.putString("quality",v)}) }
                    }
                }
                if(detail.serial) Text("Для выбора сезона и серии открой совместимый режим; там доступны все варианты с сайта и кнопка VLC.",color=Dim,fontSize=12.sp)
                Button(onClick={if(detail.serial) onLegacy() else onPhone(detail,voice,quality)},modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Смотреть на телефоне · VLC")
                }
                Button(onClick={if(detail.serial) onLegacy() else onPS4(detail,voice,quality)},modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=Purple)) {
                    Icon(Icons.Rounded.Tv,null);Spacer(Modifier.width(8.dp));Text("Смотреть на PlayStation")
                }
                OutlinedButton(onClick=onLegacy,modifier=Modifier.fillMaxWidth()) {Text("Расширенный выбор серий и субтитров")}
                if(detail.recommended.isNotEmpty()){
                    SectionHeading("Похожие фильмы",color)
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                        items(detail.recommended.filter{it.url.startsWith("https://")}) {p->Poster(p,cardColor,color,{onOpen(p)},Modifier.width(142.dp))}
                    }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(activity:MainActivity,library:LocalLibrary,
    prefs:android.content.SharedPreferences,base:String,onSaveBase:(String)->Unit,
    apiKey:String,onSaveKey:(String)->Unit,pin:String,pinIssued:Long,
    openLegacy:()->Unit,sendDemo:()->Unit) {
    var urlInput by remember(base){mutableStateOf(base)}
    var keyInput by remember {mutableStateOf(apiKey)}
    var message by remember {mutableStateOf("")}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri ->
        if(uri!=null)runCatching {activity.contentResolver.openOutputStream(uri)?.use {it.write(library.exportJson().toByteArray())}}.onFailure{message=it.message?:"Ошибка экспорта"}
    }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null)runCatching {val input=activity.contentResolver.openInputStream(uri)?:error("Нет данных")
            library.restoreJson(input.bufferedReader().use {it.readText().take(200_000)})}.onSuccess{message="Данные восстановлены"}.onFailure{message=it.message?:"Ошибка импорта"}
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("PS4 и настройки",fontSize=25.sp,fontWeight=FontWeight.Bold)
        if(pin.isNotBlank()){
            Card(colors=CardDefaults.cardColors(containerColor=Purple.copy(alpha=.27f))) {
                Column(Modifier.fillMaxWidth().padding(18.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text("Последний PIN · ${if(System.currentTimeMillis()-pinIssued<600_000)"до 10 минут" else "истёк"}",fontSize=13.sp)
                    Text(pin,fontSize=46.sp,fontWeight=FontWeight.Black,letterSpacing=7.sp)
                    Text("https://ps4-stream.onrender.com/",fontSize=12.sp)
                }
            }
        }
        Text("Сервер каталога",fontWeight=FontWeight.Bold)
        OutlinedTextField(value=urlInput,onValueChange={urlInput=it},modifier=Modifier.fillMaxWidth(),
            label={Text("HTTPS-адрес нового Catalog API")},singleLine=true)
        Button(onClick={runCatching { val normalized=CatalogClient.baseUrl(urlInput)
            prefs.edit().putString("catalog_server",normalized).apply();onSaveBase(normalized);message="Каталог подключён" }
            .onFailure{message=it.message?:"Некорректный адрес"}}){Text("Подключить каталог")}
        Text("Ключ PlayStation Render",fontWeight=FontWeight.Bold)
        OutlinedTextField(value=keyInput,onValueChange={keyInput=it},modifier=Modifier.fillMaxWidth(),
            label={Text("API_KEY (хранится в Android Keystore)")},singleLine=true,
            visualTransformation=PasswordVisualTransformation())
        Button(onClick={ if(keyInput.length>=12) {onSaveKey(keyInput);message="Ключ защищён и сохранён"}
            else message="Ключ должен содержать не менее 12 символов" }){Text("Сохранить API_KEY")}
        OutlinedButton(onClick=sendDemo,enabled=apiKey.length>=12){Text("Проверить PIN на демо-видео")}
        HorizontalDivider()
        Text("Совместимость",fontWeight=FontWeight.Bold)
        Text("Если каталог не отдал серию или ссылку, открой прежний режим. Там доступны меню озвучки, сезонов, субтитров и VLC.",fontSize=13.sp)
        OutlinedButton(onClick=openLegacy){Text("Открыть прежний режим HDrezka")}
        HorizontalDivider()
        Text("Библиотека",fontWeight=FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={create.launch("ps4stream-library.json")}){Text("Экспорт")}
            OutlinedButton(onClick={open.launch(arrayOf("application/json","text/plain"))}){Text("Импорт")}
        }
        Text("Последние отправки",fontWeight=FontWeight.Bold)
        library.read("sent").take(8).forEach{record->Text(record.substringAfter('|').substringBeforeLast('|'),fontSize=12.sp,color=Dim)}
        if(message.isNotBlank())Text(message,color=Purple,fontSize=13.sp)
        Spacer(Modifier.height(25.dp))
    }
}
