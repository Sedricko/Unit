package dev.unit.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Peça 0.2: só a tela com o nome e a versão. O motor e a busca chegam nas próximas fases. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Unit", style = MaterialTheme.typography.headlineLarge)
                        Text("versão $version", style = MaterialTheme.typography.bodyMedium)
                        Text("Fase 0: o terreno. O motor de memória chega na Fase 1.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
