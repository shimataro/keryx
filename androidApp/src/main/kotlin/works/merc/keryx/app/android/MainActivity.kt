package works.merc.keryx.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.App
import works.merc.keryx.app.runAndroidStartupTasks

/**
 * Android's single [ComponentActivity], equivalent to desktop's `Window { App() }` in `main.kt`.
 * All app-process-wide setup (Koin, the image loader, FTS backfill) already ran in
 * [KeryxApplication.onCreate] before this activity is ever created.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App()
        }

        // See runAndroidStartupTasks's own KDoc for why this runs from the Activity rather than
        // Application.onCreate (which also runs when WorkManager wakes the process for
        // FeedRefreshWorker) — and why it's safe to call again on every onCreate (rotation), since
        // the function guards itself to once per process.
        val koin = KoinPlatform.getKoin()
        koin.get<CoroutineScope>().launch { runAndroidStartupTasks(koin) }
    }
}
