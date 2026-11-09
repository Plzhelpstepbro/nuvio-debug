package tv.nuvio.clone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

// ==========================================
// 1. DATA MODELS & CATALOG
// ==========================================
data class MediaItem(
    val id: Int,
    val title: String,
    val overview: String,
    val category: String, // "Movies", "TV Shows", "Anime"
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double,
    val year: String,
    val streams: List<StreamSource>
)

data class StreamSource(
    val title: String,
    val quality: String,
    val streamUrl: String
)

// Pre-loaded offline working catalog (plays immediately)
val SampleCatalog = listOf(
    MediaItem(
        id = 1,
        title = "Big Buck Bunny",
        overview = "A large and lovable rabbit deals with bullying forest creatures in this renowned open-source animated comedy.",
        category = "Movies",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
        backdropUrl = "https://peach.blender.org/wp-content/uploads/bbb-splash.png",
        rating = 8.5,
        year = "2008",
        streams = listOf(
            StreamSource("Big Buck Bunny (Direct HLS)", "1080p", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
            StreamSource("Standard MP4 Mirror", "720p", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4")
        )
    ),
    MediaItem(
        id = 2,
        title = "Tears of Steel",
        overview = "In a dystopian future, a group of scientists and soldiers gather in Amsterdam to stage an alternate reality experiment.",
        category = "Movies",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/e/ec/Tears_of_Steel_poster.jpg",
        backdropUrl = "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
        rating = 7.8,
        year = "2012",
        streams = listOf(
            StreamSource("Tears of Steel 4K Direct", "4K", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4")
        )
    ),
    MediaItem(
        id = 3,
        title = "Sintel",
        overview = "A lonely young woman searches the world for a pet dragon that was stolen from her by an armored beast.",
        category = "Anime",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/8/8f/Sintel_poster.jpg",
        backdropUrl = "https://durian.blender.org/wp-content/uploads/2010/09/sintel_screen_01.jpg",
        rating = 8.2,
        year = "2010",
        streams = listOf(
            StreamSource("Sintel Open Stream", "1080p", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4")
        )
    ),
    MediaItem(
        id = 4,
        title = "Cosmos Laundromat",
        overview = "On a desolate island, a suicidal sheep named Franck meets a mysterious salesman who offers him the gift of a lifetime.",
        category = "TV Shows",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/3/36/Cosmos_Laundromat_Poster.jpg",
        backdropUrl = "https://cloud.blender.org/p/cosmos-laundromat/5614eb58c379cf004c7f3b89/header.jpg",
        rating = 8.0,
        year = "2015",
        streams = listOf(
            StreamSource("Episode 1: First Cycle", "1080p", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8")
        )
    )
)

// ==========================================
// 2. THEME (NUVIO OBSIDIAN & CYAN)
// ==========================================
val ObsidianBg = Color(0xFF090A0C)
val SurfaceCard = Color(0xFF14171D)
val ElectricCyan = Color(0xFF00E5FF)
val TextPrimary = Color(0xFFF5F7FA)
val TextMuted = Color(0xFF8E95A5)

// ==========================================
// 3. MAIN ACTIVITY
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ElectricCyan,
                    background = ObsidianBg,
                    surface = SurfaceCard,
                    onBackground = TextPrimary,
                    onSurface = TextPrimary
                )
            ) {
                var selectedNavIndex by remember { mutableStateOf(0) }
                var selectedMedia by remember { mutableStateOf<MediaItem?>(null) }
                var searchQuery by remember { mutableStateOf("") }
                var selectedCategory by remember { mutableStateOf("All") }

                // Hardware back button navigation
                BackHandler(enabled = selectedMedia != null) {
                    selectedMedia = null
                }

                Scaffold(
                    bottomBar = {
                        if (selectedMedia == null) {
                            NavigationBar(containerColor = SurfaceCard) {
                                val navItems = listOf("Home" to "🏠", "Discover" to "🔍", "Watchlist" to "📑", "Settings" to "⚙️")
                                navItems.forEachIndexed { index, (label, icon) ->
                                    NavigationBarItem(
                                        selected = selectedNavIndex == index,
                                        onClick = { selectedNavIndex = index },
                                        icon = { Text(icon, fontSize = 20.sp) },
                                        label = { Text(label, fontSize = 11.sp) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedTextColor = ElectricCyan,
                                            indicatorColor = ElectricCyan.copy(alpha = 0.2f),
                                            unselectedTextColor = TextMuted
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize().background(ObsidianBg).padding(innerPadding)) {
                        if (selectedMedia != null) {
                            DetailScreen(
                                media = selectedMedia!!,
                                onBack = { selectedMedia = null },
                                onPlay = { stream ->
                                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                                        putExtra("STREAM_URL", stream.streamUrl)
                                        putExtra("TITLE", selectedMedia!!.title)
                                    }
                                    startActivity(intent)
                                }
                            )
                        } else {
                            when (selectedNavIndex) {
                                0 -> HomeScreen(
                                    catalog = SampleCatalog,
                                    selectedCategory = selectedCategory,
                                    onCategorySelected = { selectedCategory = it },
                                    onSelectMedia = { selectedMedia = it }
                                )
                                1 -> DiscoverScreen(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    catalog = SampleCatalog,
                                    onSelectMedia = { selectedMedia = it }
                                )
                                2 -> WatchlistScreen(onSelectMedia = { selectedMedia = it })
                                3 -> SettingsScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. SCREEN: HOME
// ==========================================
@Composable
fun HomeScreen(
    catalog: List<MediaItem>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    onSelectMedia: (MediaItem) -> Unit
) {
    val filteredCatalog = if (selectedCategory == "All") catalog else catalog.filter { it.category == selectedCategory }
    val hero = catalog.first()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // App Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NUVIO", style = MaterialTheme.typography.headlineMedium.copy(color = ElectricCyan, fontWeight = FontWeight.Black))
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(SurfaceCard),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 16.sp)
                }
            }
        }

        // Category Filter Tabs
        item {
            val categories = listOf("All", "Movies", "TV Shows", "Anime")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = cat == selectedCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSelected) ElectricCyan else SurfaceCard)
                            .clickable { onCategorySelected(cat) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color.Black else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Hero Billboard
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectMedia(hero) }
            ) {
                AsyncImage(
                    model = hero.backdropUrl,
                    contentDescription = hero.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Gradient Shadow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                startY = 100f
                            )
                        )
                )
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                ) {
                    Text("FEATURED SPOTLIGHT", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(hero.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("★ ${hero.rating} • ${hero.year} • ${hero.category}", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSelectMedia(hero) },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                        ) {
                            Text("▶ Watch Now", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Row 1: Trending Now
        item {
            MediaRow(title = "Trending Now", items = filteredCatalog, onSelectMedia = onSelectMedia)
        }

        // Row 2: Popular Movies
        item {
            MediaRow(title = "Top Releases", items = filteredCatalog.reversed(), onSelectMedia = onSelectMedia)
        }
    }
}

@Composable
fun MediaRow(title: String, items: List<MediaItem>, onSelectMedia: (MediaItem) -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items) { item ->
                Column(
                    modifier = Modifier.width(135.dp).clickable { onSelectMedia(item) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceCard)
                    ) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("★ ${item.rating}", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text("${item.year} • ${item.category}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}

// ==========================================
// 5. SCREEN: DETAIL & SOURCE PICKER
// ==========================================
@Composable
fun DetailScreen(
    media: MediaItem,
    onBack: () -> Unit,
    onPlay: (StreamSource) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(260.dp)) {
                AsyncImage(
                    model = media.backdropUrl,
                    contentDescription = media.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Black.copy(0.4f), ObsidianBg))
                    )
                )
                // Back Button (←)
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(media.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("★ ${media.rating}", color = ElectricCyan, fontWeight = FontWeight.Bold)
                    Text("•", color = TextMuted)
                    Text(media.year, color = TextMuted)
                    Text("•", color = TextMuted)
                    Text(media.category, color = TextMuted)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(media.overview, style = MaterialTheme.typography.bodyMedium, color = TextPrimary.copy(alpha = 0.8f), lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(24.dp))
                Text("Select Stream Provider", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        items(media.streams) { stream ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlay(stream) },
                colors = CardDefaults.cardColors(containerColor = SurfaceCard)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(stream.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(stream.quality, color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onPlay(stream) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text("▶ Play", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. SCREEN: DISCOVER / LIVE SEARCH
// ==========================================
@Composable
fun DiscoverScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    catalog: List<MediaItem>,
    onSelectMedia: (MediaItem) -> Unit
) {
    val searchResults = if (query.isEmpty()) catalog else catalog.filter { it.title.contains(query, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search movies, shows, anime...", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceCard),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Results (${searchResults.size})", style = MaterialTheme.typography.titleSmall, color = TextMuted)
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(searchResults) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceCard)
                        .clickable { onSelectMedia(item) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(60.dp, 80.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                        AsyncImage(model = item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${item.year} • ${item.category}", color = TextMuted, fontSize = 12.sp)
                        Text("★ ${item.rating}", color = ElectricCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ==========================================
// 7. SCREEN: WATCHLIST & SETTINGS PLACEHOLDERS
// ==========================================
@Composable
fun WatchlistScreen(onSelectMedia: (MediaItem) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("My Watchlist", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        MediaRow("Saved for Later", SampleCatalog.take(2), onSelectMedia)
    }
}

@Composable
fun SettingsScreen() {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text("Settings & Add-ons", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(20.dp))
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SurfaceCard)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Nuvio Core Engine v1.0.0", fontWeight = FontWeight.Bold)
                Text("Video Engine: AndroidX Media3 (ExoPlayer)", color = TextMuted, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Stream Resolver: Active", color = ElectricCyan, fontSize = 13.sp)
            }
        }
    }
}

// ==========================================
// 8. FULL-SCREEN EXOPLAYER ACTIVITY
// ==========================================
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamUrl = intent.getStringExtra("STREAM_URL") ?: return finish()
        val title = intent.getStringExtra("TITLE") ?: "Nuvio Stream"

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

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                    modifier = Modifier.fillMaxSize()
                )

                // Back arrow in player
                IconButton(
                    onClick = { finish() },
                    modifier = Modifier.padding(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                ) {
                    Text("←", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
