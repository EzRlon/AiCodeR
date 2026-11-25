package com.aicode

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.presentation.DatabaseBootScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 启动引导：先确认数据库能打开，坏了直接进恢复页，避免主界面 ViewModel 查询数据库时把进程打死。
 */
@AndroidEntryPoint
class BootstrapActivity : ComponentActivity() {

    @Inject
    lateinit var database: AgentDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DatabaseBootScreen(darkTheme = isSystemInDarkTheme()) }
        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { database.openHelper.writableDatabase.version }.exceptionOrNull()
            }
            val target = if (failure == null) {
                Intent(this@BootstrapActivity, MainActivity::class.java)
            } else {
                Intent(this@BootstrapActivity, DatabaseRecoveryActivity::class.java)
                    .putExtra(DatabaseRecoveryActivity.EXTRA_ERROR, failure.message ?: failure.javaClass.name)
            }
            startActivity(target)
            finish()
        }
    }
}
