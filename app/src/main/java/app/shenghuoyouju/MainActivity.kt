package app.shenghuoyouju

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import app.shenghuoyouju.ui.LifeEvidenceApp
import app.shenghuoyouju.ui.LifeEvidenceTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            LifeEvidenceTheme { LifeEvidenceApp(state, model.actions) }
        }
    }

    override fun onStart() {
        super.onStart()
        model.onForeground()
    }
}
