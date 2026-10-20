package com.deepseek.dshmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.deepseek.dshmobile.util.AvatarImage
import com.deepseek.dshmobile.ui.NewBotScreen
import com.deepseek.dshmobile.ui.NewRoomScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.deepseek.dshmobile.ui.AppViewModel
import com.deepseek.dshmobile.ui.ChatScreen
import com.deepseek.dshmobile.ui.DsTheme
import com.deepseek.dshmobile.ui.LoadingScreen
import com.deepseek.dshmobile.ui.PairScreen
import com.deepseek.dshmobile.ui.Screen
import com.deepseek.dshmobile.ui.TeamScreen
import com.deepseek.dshmobile.ui.SettingsScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        vm.applyScannedPayload(contents)
    }

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.Default) { AvatarImage.fromUri(this@MainActivity, uri) } }
                .onSuccess { vm.setAvatarImage(it) }
                .onFailure { vm.showFormError(it.message ?: "这张图片用不了，请换一张") }
        }
    }

    private val voiceLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !spoken.isNullOrBlank()) vm.addVoiceText(spoken)
    }

    // ---- batch 4: files, camera, pictures for the next message ----
    private val attachUi by lazy {
        com.deepseek.dshmobile.ui.AttachUi(
            picture = { a, max -> vm.picture(a, max) },
            openImage = { vm.openViewer(it) },
            openFile = { vm.openFile(it) },
            removePending = { vm.removePending(it) },
            toggleRun = { vm.toggleExpanded(it) },
            closeViewer = { vm.openViewer(null) },
        )
    }
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addPicked(uris)
    }
    private val imageLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) vm.addPicked(uris)
    }
    private var cameraUri: android.net.Uri? = null
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        if (ok && uri != null) vm.addPicked(listOf(uri), forceName = "照片_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg")
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else vm.showNotice("需要相机权限才能拍照，可以在手机设置里打开")
    }

    private fun openCamera() {
        runCatching {
            val dir = java.io.File(cacheDir, "camera").apply { mkdirs() }
            val f = java.io.File(dir, "shot-" + System.currentTimeMillis() + ".jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            cameraUri = uri
            cameraLauncher.launch(uri)
        }.onFailure { vm.showNotice("这台手机打不开相机") }
    }

    private fun attach(kind: String) {
        runCatching {
            when (kind) {
                "file" -> fileLauncher.launch(arrayOf("*/*"))
                "image" -> imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                "camera" -> if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
                    else cameraPermission.launch(android.Manifest.permission.CAMERA)
            }
        }.onFailure { vm.showNotice(if (kind == "camera") "这台手机打不开相机" else "没能打开选择器") }
    }

    /** Opens the phone's own voice input; the words land in the input box. */
    private fun startVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "请说话")
        if (intent.resolveActivity(packageManager) == null) {
            vm.showNotice("这台手机没有语音输入，可以用键盘上的话筒")
            return
        }
        runCatching { voiceLauncher.launch(intent) }.onFailure { vm.showNotice("没能打开语音输入") }
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
            val state by vm.state.collectAsState()
            val dark = when (state.prefs.theme) { "light" -> false; "dark" -> true; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            DsTheme(dark = dark) {
              androidx.compose.runtime.CompositionLocalProvider(
                  com.deepseek.dshmobile.ui.LocalBotShapes provides state.bots.associate { it.id to it.shape },
              ) {
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
                        onOpenRoom = { vm.openRoom(it) },
                        onNewBot = vm::openNewBot,
                        onNewRoom = vm::openNewRoom,
                        onOpenChat = vm::openChat,
                        onAnswer = vm::answerApproval,
                        onSettings = vm::openSettings,
                        onTogglePin = vm::togglePin,
                        onToggleUnread = vm::toggleUnread,
                        onHide = vm::hideChat,
                        onSearch = vm::openSearch,
                    )
                    Screen.Settings -> {
                        BackHandler { vm.closeSettings() }
                        SettingsScreen(
                            state = state,
                            onClose = vm::closeSettings,
                            onTheme = vm::setTheme,
                            onUnhideAll = vm::unhideAll,
                            onLogout = { vm.logout() },
                        )
                    }
                    Screen.NewBot -> {
                        BackHandler { if (state.folderListing != null) vm.closeFolderBrowser() else vm.closeForm() }
                        NewBotScreen(
                            state = state,
                            onChange = vm::updateBotDraft,
                            onPickPhoto = { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onBrowse = { vm.browseFolder(it) },
                            onCloseBrowse = vm::closeFolderBrowser,
                            onCreate = vm::createBot,
                            onCancel = vm::closeForm,
                        )
                    }
                    Screen.NewRoom -> {
                        BackHandler { vm.closeForm() }
                        NewRoomScreen(
                            state = state,
                            onPickAdmin = vm::pickRoomAdmin,
                            onToggle = vm::toggleRoomMember,
                            onName = vm::setRoomName,
                            onCreate = vm::createRoom,
                            onCancel = vm::closeForm,
                        )
                    }
                    Screen.Chat -> {
                        BackHandler { vm.backToTeam() }
                        androidx.compose.runtime.CompositionLocalProvider(com.deepseek.dshmobile.ui.LocalAttach provides attachUi) { ChatScreen(
                            state = state,
                            onBack = vm::backToTeam,
                            onLoadMore = { vm.loadMoreHistory() },
                            onComposeChange = vm::onComposeChange,
                            onSend = vm::sendWithReply,
                            onDismissWait = vm::dismissWait,
                            onStop = vm::stopReply,
                            onAnswer = vm::answerApproval,
                            onVoice = ::startVoice,
                            onRefresh = { vm.loadTeam() },
                            onReply = vm::setReply,
                            onAttach = ::attach,
                            onInfo = { val r = state.selectedRoom; if (r != null) vm.openGroupInfo() else state.selectedBot?.let { vm.openProfile(it.id) } },
                        ) }
                    }
                    Screen.BotProfile -> {
                        BackHandler { vm.closeProfile() }
                        com.deepseek.dshmobile.ui.BotProfileScreen(
                            state = state,
                            onBack = vm::closeProfile,
                            onShape = vm::setShape,
                            onColor = vm::setColor,
                            onReset = vm::resetLook,
                            onText = vm::saveText,
                            onMute = { state.profile?.let { vm.toggleMute("bot", it.id) } },
                        )
                    }
                    Screen.GroupInfo -> {
                        BackHandler { vm.closeGroupInfo() }
                        com.deepseek.dshmobile.ui.GroupInfoScreen(
                            state = state,
                            onBack = vm::closeGroupInfo,
                            onMember = vm::openProfile,
                            onAdd = vm::openAddMember,
                            onMute = { state.selectedRoom?.let { vm.toggleMute("room", it.id) } },
                            onRemove = vm::removeMember,
                            onRename = vm::renameRoom,
                        )
                    }
                    Screen.AddMember -> {
                        BackHandler { vm.closeAddMember() }
                        com.deepseek.dshmobile.ui.AddMemberScreen(state, onClose = vm::closeAddMember, onToggle = vm::toggleAddPick, onAdd = vm::addMembers)
                    }
                    Screen.Search -> {
                        BackHandler { vm.closeSearch() }
                        com.deepseek.dshmobile.ui.SearchScreen(state, onClose = vm::closeSearch, onQuery = vm::onSearch, onOpen = vm::openHit)
                    }
                }
              }
            }
        }
    }
}
