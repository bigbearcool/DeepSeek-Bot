package com.deepseek.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.deepseek.dshmobile.ui.AppViewModel
import com.deepseek.dshmobile.ui.ChatScreen
import com.deepseek.dshmobile.ui.PairScreen
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
        super.onCreate(savedInstanceState)
        intent?.data?.let { uri ->
            if (uri.scheme.equals("dshmb", ignoreCase = true)) {
                vm.applyScannedPayload(uri.toString())
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface {
                    val state by vm.state.collectAsState()
                    var route by remember { mutableStateOf(if (state.paired) "team" else "pair") }
                    // Keep route in sync when pairing completes
                    if (state.paired && route == "pair" && state.bots.isNotEmpty()) {
                        route = "team"
                    }
                    when {
                        state.selectedBot != null -> ChatScreen(
                            state = state,
                            onBack = { vm.backToTeam(); route = "team" },
                            onLoadMore = vm::loadMoreHistory,
                            onComposeChange = vm::onComposeChange,
                            onSend = vm::sendMessage,
                        )
                        route == "team" && state.paired -> TeamScreen(
                            state = state,
                            onRefresh = vm::loadTeam,
                            onOpen = { bot -> vm.openBot(bot); route = "chat" },
                            onLogout = {
                                vm.logout()
                                route = "pair"
                            },
                            onBackToPair = { route = "pair" },
                        )
                        else -> PairScreen(
                            state = state,
                            onBaseUrlChange = vm::onBaseUrlChange,
                            onSaveBaseUrl = vm::saveBaseUrl,
                            onPairInputChange = vm::onPairInputChange,
                            onPair = {
                                vm.pair()
                                route = "team"
                            },
                            onScan = {
                                val options = ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Scan dshmb: pairing QR")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(true)
                                scanLauncher.launch(options)
                            },
                            onContinueIfPaired = {
                                vm.loadTeam()
                                route = "team"
                            },
                        )
                    }
                }
            }
        }
    }
}
