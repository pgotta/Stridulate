package com.pgotta.stridulate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pgotta.stridulate.audio.RecordedSegmentPlayer
import com.pgotta.stridulate.audio.ReferenceSoundPlayer
import com.pgotta.stridulate.community.CommunityShare
import com.pgotta.stridulate.qa.TestFeedbackShare
import com.pgotta.stridulate.ui.CommunityShareRequest
import com.pgotta.stridulate.ui.StridulateViewModel
import com.pgotta.stridulate.ui.UiState
import com.pgotta.stridulate.ui.screens.AnalyzingScreen
import com.pgotta.stridulate.ui.screens.BrowseScreen
import com.pgotta.stridulate.ui.screens.CommunityArchiveScreen
import com.pgotta.stridulate.ui.screens.CommunityRecordScreen
import com.pgotta.stridulate.ui.screens.ErrorScreen
import com.pgotta.stridulate.ui.screens.GuideScreen
import com.pgotta.stridulate.ui.screens.HomeScreen
import com.pgotta.stridulate.ui.screens.ListenScreen
import com.pgotta.stridulate.ui.screens.NearbyMapScreen
import com.pgotta.stridulate.ui.screens.ResultScreen
import com.pgotta.stridulate.ui.screens.SessionScreen
import com.pgotta.stridulate.ui.screens.SettingsScreen
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Ink
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Panel2
import com.pgotta.stridulate.ui.theme.ParchDim
import com.pgotta.stridulate.ui.theme.StridulateTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val pendingSharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSharedUri.value = extractSharedUri(intent)
        setContent {
            StridulateTheme {
                StridulateApp(
                    sharedUri = pendingSharedUri.value,
                    onSharedUriConsumed = { pendingSharedUri.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingSharedUri.value = extractSharedUri(intent)
    }

    override fun onStop() {
        // Reference audio is screen-scoped. Never keep it playing after the app
        // leaves the foreground, and cancel any recording that is still loading.
        ReferenceSoundPlayer.stop()
        RecordedSegmentPlayer.stop()
        super.onStop()
    }

    private fun extractSharedUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }
}

private enum class Tab { Home, Guide, Listen, Session, Community }

@Composable
fun StridulateApp(
    sharedUri: Uri? = null,
    onSharedUriConsumed: () -> Unit = {}
) {
    val vm: StridulateViewModel = viewModel()
    val context = LocalContext.current

    val ui by vm.ui.collectAsState()
    val liveDetections by vm.liveDetections.collectAsState()
    val liveCandidates by vm.liveCandidates.collectAsState()
    val liveSignalAssessment by vm.liveSignalAssessment.collectAsState()
    val recordingElapsedSeconds by vm.recordingElapsedSeconds.collectAsState()
    val logSessions by vm.logSessions.collectAsState()
    val tierSettings by vm.tierSettings.collectAsState()
    val specCol by vm.spectrogramColumn.collectAsState()
    val loudness by vm.loudness.collectAsState()
    val observationContext by vm.environment.collectAsState()
    val communityRecords by vm.communityRecords.collectAsState()
    val communityBusy by vm.communityBusy.collectAsState()
    val communityNotice by vm.communityNotice.collectAsState()
    val communityShareRequest by vm.communityShareRequest.collectAsState()
    val testFeedbackCount by vm.testFeedbackCount.collectAsState()
    val testTargetKey by vm.testTargetKey.collectAsState()
    val testFeedbackExportRequest by vm.testFeedbackExportRequest.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var guideId by rememberSaveable { mutableStateOf<String?>(null) }
    var communityRecordId by rememberSaveable { mutableStateOf<String?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCurrentContextImportUri by remember { mutableStateOf<Uri?>(null) }

    val liveColumns = remember { mutableStateListOf<FloatArray>() }
    LaunchedEffect(specCol) {
        if (specCol.isNotEmpty()) {
            liveColumns.add(specCol)
            if (liveColumns.size > 260) liveColumns.removeAt(0)
        }
    }

    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            liveColumns.clear()
            guideId = null
            communityRecordId = null
            showMap = false
            showSettings = false
            tab = Tab.Home
            pendingImportUri = sharedUri
            onSharedUriConsumed()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> pendingImportUri = uri }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) { liveColumns.clear(); vm.startListening() } }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val importUri = pendingCurrentContextImportUri
        pendingCurrentContextImportUri = null
        if (granted) {
            if (importUri != null) vm.analyzeFile(importUri, useCurrentContext = true)
            else vm.useDeviceContext()
        } else {
            vm.markLocationPermissionDenied()
            // Do not block file analysis when context permission is declined.
            if (importUri != null) vm.analyzeFile(importUri, useCurrentContext = false)
        }
    }

    fun requestDeviceContext() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) vm.useDeviceContext()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun requestListen() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            liveColumns.clear()
            vm.startListening()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }


    LaunchedEffect(communityShareRequest) {
        val request = communityShareRequest ?: return@LaunchedEffect
        try {
            val record = communityRecords.firstOrNull { it.id == request.recordId }
                ?: error("Saved recording not found.")
            when (request) {
                is CommunityShareRequest.Identification ->
                    CommunityShare.shareAudioForIdentification(context, vm.communityRepository, record)
                is CommunityShareRequest.TrainingBundle ->
                    CommunityShare.shareTrainingBundle(context, File(request.filePath), record)
            }
        } catch (e: Exception) {
            vm.showCommunityNotice(e.message ?: "Could not open the share sheet.")
        } finally {
            vm.clearCommunityShareRequest()
        }
    }

    LaunchedEffect(testFeedbackExportRequest) {
        val path = testFeedbackExportRequest ?: return@LaunchedEffect
        try {
            TestFeedbackShare.share(context, File(path))
        } catch (e: Exception) {
            vm.showCommunityNotice(e.message ?: "Could not export the QA test log.")
        } finally {
            vm.clearTestFeedbackExportRequest()
        }
    }

    communityNotice?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearCommunityNotice,
            title = { Text("Community recordings") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::clearCommunityNotice) { Text("OK") }
            },
            containerColor = Panel2
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("When was this recorded?") },
            text = {
                Text(
                    "Current temperature and location are only appropriate when this file was recorded here and now. Otherwise, use audio-only analysis so present weather does not distort the context. If context is off, Android will ask for approximate location."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        val locationGranted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (observationContext.enabled || locationGranted) {
                            vm.analyzeFile(uri, useCurrentContext = true)
                        } else {
                            pendingCurrentContextImportUri = uri
                            locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                ) { Text("Use current conditions") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingImportUri = null
                        vm.analyzeFile(uri, useCurrentContext = false)
                    }
                ) { Text("Audio only") }
            },
            containerColor = Panel2
        )
    }

    val atRoot = ui is UiState.Idle && guideId == null && communityRecordId == null &&
        !showMap && !showSettings && tab == Tab.Home
    BackHandler(enabled = !atRoot) {
        // Covers Android system back for guide and result navigation.
        ReferenceSoundPlayer.stop()
        RecordedSegmentPlayer.stop()
        when {
            ui is UiState.Listening -> vm.cancelListening()
            ui is UiState.Analyzing -> vm.cancelAnalysis()
            ui is UiState.Result -> vm.dismissResult()
            ui is UiState.Error -> vm.dismissResult()
            guideId != null -> guideId = null
            communityRecordId != null -> communityRecordId = null
            showMap -> showMap = false
            showSettings -> showSettings = false
            tab != Tab.Home -> tab = Tab.Home
        }
    }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            val hide = ui is UiState.Listening || ui is UiState.Analyzing ||
                ui is UiState.Result || guideId != null || communityRecordId != null || showMap || showSettings
            if (!hide) {
                BottomBar(tab) { picked ->
                    when (picked) {
                        Tab.Listen -> requestListen()
                        else -> {
                            tab = picked
                            showMap = false
                            showSettings = false
                            guideId = null
                            communityRecordId = null
                            if (picked == Tab.Guide) vm.prefetchFieldGuidePhotos()
                        }
                    }
                }
            }
        }
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .background(Ink)
        ) {
            when (val s = ui) {
                is UiState.Listening -> ListenScreen(
                    spectrogramColumns = liveColumns.toList(),
                    loudness = loudness,
                    candidates = liveCandidates,
                    signalAssessment = liveSignalAssessment,
                    detections = liveDetections,
                    elapsedSeconds = recordingElapsedSeconds,
                    testSpecies = vm.tier1Species,
                    testTargetKey = testTargetKey,
                    testFeedbackCount = testFeedbackCount,
                    onSetTestTargetSpecies = vm::setTestTargetSpecies,
                    onSetTestTargetNoise = vm::setTestTargetNoise,
                    onClearTestTarget = vm::clearTestTarget,
                    onExportTestFeedback = vm::requestTestFeedbackExport,
                    onClearTestFeedback = vm::clearTestFeedback,
                    onMarkCurrentNoise = vm::recordCurrentLiveWindowAsNoise,
                    onTestFeedback = vm::recordLiveTestFeedback,
                    onStop = {
                        tab = Tab.Session
                        vm.stopAndSaveLog()
                    },
                    onCancel = { vm.cancelListening() }
                )
                is UiState.Analyzing -> AnalyzingScreen(s.label)
                is UiState.Result -> ResultScreen(
                    result = s.result,
                    onBack = { vm.dismissResult() },
                    onOpenGuide = { id -> vm.dismissResult(); guideId = id },
                    onPlay = { sp -> ReferenceSoundPlayer.play(context, sp) },
                    canRefreshContext = observationContext.enabled,
                    onRefreshContext = vm::refreshResultContext,
                    onSaveForCommunity = vm::saveCurrentForCommunity,
                    onShareForIdentification = vm::requestIdentificationShare,
                    onOpenCommunity = {
                        val savedId = s.result.communityRecordId
                        vm.dismissResult()
                        tab = Tab.Community
                        communityRecordId = savedId
                    },
                    testSpecies = vm.tier1Species,
                    testTargetKey = testTargetKey,
                    testFeedbackCount = testFeedbackCount,
                    onSetTestTargetSpecies = vm::setTestTargetSpecies,
                    onSetTestTargetNoise = vm::setTestTargetNoise,
                    onClearTestTarget = vm::clearTestTarget,
                    onExportTestFeedback = vm::requestTestFeedbackExport,
                    onClearTestFeedback = vm::clearTestFeedback,
                    onMarkCurrentNoise = vm::recordCurrentResultAsNoise,
                    onTestFeedback = vm::recordResultTestFeedback
                )
                is UiState.Error -> ErrorScreen(s.message) { vm.dismissResult() }
                UiState.Idle -> {
                    when {
                        communityRecordId != null -> {
                            val record = communityRecords.firstOrNull { it.id == communityRecordId }
                            if (record != null) {
                                CommunityRecordScreen(
                                    record = record,
                                    busyMessage = communityBusy,
                                    onBack = {
                                        RecordedSegmentPlayer.stop()
                                        communityRecordId = null
                                    },
                                    onShareToINaturalist = { vm.requestIdentificationShare(record.id) },
                                    onLinkINaturalist = { vm.linkCommunityRecord(record.id, it) },
                                    onRefreshINaturalist = { vm.refreshCommunityRecord(record.id) },
                                    onOpenINaturalist = {
                                        record.iNaturalistUrl?.let { url ->
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                    onOpenGitHubTracking = {
                                        runCatching {
                                            CommunityShare.githubTrackingIssueUrl(record)
                                        }.onSuccess { url ->
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }.onFailure { error ->
                                            vm.showCommunityNotice(error.message ?: "Could not open the GitHub issue.")
                                        }
                                    },
                                    onPlayRecording = {
                                        RecordedSegmentPlayer.play(
                                            context,
                                            vm.communityRepository.audioFile(record).absolutePath,
                                            0.0,
                                            record.durationSeconds
                                        )
                                    },
                                    onUpdateNote = { vm.updateCommunityNote(record.id, it) },
                                    onApproveTraining = { label, credit, rightsConfirmed ->
                                        vm.approveCommunityRecord(
                                            record.id, label, credit, rightsConfirmed
                                        )
                                    },
                                    onExportBundle = { vm.requestTrainingBundle(record.id) },
                                    onDelete = {
                                        vm.deleteCommunityRecord(record.id)
                                        communityRecordId = null
                                    }
                                )
                            } else {
                                communityRecordId = null
                            }
                        }
                        guideId != null -> {
                            val sp = vm.repo.byId(guideId!!)
                            if (sp != null) {
                                GuideScreen(
                                    sp = sp,
                                    reliability = vm.reliabilityFor(sp),
                                    observationContext = observationContext,
                                    contextAssessment = vm.contextAssessmentFor(sp),
                                    coverageNote = vm.contextCoverageFor(sp),
                                    onBack = {
                                        ReferenceSoundPlayer.stop()
                                        guideId = null
                                    },
                                    onPlay = { ReferenceSoundPlayer.play(context, it) },
                                    onStopPlayback = ReferenceSoundPlayer::stop
                                )
                            } else {
                                guideId = null
                            }
                        }
                        showSettings -> SettingsScreen(
                            settings = tierSettings,
                            onBack = { showSettings = false },
                            onTierChanged = vm::setTierEnabled
                        )
                        showMap -> NearbyMapScreen(
                            species = vm.tier1Species,
                            reliabilityFor = vm::reliabilityFor,
                            profileFor = vm::contextProfileFor,
                            observationContext = observationContext,
                            onBack = { showMap = false },
                            onOpenGuide = { guideId = it }
                        )
                        else -> when (tab) {
                            Tab.Home -> HomeScreen(
                                speciesCount = vm.tier1Species.size,
                                sessionCount = logSessions.flatMap { it.detections }.map { it.speciesId }.toSet().size,
                                unknownCount = communityRecords.size,
                                modelStatus = vm.modelStatus,
                                usingTrainedModel = vm.usingTrainedModel,
                                observationContext = observationContext,
                                onUseDeviceLocation = { requestDeviceContext() },
                                onUseManualLocation = vm::useManualContext,
                                onRefreshContext = vm::refreshContext,
                                onDisableContext = vm::disableContext,
                                onListen = { requestListen() },
                                onImport = { filePicker.launch(arrayOf("audio/*", "video/*")) },
                                onOpenGuide = {
                                    vm.prefetchFieldGuidePhotos()
                                    tab = Tab.Guide
                                },
                                onOpenSession = { tab = Tab.Session },
                                onOpenCommunity = { tab = Tab.Community },
                                onOpenMap = { showMap = true },
                                onOpenSettings = { showSettings = true }
                            )
                            Tab.Guide -> BrowseScreen(
                                species = vm.tier1Species,
                                reliabilityFor = vm::reliabilityFor,
                                onBack = { tab = Tab.Home },
                                onOpenGuide = { guideId = it }
                            )
                            Tab.Session -> SessionScreen(
                                sessions = logSessions,
                                resolveSpecies = vm.repo::byId,
                                onBack = { tab = Tab.Home },
                                onClear = { vm.clearSession() },
                                onDeleteSession = vm::deleteLogSession,
                                onMoveToUnknowns = vm::moveLogSessionToUnknowns,
                                onOpenGuide = { guideId = it },
                                onPlaySegment = { file, start, end ->
                                    RecordedSegmentPlayer.play(context, file, start, end)
                                }
                            )
                            Tab.Community -> CommunityArchiveScreen(
                                records = communityRecords,
                                busyMessage = communityBusy,
                                onBack = { tab = Tab.Home },
                                onRefreshAll = vm::refreshAllCommunityRecords,
                                onOpenRecord = { communityRecordId = it }
                            )
                            Tab.Listen -> Unit
                        }
                    }
                }
            }
        }
    }
}

private data class NavItem(val tab: Tab, val icon: ImageVector, val label: String)

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Panel, contentColor = ParchDim) {
        listOf(
            NavItem(Tab.Home, Icons.Filled.Home, "Home"),
            NavItem(Tab.Guide, Icons.Filled.Book, "Guide"),
            NavItem(Tab.Listen, Icons.Filled.Mic, "Listen"),
            NavItem(Tab.Session, Icons.Filled.History, "Log"),
            NavItem(Tab.Community, Icons.Filled.HelpOutline, "Unknowns")
        ).forEach { item ->
            NavigationBarItem(
                selected = current == item.tab && item.tab != Tab.Listen,
                onClick = { onSelect(item.tab) },
                icon = { Icon(item.icon, item.label, modifier = Modifier.size(22.dp)) },
                label = { Text(item.label, fontSize = 9.5.sp, fontFamily = JetBrainsMono) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Biolume,
                    selectedTextColor = Biolume,
                    unselectedIconColor = Mute,
                    unselectedTextColor = Mute,
                    indicatorColor = Panel2
                )
            )
        }
    }
}
