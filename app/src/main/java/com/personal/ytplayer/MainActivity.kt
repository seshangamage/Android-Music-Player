package com.personal.ytplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.personal.ytplayer.ui.PlayerScreen
import com.personal.ytplayer.ui.PlayerViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlayerTheme {
                PlayerScreen(viewModel)
            }
        }
    }
}

@Composable
private fun PlayerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4F8),
            onPrimary = Color(0xFF062033),
            secondary = Color(0xFFB6C4D9),
            background = Color(0xFF101114),
            surface = Color(0xFF1A1C20),
            surfaceVariant = Color(0xFF26282E),
            onBackground = Color(0xFFE8EAED),
            onSurface = Color(0xFFE8EAED),
            onSurfaceVariant = Color(0xFFBDC1C6),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
        ),
        content = content,
    )
}
