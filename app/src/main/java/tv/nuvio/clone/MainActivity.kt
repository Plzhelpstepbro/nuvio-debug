package tv.nuvio.clone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ==========================================
// 1. DATA MODELS & STREMIO ADDON SPEC
// ==========================================
data class MediaItem(
    val id: String, // IMDB or TMDB ID (e.g. tt0111161 or 1)
    val title: String,
    val overview: String,
    val category: String, // "movie" or "series"
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double,
    val year: String,
    val streams: List<StreamSource> = emptyList()
)

data class StreamSource(
    val title: String,
    val quality: String,
    val streamUrl: String,
    val addonName: String = "Built-in"
)

// Stremio-Standard Addon Manifest
data class AddonManifest(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("resources") val resources: List<String> = emptyList(), // e.g. ["catalog", "stream"]
    @SerializedName("types") val types: List<String> = emptyList(),         // e.g. ["movie", "series"]
    var transportUrl: String = ""                                            // Base URL (without /manifest.json)
)

data class StremioStreamResponse(
    @SerializedName("streams") val streams: List<StremioStreamItem>? = emptyList()
)

data class StremioStreamItem(
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("url") val url: String? = null
)

// ==========================================
// 2. ADDON SYNC & PERSISTENCE REPOSITORY
// ==========================================
object AddonManager {
    private const val PREFS_NAME = "nuvio_addons_prefs"
    private const val KEY_ADDONS = "installed_manifests"

    // Default pre-installed Public Domain / Creative Commons streams addon
    private val DEFAULT_ADDONS = listOf(
        AddonManifest(
            id = "tv.nuvio.publicstreams",
            name = "Open Blender Streams",
            version = "1.2.0",
            description = "Provides official high-speed 4K & 1080p open streams for benchmark open-source media.",
            resources = listOf("stream", "catalog"),
            types = listOf("movie", "series"),
            transportUrl = "https://v3-cinemeta.strem.io"
        )
    )

    fun getInstalledAddons(context: Context): List<AddonManifest> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_ADDONS, null) ?: return DEFAULT_ADDONS
        return try {
            val type = object : TypeToken<List<AddonManifest>>() {}.type
            Gson().fromJson(json, type) ?: DEFAULT_ADDONS
        } catch (e: Exception) {
            DEFAULT_ADDONS
        }
    }

    fun saveAddons(context: Context, addons: List<AddonManifest>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ADDONS, Gson().toJson(addons)).apply()
    }

    // Fetches and synchronizes a JSON manifest from any HTTP or stremio:// URL
    suspend fun syncAddonFromUrl(rawUrl: String): AddonManifest = withContext(Dispatchers.IO) {
        val cleanedUrl = rawUrl.trim().replace("stremio://", "https://")
        val manifestUrl = if (cleanedUrl.endsWith("/manifest.json")) cleanedUrl else "$cleanedUrl/manifest.json"

        val conn = URL(manifestUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Nuvio/1.0 (Android; StremioCompatible)")

        val json = conn.inputStream.bufferedReader().use { it.readText() }
        val manifest = Gson().fromJson(json, AddonManifest::class.java)
        manifest.transportUrl = manifestUrl.substringBeforeLast("/manifest.json")
        manifest
    }

    // Queries all installed addons that support "stream" for a given movie/show ID
    suspend fun fetchStreamsFromAddons(context: Context, type: String, id: String): List<StreamSource> = withContext(Dispatchers.IO) {
        val addons = getInstalledAddons(context).filter { it.resources.contains("stream") }
        val resolvedStreams = mutableListOf<StreamSource>()

        for (addon in addons) {
            try {
                val streamEndpoint = "${addon.transportUrl}/stream/$type/$id.json"
                val conn = URL(streamEndpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "Nuvio/1.0 (Android; StremioCompatible)")

                if (conn.responseCode == 200) {
                    val responseJson = conn.inputStream.bufferedReader().use { it.readText() }
                    val response = Gson().fromJson(responseJson, StremioStreamResponse::class.java)
                    response.streams?.forEach { s ->
                        val url = s.url ?: return@forEach
                        val title = s.title ?: s.name ?: "Stream"
                        val quality = when {
                            title.contains("4K", true) || title.contains("2160", true) -> "4K"
                            title.contains("1080", true) -> "1080p"
                            title.contains("720", true) -> "720p"
                            else -> "HD"
                        }
                        resolvedStreams.add(StreamSource(title, quality, url, addon.name))
                    }
                }
            } catch (_: Exception) {
                // Skip offline or unreachable addons gracefully
            }
        }
        resolvedStreams
    }
}

// Sample Catalog with IMDb IDs to demonstrate real addon stream resolution
val SampleCatalog = listOf(
    MediaItem(
        id = "tt1087260",
        title = "Big Buck Bunny",
        overview = "A large and lovable rabbit deals with bullying forest creatures in this renowned open-source animated comedy.",
        category = "movie",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
        backdropUrl = "https://peach.blender.org/wp-content/uploads/bbb-splash.png",
        rating = 8.5,
        year = "2008",
        streams = listOf(
            StreamSource("Default Direct HLS", "1080p", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "Local")
        )
    ),
    MediaItem(
        id = "tt2285752",
        title = "Tears of Steel",
        overview = "In a dystopian future, scientists stage an alternate reality experiment in Amsterdam.",
        category = "movie",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/e/ec/Tears_of_Steel_poster.jpg",
        backdropUrl = "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg",
        rating = 7.8,
        year = "2012",
        streams = listOf(
            StreamSource("Tears of Steel 4K MP4", "4K", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4", "Local")
        )
    ),
    MediaItem(
        id = "tt1727587",
        title = "Sintel",
        overview = "A lonely young woman searches the world for a pet dragon that was stolen from her.",
        category = "movie",
        posterUrl = "https://upload.wikimedia.org/wikipedia/commons/8/8f/Sintel_poster.jpg",
        backdropUrl = "https://durian.blender.org/wp-content/uploads/2010/09/sintel_screen_01.jpg",
        rating = 8.2,
        year = "2010",
        streams = listOf(
            StreamSource("Sintel Open Direct", "1080p", "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4", "Local")
        )
    )
)

// Theme Colors
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

                BackHandler(enabled = selectedMedia != null) {
                    selectedMedia = null
                }

                Scaffold(
                    bottomBar = {
                        if (selectedMedia == null) {
                            NavigationBar(containerColor = SurfaceCard) {
                                val navItems = listOf("Home" to "🏠", "Discover" to "🔍", "Watchlist" to "📑", "Add-ons" to "🧩")
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
                                0 -> HomeScreen(catalog = SampleCatalog, onSelectMedia = { selectedMedia = it })
                                1 -> DiscoverScreen(query = searchQuery, onQueryChange = { searchQuery = it }, catalog = SampleCatalog, onSelectMedia = { selectedMedia = it })
                                2 -> WatchlistScreen(onSelectMedia = { selectedMedia = it })
                                3 -> AddonsSyncScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. SCREEN: ADD-ON SYNC & MANIFEST MANAGER
// ==========================================
@Composable
fun AddonsSyncScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var installedAddons by remember { mutableStateOf(AddonManager.getInstalledAddons(context)) }
    var inputManifestUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun refreshAddonList() {
        installedAddons = AddonManager.getInstalledAddons(context)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Add-on Manifest Sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Sync third-party Stremio-format JSON manifests for direct streams and catalogs.", color = TextMuted, fontSize = 13.sp)
        }

        // Install Manifest URL Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Install New Add-on", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputManifestUrl,
                        onValueChange = { inputManifestUrl = it },
                        placeholder = { Text("https://example.com/manifest.json", color = TextMuted, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (inputManifestUrl.isNotBlank()) {
                                    isLoading = true
                                    coroutineScope.launch {
                                        try {
                                            val manifest = AddonManager.syncAddonFromUrl(inputManifestUrl)
                                            val updated = installedAddons.filter { it.id != manifest.id } + manifest
                                            AddonManager.saveAddons(context, updated)
                                            refreshAddonList()
                                            inputManifestUrl = ""
                                            Toast.makeText(context, "Synced: ${manifest.name} v${manifest.version}", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed to parse manifest: ${e.message}", Toast.LENGTH_LONG).show()
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan),
                            enabled = !isLoading
                        ) {
                            Text(if (isLoading) "Syncing..." else "Sync Manifest", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        // Preset button for quick testing
                        OutlinedButton(
                            onClick = { inputManifestUrl = "https://v3-cinemeta.strem.io/manifest.json" }
                        ) {
                            Text("Paste Test Addon", color = ElectricCyan)
                        }
                    }
                }
            }
        }

        // Installed Addons List
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Installed Add-ons (${installedAddons.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                // Sync All Button
                TextButton(
                    onClick = {
                        isLoading = true
                        coroutineScope.launch {
                            val refreshed = mutableListOf<AddonManifest>()
                            for (addon in installedAddons) {
                                try {
                                    refreshed.add(AddonManager.syncAddonFromUrl("${addon.transportUrl}/manifest.json"))
                                } catch (_: Exception) {
                                    refreshed.add(addon)
                                }
                            }
                            AddonManager.saveAddons(context, refreshed)
                            refreshAddonList()
                            isLoading = false
                            Toast.makeText(context, "All add-ons resynced!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("🔄 Sync All", color = ElectricCyan, fontWeight = FontWeight.Bold)
                }
            }
        }

        items(installedAddons) { addon ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(addon.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("ID: ${addon.id} • v${addon.version}", color = TextMuted, fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = {
                                val updated = installedAddons.filter { it.id != addon.id }
                                AddonManager.saveAddons(context, updated)
                                refreshAddonList()
                            }
                        ) {
                            Text("🗑️", fontSize = 16.sp)
                        }
                    }

                    if (!addon.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(addon.description, color = TextPrimary.copy(alpha = 0.8f), fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        addon.resources.forEach { resource ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ElectricCyan.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(resource.uppercase(), color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. SCREEN: DETAIL & DYNAMIC STREAM RESOLVER
// ==========================================
@Composable
fun DetailScreen(
    media: MediaItem,
    onBack: () -> Unit,
    onPlay: (StreamSource) -> Unit
) {
    val context = LocalContext.current
    var addonStreams by remember { mutableStateOf<List<StreamSource>>(emptyList()) }
    var isResolvingStreams by remember { mutableStateOf(true) }

    // Query all installed Stremio add-ons for streams when this media item opens
    LaunchedEffect(media.id) {
        isResolvingStreams = true
        val resolved = AddonManager.fetchStreamsFromAddons(context, media.category, media.id)
        addonStreams = resolved
        isResolvingStreams = false
    }

    val allStreams = media.streams + addonStreams

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
                    Text(media.category.uppercase(), color = TextMuted)
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(media.overview, style = MaterialTheme.typography.bodyMedium, color = TextPrimary.copy(alpha = 0.8f), lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Available Streams", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (isResolvingStreams) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = ElectricCyan, strokeWidth = 2.dp)
                            Text("Querying add-ons...", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        if (allStreams.isEmpty() && !isResolvingStreams) {
            item {
                Text("No active stream sources found. Sync a stream add-on in the Add-ons tab.", color = TextMuted, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
            }
        } else {
            items(allStreams) { stream ->
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stream.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stream.quality, color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("via ${stream.addonName}", color = TextMuted, fontSize = 11.sp)
                            }
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
}

// ==========================================
// 6. SCREEN: HOME
// ==========================================
@Composable
fun HomeScreen(catalog: List<MediaItem>, onSelectMedia: (MediaItem) -> Unit) {
    val hero = catalog.first()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
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

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectMedia(hero) }
            ) {
                AsyncImage(model = hero.backdropUrl, contentDescription = hero.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), startY = 100f)
                    )
                )
                Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text("FEATURED SPOTLIGHT", color = ElectricCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(hero.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("★ ${hero.rating} • ${hero.year}", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { onSelectMedia(hero) },
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan)
                    ) {
                        Text("▶ Watch Now", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            MediaRow(title = "Trending Now", items = catalog, onSelectMedia = onSelectMedia)
        }
        item {
            MediaRow(title = "Top Releases", items = catalog.reversed(), onSelectMedia = onSelectMedia)
        }
    }
}

@Composable
fun MediaRow(title: String, items: List<MediaItem>, onSelectMedia: (MediaItem) -> Unit) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items) { item ->
                Column(modifier = Modifier.width(135.dp).clickable { onSelectMedia(item) }) {
                    Box(modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(10.dp)).background(SurfaceCard)) {
                        AsyncImage(model = item.posterUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("★ ${item.rating}", color = ElectricCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, fontWeight = FontWeight.Medium)
                    Text("${item.year} • ${item.category.uppercase()}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                }
            }
        }
    }
}

// ==========================================
// 7. SCREEN: DISCOVER & WATCHLIST
// ==========================================
@Composable
fun DiscoverScreen(query: String, onQueryChange: (String) -> Unit, catalog: List<MediaItem>, onSelectMedia: (MediaItem) -> Unit) {
    val searchResults = if (query.isEmpty()) catalog else catalog.filter { it.title.contains(query, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search titles...", color = TextMuted) },
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
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceCard).clickable { onSelectMedia(item) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(55.dp, 75.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                        AsyncImage(model = item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(item.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${item.year} • ★ ${item.rating}", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun WatchlistScreen(onSelectMedia: (MediaItem) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("My Watchlist", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        MediaRow("Saved for Later", SampleCatalog.take(2), onSelectMedia)
    }
}

// ==========================================
// 8. FULLSCREEN VIDEO PLAYER ACTIVITY
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
