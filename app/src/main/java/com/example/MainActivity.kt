package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.ai.CloudSynthesisService
import com.example.data.model.UiState
import com.example.data.model.UserRole
import com.example.data.repository.FolioRepository
import com.example.domain.pipeline.SidecarDriftService
import com.example.domain.prosody.ProsodyAudioEngine
import com.example.firebase.configureAppCheck
import com.example.ui.auth.SignInScreen
import com.example.ui.auth.signOutUser
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PipelineScreen
import com.example.ui.screens.ProsodyScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.theme.FolioGraphTheme
import com.example.ui.viewmodel.FolioViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class FolioTab(
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
) {
    LIBRARY(R.string.nav_library, Icons.Filled.LocalLibrary, Icons.Outlined.LocalLibrary, "nav_library"),
    READER(R.string.nav_reader, Icons.Filled.AutoStories, Icons.Outlined.AutoStories, "nav_reader"),
    PROSODY(R.string.nav_prosody, Icons.Filled.GraphicEq, Icons.Outlined.GraphicEq, "nav_prosody"),
    PIPELINE(R.string.nav_pipeline, Icons.Filled.Hub, Icons.Outlined.Hub, "nav_pipeline")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val authFlow = Firebase.auth.authStateFlow()

        setContent {
            val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)
            val systemDark = isSystemInDarkTheme()
            var darkThemeOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
            val effectiveDark = darkThemeOverride ?: systemDark

            FolioGraphTheme(darkTheme = effectiveDark) {
                val user = currentUser
                if (user == null) {
                    SignInScreen(onSignedIn = {})
                } else {
                    key(user.uid) {
                        AuthenticatedFolioShell(
                            user = user,
                            isDarkTheme = effectiveDark,
                            onToggleTheme = { darkThemeOverride = !effectiveDark }
                        )
                    }
                }
            }
        }
    }
}

private fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
private fun AuthenticatedFolioShell(
    user: FirebaseUser,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: FolioViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val dbId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                FolioViewModel(
                    repository = FolioRepository(db),
                    cloudAi = CloudSynthesisService(),
                    audioEngine = ProsodyAudioEngine(app),
                    sidecarDriftService = SidecarDriftService(app.filesDir),
                    currentUserId = user.uid,
                    initialDisplayName = user.displayName ?: "Archivist",
                    initialEmail = user.email ?: ""
                )
            }
        }
    )

    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val documentsState by viewModel.documentsState.collectAsStateWithLifecycle()
    val auditLogsState by viewModel.auditLogsState.collectAsStateWithLifecycle()
    val selectedDocId by viewModel.selectedDocId.collectAsStateWithLifecycle()
    val selectedSections by viewModel.selectedSections.collectAsStateWithLifecycle()
    val selectedTriples by viewModel.selectedTriples.collectAsStateWithLifecycle()
    val selectedAnnotations by viewModel.selectedAnnotations.collectAsStateWithLifecycle()
    val ingestionStage by viewModel.ingestionStage.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val errorBanner by viewModel.errorBanner.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val formatFilter by viewModel.formatFilter.collectAsStateWithLifecycle()
    val readerSettings by viewModel.readerSettings.collectAsStateWithLifecycle()
    val activeEnvelope by viewModel.activeEnvelope.collectAsStateWithLifecycle()
    val dofThreshold by viewModel.dofThreshold.collectAsStateWithLifecycle()
    val activeDriftReport by viewModel.activeDriftReport.collectAsStateWithLifecycle()
    val isCheckingDrift by viewModel.isCheckingDrift.collectAsStateWithLifecycle()
    val prosodyState by viewModel.prosodyState.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(FolioTab.LIBRARY) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner, errorBanner) {
        val message = errorBanner ?: statusBanner
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissBanners()
        }
    }

    BackHandler(enabled = currentTab != FolioTab.LIBRARY) {
        currentTab = FolioTab.LIBRARY
    }

    val docsList = (documentsState as? UiState.Success)?.data.orEmpty()
    val selectedDocument = remember(docsList, selectedDocId) {
        docsList.firstOrNull { it.id == selectedDocId } ?: docsList.firstOrNull()
    }
    LaunchedEffect(selectedDocument?.id, selectedDocId) {
        if (selectedDocId == null && selectedDocument != null) {
            viewModel.selectDocument(selectedDocument.id)
        }
    }

    val userRole = UserRole.fromWire(userProfile?.role ?: UserRole.CURATOR.wireValue)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        FolioTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        FolioTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.titleRes)
                                    )
                                },
                                label = { Text(stringResource(tab.titleRes)) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentTab) {
                        FolioTab.LIBRARY -> LibraryScreen(
                            documentsState = documentsState,
                            userProfile = userProfile,
                            searchQuery = searchQuery,
                            formatFilter = formatFilter,
                            ingestionStage = ingestionStage,
                            isDarkTheme = isDarkTheme,
                            onSearchChange = viewModel::setSearchQuery,
                            onFilterChange = viewModel::setFormatFilter,
                            onToggleTheme = onToggleTheme,
                            onSignOut = {
                                viewModel.stopAudio()
                                signOutUser(
                                    credentialManager = credentialManager,
                                    scope = scope
                                )
                            },
                            onOpenDocument = { docId ->
                                viewModel.selectDocument(docId)
                                currentTab = FolioTab.READER
                            },
                            onDeleteDocument = viewModel::deleteDocument,
                            onIngestUri = { uri, name ->
                                viewModel.ingestDocumentFromUri(context, uri, name)
                            },
                            onIngestCanonicalSpec = viewModel::ingestCanonicalReferenceDita
                        )

                        FolioTab.READER -> ReaderScreen(
                            document = selectedDocument,
                            sections = selectedSections,
                            triples = selectedTriples,
                            annotations = selectedAnnotations,
                            readerSettings = readerSettings,
                            userRole = userRole,
                            onUpdateSettings = viewModel::updateReaderSettings,
                            onReseedSection = viewModel::triggerCloudSectionReseed,
                            onAddAnnotation = viewModel::addAnnotation,
                            onDeleteAnnotation = viewModel::deleteAnnotation,
                            onPlayFromSection = { idx ->
                                viewModel.playAllSections(idx)
                                currentTab = FolioTab.PROSODY
                            }
                        )

                        FolioTab.PROSODY -> ProsodyScreen(
                            document = selectedDocument,
                            sections = selectedSections,
                            prosodyState = prosodyState,
                            onSelectRegister = { reg ->
                                viewModel.replayCentralRingWithRegister(reg, simulateDriftTest = false)
                            },
                            onPlayFromSection = viewModel::playAllSections,
                            onStopAudio = viewModel::stopAudio
                        )

                        FolioTab.PIPELINE -> PipelineScreen(
                            selectedDocument = selectedDocument,
                            sections = selectedSections,
                            activeEnvelope = activeEnvelope,
                            driftReport = activeDriftReport,
                            isCheckingDrift = isCheckingDrift,
                            auditLogsState = auditLogsState,
                            userProfile = userProfile,
                            dofThreshold = dofThreshold,
                            onDofThresholdChange = viewModel::setDofThreshold,
                            onReplayCheckpoint = {
                                viewModel.replayCentralRingWithRegister(
                                    register = prosodyState.register,
                                    simulateDriftTest = false
                                )
                            },
                            onSimulateDriftTest = {
                                viewModel.replayCentralRingWithRegister(
                                    register = prosodyState.register,
                                    simulateDriftTest = true
                                )
                            },
                            onVerifySidecarHash = {
                                viewModel.verifyLocalSidecarAgainstFirestore()
                            },
                            onSimulateLocalSidecarDrift = {
                                viewModel.simulateLocalSidecarDrift()
                            },
                            onRestoreSidecarFromCloud = {
                                viewModel.restoreLocalSidecarFromFirestore()
                            },
                            onSaveProfileSettings = viewModel::updateProfileSettings
                        )
                    }
                }
            }
        }
    }
}
