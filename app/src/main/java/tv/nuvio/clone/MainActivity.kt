package tv.nuvio.clone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ==========================================
// 1. DATA MODELS
// ==========================================
data class TmdbResponse<T>(val results: List<T>)

data class MediaItem(
    val id: Int,
    @SerializedName("title", alternate = ["name"]) val title: String,
    @SerializedName("overview") val overview: String,
    @SerializedName("poster_path") val posterPath: String?,
    @SerializedName("backdrop_path") val backdropPath: String?,
    @SerializedName("vote_average") val voteAverage: Double
) {
    val fullPosterUrl: String get() = "https://image.tmdb.org/t/p/w500$posterPath"
    val fullBackdropUrl: String get() = "https://image.tmdb.org/t/p/original$backdropPath"
}

data class StreamSource(
    val title: String,
    val resolution: String,
    val streamUrl: String
)

// ==========================================
// 2. NETWORK API (TMDB)
// ==========================================
interface TmdbApi {
    @GET("trending/movie/week")
    suspend fun getTrendingMovies(@Query("api_key") apiKey: String): TmdbResponse<MediaItem>

    @GET("trending/tv/week")
    suspend fun getTrendingShows(@Query("api_key") apiKey: String): TmdbResponse<MediaItem>

    companion object {
        fun create(): TmdbApi = Retrofit.Builder()
            .baseUrl("https://api.themoviedb.org/3/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)
    }
}

// ==========================================
// 3. THEME
// ==========================================
val ObsidianBlack = Color(0xFF0D0F12)
val SurfaceDark = Color(0xFF161920)
val ElectricCyan = Color(0xFF00E5FF)
val TextWhite = Color(0xFFF0F3F6)

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    background = ObsidianBlack,
    surface = SurfaceDark,
    onBackground = TextWhite,
    onSurface = TextWhite
)

@Composable
fun NuvioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}

// ==========================================
// 4. MAIN ACTIVITY & UI
// ==========================================
class MainActivity : ComponentActivity() {

    private val TMDB_API_KEY = "YOUR_TMDB_API_KEY_HERE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NuvioTheme {
                var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
                var movies by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
                var shows by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

                LaunchedEffect(Unit) {
                    try {
                        val api = TmdbApi.create()
                        movies = api.getTrendingMovies(TMDB_API_KEY).results
                        shows = api.getTrendingShows(TMDB_API_KEY).results
                    } catch (t: Throwable) {
                        // Fallback sample catalog if no internet / key missing
                        movies = listOf(
                            MediaItem(1, "Big Buck Bunny", "An open-source animated film.", null, null, 8.5),
                            MediaItem(2, "Tears of Steel", "Open-source VFX project.", null, null, 7.9)
                        )
                        shows = listOf(
                            MediaItem(3, "Cosmos Laundromat", "Open-source pilot series.", null, null, 8.0)
                        )
                    }
                }

                if (selectedMedia == null) {
                    HomeScreen(
                        trendingMovies = movies,
                        trendingShows = shows,
                        onMediaSelected = { selectedMedia = it }
                    )
                } else {
                    DetailScreen(
                        media = selectedMedia!!,
                        onBack = { selectedMedia = null },
                        onStreamSelected = { stream ->
                            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                                putExtra("STREAM_URL", stream.streamUrl)
                                putExtra("TITLE", selectedMedia!!.title)
                            }
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. SCREENS (HOME & DETAIL)
// ==========================================
@Composable
fun HomeScreen(
    trendingMovies: List<MediaItem>,
    trendingShows: List<MediaItem>,
    onMediaSelected: (MediaItem) -> Unit
) {
    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NUVIO",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black
                    )
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            if (trendingMovies.isNotEmpty()) {
                item {
                    val hero = trendingMovies.first()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onMediaSelected(hero) }
                    ) {
                        if (hero.backdropPath != null) {
                            AsyncImage(
                                model = hero.fullBackdropUrl,
                                contentDescription = hero.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text(hero.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("★ ${String.format("%.1f", hero.voteAverage)}", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                MediaSection("Trending Movies", trendingMovies, onMediaSelected)
            }
            item {
                MediaSection("Top TV Series", trendingShows, onMediaSelected)
            }
        }
    }
}

@Composable
fun MediaSection(title: String, mediaList: List<MediaItem>, onMediaClick: (MediaItem) -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(mediaList) { item ->
                Column(
                    modifier = Modifier
                        .width(130.dp)
                        .clickable { onMediaClick(item) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        if (item.posterPath != null) {
                            AsyncImage(
                                model = item.fullPosterUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(
    media: MediaItem,
    onBack: () -> Unit,
    onStreamSelected: (StreamSource) -> Unit
) {
    val streams = listOf(
        StreamSource("Big Buck Bunny (Direct HLS)", "1080p", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
        StreamSource("Tears of Steel (Direct MP4)", "4K", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4")
    )

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = onBack) { Text("← Back") }
        Spacer(modifier = Modifier.height(16.dp))
        Text(media.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(media.overview, color = Color.Gray)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Available Streams:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        streams.forEach { stream ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .clickable { onStreamSelected(stream) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(stream.title, fontWeight = FontWeight.Medium)
                        Text(stream.resolution, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("▶ Play", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ==========================================
// 6. VIDEO PLAYER ACTIVITY (EXOPLAYER)
// ==========================================
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamUrl = intent.getStringExtra("STREAM_URL") ?: return finish()

        setContent {
            val context = this
            val exoPlayer = remember {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(ExoMediaItem.fromUri(Uri.parse(streamUrl)))
                    prepare()
                    playWhenReady = true
                }
            }

            DisposableEffect(Unit) {
                onDispose { exoPlayer.release() }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize().background(Color.Black)
            )
        }
    }
}
