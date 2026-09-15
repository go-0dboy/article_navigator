package io.github.go0dboy.articlenavigator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                ArticleNavigatorApp()
            }
        }
    }
}

@Composable
private fun ArticleNavigatorApp() {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Article Navigator",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Local-first personal knowledge collection and search.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "Foundation build: architecture, domain model, collector contracts and durable storage schema.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
