package com.deepseek.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.deepseek.dshmobile.ui.AppViewModel
import com.deepseek.dshmobile.ui.ChatScreen
import com.deepseek.dshmobile.ui.DsTheme
import com.deepseek.dshmobile.ui.LoadingScreen
import com.deepseek.dshmobile.ui.PairScreen
import com.deepseek.dshmobile.ui.Screen
import com.deepseek.dshmobile.ui.TeamScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        vm.applyScannedPayload(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            if (uri.scheme.equals("dshmb", ignoreCase = true)) vm.applyScannedPayload(uri.toString())
        }
        setContent {
            DsTheme {
                val state by vm.state.collectAsState()
                when (state.screen) {
                    Screen.Loading -> LoadingScreen()
                    Screen.Pair -> PairScreen(
                        state = state,
                        onBaseUrlChange = vm::onBaseUrlChange,
                        onPairInputChange = vm::onPairInputChange,
                        onPair = { vm.pair() },
                        onScan = {
                            scanLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("对准电脑上「手机配对」的二维码")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(true),
                            )
                        },
                    )
                    Screen.Team -> TeamScreen(
                        state = state,
                        onRefresh = { vm.loadTeam() },
                        onOpen = { vm.openBot(it) },
                        onLogout = { vm.logout() },
                    )
                    Screen.Chat -> {
                        BackHandler { vm.backToTeam() }
                        ChatScreen(
                            state = state,
                            onBack = vm::backToTeam,
                            onLoadMore = { vm.loadMoreHistory() },
                            onComposeChange = vm::onComposeChange,
                            onSend = vm::sendMessage,
                            onDismissWait = vm::dismissWait,
                        )
                    }
                }
            }
        }
    }
}
