package com.lechugones.cocina

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lechugones.cocina.ui.CocinaApp
import com.lechugones.cocina.ui.IncomingImports
import com.lechugones.cocina.ui.theme.CocinaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var incomingImports: IncomingImports

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity gets the same intent again: only handle it the first time.
        if (savedInstanceState == null) incomingImports.submitFrom(intent)
        setContent {
            CocinaTheme {
                CocinaApp(pendingImport = incomingImports.pending)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingImports.submitFrom(intent)
    }
}
