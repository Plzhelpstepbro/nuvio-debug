package tv.nuvio.clone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tv.nuvio.clone.models.MediaItem
import tv.nuvio.clone.network.TmdbApi
import tv.nuvio.clone.ui.DetailScreen
import tv.nuvio.clone.ui.HomeScreen
import tv.nuvio.clone.ui.PlayerActivity
import tv.nuvio.clone.ui.theme.NuvioTheme

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
                        // Fallback items so the UI loads immediately without crashing
                        movies = listOf(
                            MediaItem(1, "Big Buck Bunny", "An open-source film.", null, null, 8.5)
                        )
                        shows = listOf(
                            MediaItem(2, "Tears of Steel", "Sci-Fi project.", null, null, 7.9)
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
                        onStreamSelected = { stream ->
                            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                                putExtra("STREAM_URL", stream.streamUrl)
                                putExtra("MEDIA_ID", selectedMedia!!.id)
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
