package com.cardforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cardforge.data.LibraryRepository
import com.cardforge.ui.AppRoot
import com.cardforge.ui.theme.CardForgeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = LibraryRepository(applicationContext)
        repository.load()

        setContent {
            CardForgeTheme {
                AppRoot(repository)
            }
        }
    }
}
