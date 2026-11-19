package com.aicode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.presentation.DatabaseRecoveryScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class DatabaseRecoveryActivity : ComponentActivity() {

    @Inject
    lateinit var database: AgentDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val error = intent.getStringExtra(EXTRA_ERROR) ?: "数据库版本不兼容"
        setContent {
            DatabaseRecoveryScreen(
                error = error,
                database = database,
                darkTheme = isSystemInDarkTheme()
            )
        }
    }

    companion object {
        const val EXTRA_ERROR = "database_error"
    }
}
