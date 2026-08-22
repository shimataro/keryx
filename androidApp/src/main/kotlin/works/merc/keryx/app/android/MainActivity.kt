package works.merc.keryx.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import works.merc.keryx.app.App

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
    }
}
