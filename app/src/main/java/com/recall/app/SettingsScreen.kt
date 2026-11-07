package com.recall.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val SettingsBackground = Color(0xFF100F17)
private val SettingsCard = Color(0xFF201E2B)
private val SettingsPurple = Color(0xFFB69CFF)
private val SettingsMuted = Color(0xFFAAA5B8)
private val SettingsText = Color(0xFFF5F2FA)

private val DriveScopes = listOf(
    Scope("https://www.googleapis.com/auth/drive.file"),
    Scope("openid"),
    Scope("profile"),
    Scope("email")
)

private val PhotosScopes = listOf(
    Scope("https://www.googleapis.com/auth/photoslibrary.appendonly"),
    Scope("openid"),
    Scope("profile"),
    Scope("email")
)

private suspend fun fetchDriveAccountEmail(
    accessToken: String
): String = withContext(Dispatchers.IO) {

    val connection = URL(
        "https://www.googleapis.com/oauth2/v3/userinfo"
    ).openConnection() as HttpURLConnection

    try {
        connection.requestMethod = "GET"

        connection.setRequestProperty(
            "Authorization",
            "Bearer $accessToken"
        )

        connection.connectTimeout = 15000
        connection.readTimeout = 15000

        val responseCode = connection.responseCode

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception(
                "Unable to retrieve Google account details. HTTP $responseCode"
            )
        }

        val response = connection.inputStream
            .bufferedReader()
            .use { it.readText() }

        val userInfo = JSONObject(response)

        val email = userInfo.optString("email", "")
        val verified = userInfo.optBoolean("email_verified", false)

        if (email.isBlank() || !verified) {
            throw Exception("Google account email could not be verified.")
        }

        email

    } finally {
        connection.disconnect()
    }
}

@Composable
fun SettingsScreen(
    tripleTapEnabled: Boolean,
    sensitivity: Float,
    onTripleTapChanged: (Boolean) -> Unit,
    onSensitivityChanged: (Float) -> Unit
) {

    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val auth = remember {
        FirebaseAuth.getInstance()
    }

    val googleAuth = remember {
        RecallGoogleAuth(context)
    }

    val driveStorage = remember {
        RecallDriveStorage(context)
    }

    var currentUser by remember {
        mutableStateOf(auth.currentUser)
    }

    var signingIn by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf("") }
    var showSignOutConfirmation by remember { mutableStateOf(false) }

    // HELP PAGE

    var showHelp by remember {
        mutableStateOf(false)
    }

    BackHandler(enabled = showHelp) {
        showHelp = false
    }

    // GOOGLE DRIVE STATE

    var driveAccessToken by remember {
        mutableStateOf<String?>(null)
    }

    var driveAccountEmail by remember {
        mutableStateOf<String?>(null)
    }

    var connectingDrive by remember { mutableStateOf(false) }
    var disconnectingDrive by remember { mutableStateOf(false) }
    var driveMessage by remember { mutableStateOf("") }
    var showDriveDisconnectConfirmation by remember { mutableStateOf(false) }

    // GOOGLE PHOTOS STATE

    var photosAccessToken by remember {
        mutableStateOf(RecallPhotosSession.accessToken)
    }

    var photosAccountEmail by remember {
        mutableStateOf(
            RecallPhotosSession.accountEmail
                ?: RecallPhotosAuthorization.getConnectedAccountEmail(context)
        )
    }

    var connectingPhotos by remember { mutableStateOf(false) }
    var disconnectingPhotos by remember { mutableStateOf(false) }

    var photosMessage by remember {
        mutableStateOf(
            if (photosAccountEmail != null) {
                "Checking Google Photos connection..."
            } else {
                "Connect Google Photos to upload your captures."
            }
        )
    }

    var showPhotosDisconnectConfirmation by remember { mutableStateOf(false) }

    // CLOUD BACKUP PREFERENCES

    var driveBackupEnabled by remember {
        mutableStateOf(
            RecallBackupPreferences.isDriveBackupEnabled(context)
        )
    }

    var photosBackupEnabled by remember {
        mutableStateOf(
            RecallBackupPreferences.isPhotosBackupEnabled(context)
        )
    }

    var autoBackupEnabled by remember {
        mutableStateOf(
            RecallBackupPreferences.isAutoBackupEnabled(context)
        )
    }

    var backupFrequency by remember {
        mutableStateOf(
            RecallBackupPreferences.getFrequency(context)
        )
    }

    // FIREBASE ACCOUNT LISTENER

    DisposableEffect(auth) {

        val listener = FirebaseAuth.AuthStateListener {
            currentUser = it.currentUser
        }

        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    // GOOGLE DRIVE TOKEN PROCESSING

    fun processDriveToken(token: String?) {

        if (token.isNullOrBlank()) {
            connectingDrive = false
            RecallDriveSession.disconnect()

            driveMessage =
                "No access token was returned. Please try again."

            return
        }

        scope.launch {

            try {

                val email = fetchDriveAccountEmail(token)

                driveStorage.saveConnectedAccountEmail(email)

                driveAccessToken = token
                driveAccountEmail = email

                driveMessage =
                    "Connected. Preparing your Recall folder..."

                val folderId =
                    driveStorage.getOrCreateRecallFolder(
                        accessToken = token,
                        accountEmail = email
                    )

                if (folderId.isBlank()) {
                    throw Exception("Recall folder could not be created.")
                }

                RecallDriveSession.connect(
                    token = token,
                    email = email,
                    recallFolderId = folderId
                )

                driveMessage =
                    "Google Drive connected! Recall folder is ready."

                RecallBackupScheduler.schedule(context)

            } catch (e: Exception) {

                driveMessage =
                    e.localizedMessage
                        ?: "Unable to prepare the Recall folder."

            } finally {
                connectingDrive = false
            }
        }
    }

    // GOOGLE PHOTOS TOKEN PROCESSING

    fun processPhotosToken(token: String?) {

        if (token.isNullOrBlank()) {
            connectingPhotos = false
            photosMessage =
                "Google Photos authorization was not completed."
            return
        }

        connectingPhotos = true
        photosMessage = "Checking your Google account..."

        scope.launch {

            try {

                val email = fetchDriveAccountEmail(token)

                RecallPhotosAuthorization.saveConnectedAccountEmail(
                    context,
                    email
                )

                RecallPhotosSession.connect(
                    token = token,
                    email = email
                )

                photosAccessToken = token
                photosAccountEmail = email

                photosMessage =
                    "Google Photos connected successfully!"

                RecallBackupScheduler.schedule(context)

            } catch (e: Exception) {

                RecallPhotosSession.disconnect()

                photosAccessToken = null
                photosAccountEmail = null

                photosMessage = e.localizedMessage
                    ?: "Unable to retrieve your Google account details."

            } finally {
                connectingPhotos = false
            }
        }
    }

    // GOOGLE DRIVE AUTHORIZATION RESULT

    val driveAuthorizationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                try {

                    val returnedIntent = result.data

                    if (returnedIntent == null) {

                        connectingDrive = false
                        driveMessage =
                            "Google Drive authorization was not completed."

                    } else {

                        val authorizationResult =
                            Identity.getAuthorizationClient(context)
                                .getAuthorizationResultFromIntent(returnedIntent)

                        processDriveToken(
                            authorizationResult.accessToken
                        )
                    }

                } catch (e: Exception) {

                    connectingDrive = false
                    driveMessage =
                        e.localizedMessage
                            ?: "Google Drive authorization failed."
                }

            } else {

                connectingDrive = false
                driveMessage = "Google Drive connection was cancelled."
            }
        }

    // GOOGLE PHOTOS AUTHORIZATION RESULT

    val photosAuthorizationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                try {

                    val returnedIntent = result.data

                    if (returnedIntent == null) {

                        connectingPhotos = false
                        photosMessage =
                            "Google Photos authorization was not completed."

                    } else {

                        val authorizationResult =
                            Identity.getAuthorizationClient(context)
                                .getAuthorizationResultFromIntent(returnedIntent)

                        processPhotosToken(
                            authorizationResult.accessToken
                        )
                    }

                } catch (e: Exception) {

                    connectingPhotos = false
                    photosMessage =
                        e.localizedMessage
                            ?: "Google Photos authorization failed."
                }

            } else {

                connectingPhotos = false
                photosMessage = "Google Photos connection was cancelled."
            }
        }

    // CONNECT GOOGLE DRIVE

    fun connectGoogleDrive() {

        if (activity == null) {
            driveMessage = "Unable to open Google Drive authorization."
            return
        }

        connectingDrive = true
        driveMessage = ""

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(DriveScopes)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { authorizationResult ->

                if (authorizationResult.hasResolution()) {

                    val pendingIntent = authorizationResult.pendingIntent

                    if (pendingIntent != null) {

                        try {

                            val intentRequest =
                                IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()

                            driveAuthorizationLauncher.launch(intentRequest)

                        } catch (e: Exception) {

                            connectingDrive = false
                            driveMessage =
                                e.localizedMessage
                                    ?: "Unable to open Google permission screen."
                        }

                    } else {

                        connectingDrive = false
                        driveMessage =
                            "Google authorization request was incomplete."
                    }

                } else {

                    processDriveToken(authorizationResult.accessToken)
                }
            }
            .addOnFailureListener { error ->

                connectingDrive = false
                driveMessage =
                    error.localizedMessage
                        ?: "Google Drive connection failed."
            }
    }

    // CONNECT GOOGLE PHOTOS

    fun connectGooglePhotos() {

        if (activity == null) {
            photosMessage = "Unable to open Google Photos authorization."
            return
        }

        connectingPhotos = true
        photosMessage = "Opening Google Photos authorization..."

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(PhotosScopes)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { authorizationResult ->

                if (authorizationResult.hasResolution()) {

                    val pendingIntent = authorizationResult.pendingIntent

                    if (pendingIntent != null) {

                        try {

                            val intentRequest =
                                IntentSenderRequest.Builder(
                                    pendingIntent.intentSender
                                ).build()

                            photosAuthorizationLauncher.launch(intentRequest)

                        } catch (e: Exception) {

                            connectingPhotos = false
                            photosMessage =
                                e.localizedMessage
                                    ?: "Unable to open Google Photos permission screen."
                        }

                    } else {

                        connectingPhotos = false
                        photosMessage =
                            "Google authorization request was incomplete."
                    }

                } else {

                    processPhotosToken(authorizationResult.accessToken)
                }
            }
            .addOnFailureListener { error ->

                connectingPhotos = false
                photosMessage =
                    error.localizedMessage
                        ?: "Google Photos connection failed."
            }
    }

    // DISCONNECT GOOGLE DRIVE

    fun disconnectGoogleDrive() {

        val token = driveAccessToken

        if (token.isNullOrBlank()) {

            driveAccessToken = null
            driveAccountEmail = null

            RecallDriveSession.disconnect()
            driveStorage.clearConnectedAccountEmail()

            RecallBackupScheduler.schedule(context)

            driveMessage = "Google Drive is already disconnected."
            return
        }

        disconnectingDrive = true
        driveMessage = ""

        val request = ClearTokenRequest.builder()
            .setToken(token)
            .build()

        Identity.getAuthorizationClient(context)
            .clearToken(request)
            .addOnSuccessListener {

                RecallDriveSession.disconnect()
                driveStorage.clearConnectedAccountEmail()

                driveAccessToken = null
                driveAccountEmail = null
                disconnectingDrive = false

                RecallBackupScheduler.schedule(context)

                driveMessage =
                    "Google Drive disconnected successfully."
            }
            .addOnFailureListener { error ->

                disconnectingDrive = false
                driveMessage =
                    error.localizedMessage
                        ?: "Unable to disconnect Google Drive."
            }
    }

    // DISCONNECT GOOGLE PHOTOS

    fun disconnectGooglePhotos() {

        val token = photosAccessToken

        if (token.isNullOrBlank()) {

            RecallPhotosSession.disconnect()

            RecallPhotosAuthorization.clearConnectedAccountEmail(context)

            photosAccessToken = null
            photosAccountEmail = null

            RecallBackupScheduler.schedule(context)

            photosMessage = "Google Photos disconnected."
            return
        }

        disconnectingPhotos = true
        photosMessage = "Disconnecting Google Photos..."

        val request = ClearTokenRequest.builder()
            .setToken(token)
            .build()

        Identity.getAuthorizationClient(context)
            .clearToken(request)
            .addOnSuccessListener {

                RecallPhotosSession.disconnect()

                RecallPhotosAuthorization.clearConnectedAccountEmail(context)

                photosAccessToken = null
                photosAccountEmail = null
                disconnectingPhotos = false

                RecallBackupScheduler.schedule(context)

                photosMessage =
                    "Google Photos disconnected successfully."
            }
            .addOnFailureListener { error ->

                disconnectingPhotos = false
                photosMessage =
                    error.localizedMessage
                        ?: "Unable to disconnect Google Photos."
            }
    }

    // RESTORE EXISTING AUTHORIZATION

    LaunchedEffect(Unit) {

        // Restore Google Drive authorization.

        if (activity != null && driveAccessToken == null) {

            driveMessage = "Checking Google Drive connection..."

            val request = AuthorizationRequest.builder()
                .setRequestedScopes(DriveScopes)
                .build()

            Identity.getAuthorizationClient(activity)
                .authorize(request)
                .addOnSuccessListener { result ->

                    if (!result.hasResolution()) {
                        processDriveToken(result.accessToken)
                    } else {
                        driveMessage =
                            "Connect Google Drive to restore access."
                    }
                }
                .addOnFailureListener { error ->

                    driveMessage =
                        error.localizedMessage
                            ?: "Unable to restore Google Drive connection."
                }
        }

        // Restore Google Photos authorization.

        val savedPhotosEmail =
            RecallPhotosAuthorization.getConnectedAccountEmail(context)

        if (
            activity != null &&
            savedPhotosEmail != null &&
            photosAccessToken == null
        ) {

            photosMessage = "Restoring Google Photos connection..."

            val request = AuthorizationRequest.builder()
                .setRequestedScopes(PhotosScopes)
                .build()

            Identity.getAuthorizationClient(activity)
                .authorize(request)
                .addOnSuccessListener { result ->

                    if (!result.hasResolution()) {

                        processPhotosToken(result.accessToken)

                    } else {

                        photosMessage =
                            "Google Photos needs you to reconnect. Tap Connect Google Photos."
                    }
                }
                .addOnFailureListener { error ->

                    photosMessage =
                        error.localizedMessage
                            ?: "Unable to restore Google Photos connection. Please reconnect."
                }
        }
    }

    // SETTINGS UI

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = SettingsBackground,
            surface = SettingsCard,
            primary = SettingsPurple,
            onBackground = SettingsText,
            onSurface = SettingsText
        )
    ) {

        if (showHelp) {

            RecallHelpScreen(
                onBack = {
                    showHelp = false
                }
            )

        } else {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SettingsBackground)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .verticalScroll(rememberScrollState()),

                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {

                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = SettingsText
                )

                // HELP & INSTRUCTIONS

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showHelp = true
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = SettingsCard
                    ),
                    shape = MaterialTheme.shapes.large
                ) {

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),

                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {

                            Text(
                                "Help & Instructions",
                                color = SettingsText,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(
                                "Learn how Recall works and how to back up memories.",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Text(
                            "›",
                            color = SettingsPurple,
                            fontSize = 26.sp
                        )
                    }
                }

                // TRIPLE-TAP DETECTION

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            "Triple-Tap Detection",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Enable or disable the triple-tap gesture for quick capture.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            Text(
                                if (tripleTapEnabled) "ON" else "OFF",
                                color = if (tripleTapEnabled) SettingsPurple else SettingsMuted,
                                fontWeight = FontWeight.SemiBold
                            )

                            Switch(
                                checked = tripleTapEnabled,
                                onCheckedChange = onTripleTapChanged
                            )
                        }
                    }
                }

                // TRIPLE-TAP SENSITIVITY

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        Text(
                            "Triple-Tap Sensitivity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Adjust how easily Recall detects the triple-tap gesture.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            "${sensitivity.toInt()}%",
                            color = SettingsPurple,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Slider(
                            value = sensitivity.coerceIn(0f, 100f),
                            onValueChange = onSensitivityChanged,
                            valueRange = 0f..100f,
                            enabled = tripleTapEnabled,
                            colors = SliderDefaults.colors(
                                thumbColor = SettingsPurple,
                                activeTrackColor = SettingsPurple,
                                inactiveTrackColor = Color(0xFF494354)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Firm", color = SettingsMuted)
                            Text("Very Sensitive", color = SettingsMuted)
                        }
                    }
                }

                // RECALL ACCOUNT

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            "Recall Account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Sign in to sync your saved memory details across devices.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsMuted
                        )

                        if (currentUser != null) {

                            Text(
                                "SIGNED IN",
                                color = SettingsPurple,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = currentUser?.email
                                    ?: currentUser?.displayName
                                    ?: "Google account",
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsText,
                                style = MaterialTheme.typography.bodyLarge,
                                softWrap = true
                            )

                            OutlinedButton(
                                onClick = { showSignOutConfirmation = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Sign Out")
                            }

                        } else {

                            Button(
                                onClick = {

                                    if (activity == null) {

                                        accountMessage =
                                            "Unable to open Google sign-in."

                                    } else {

                                        scope.launch {

                                            signingIn = true
                                            accountMessage = ""

                                            try {

                                                googleAuth.signIn(activity)

                                                accountMessage =
                                                    "Google sign-in successful."

                                            } catch (e: Exception) {

                                                accountMessage =
                                                    e.localizedMessage
                                                        ?: "Sign-in failed. Please try again."

                                            } finally {
                                                signingIn = false
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !signingIn
                            ) {

                                Text(
                                    if (signingIn) {
                                        "Opening Google accounts..."
                                    } else {
                                        "Choose Google Account"
                                    }
                                )
                            }

                            Text(
                                "You can choose an existing account or add another Google account.",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        if (accountMessage.isNotBlank()) {
                            Text(
                                text = accountMessage,
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsPurple,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true
                            )
                        }
                    }
                }

                // GOOGLE DRIVE

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            "Google Drive",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Connect your Google Drive account to let Recall store your memories in your own Drive.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsMuted
                        )

                        if (driveAccessToken != null) {

                            Text(
                                "DRIVE CONNECTED",
                                color = SettingsPurple,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Connected Google account:",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = driveAccountEmail
                                    ?: "Retrieving account email...",
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                softWrap = true
                            )

                            OutlinedButton(
                                onClick = { showDriveDisconnectConfirmation = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !disconnectingDrive
                            ) {
                                Text(
                                    if (disconnectingDrive) {
                                        "Disconnecting..."
                                    } else {
                                        "Disconnect Google Drive"
                                    }
                                )
                            }

                        } else {

                            Text(
                                "NOT CONNECTED",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Button(
                                onClick = { connectGoogleDrive() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !connectingDrive && !disconnectingDrive
                            ) {
                                Text(
                                    if (connectingDrive) {
                                        "Connecting to Google Drive..."
                                    } else {
                                        "Connect Google Drive"
                                    }
                                )
                            }
                        }

                        Text(
                            "Recall requests access to files it creates or that you explicitly open with the app. Your other Drive files remain outside this permission.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (driveMessage.isNotBlank()) {
                            Text(
                                text = driveMessage,
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsPurple,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true
                            )
                        }
                    }
                }

                // GOOGLE PHOTOS

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            "Google Photos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Connect Google Photos to let Recall upload your newly captured images directly to your Photos library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SettingsMuted
                        )

                        if (photosAccountEmail != null) {

                            Text(
                                if (photosAccessToken != null) {
                                    "PHOTOS CONNECTED"
                                } else {
                                    "ACCOUNT SAVED"
                                },
                                color = SettingsPurple,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Connected Google account:",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Text(
                                text = photosAccountEmail
                                    ?: "Retrieving account email...",
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsText,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                softWrap = true
                            )

                            OutlinedButton(
                                onClick = { showPhotosDisconnectConfirmation = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !disconnectingPhotos
                            ) {
                                Text(
                                    if (disconnectingPhotos) {
                                        "Disconnecting..."
                                    } else {
                                        "Disconnect Google Photos"
                                    }
                                )
                            }

                        } else {

                            Text(
                                "NOT CONNECTED",
                                color = SettingsMuted,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Button(
                                onClick = { connectGooglePhotos() },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !connectingPhotos && !disconnectingPhotos
                            ) {
                                Text(
                                    if (connectingPhotos) {
                                        "Connecting to Google Photos..."
                                    } else {
                                        "Connect Google Photos"
                                    }
                                )
                            }
                        }

                        Text(
                            "Recall requests permission to add new images to Google Photos. It cannot browse your existing Photos library.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (photosMessage.isNotBlank()) {
                            Text(
                                text = photosMessage,
                                modifier = Modifier.fillMaxWidth(),
                                color = SettingsPurple,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true
                            )
                        }
                    }
                }

                // CLOUD BACKUP

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SettingsCard),
                    shape = MaterialTheme.shapes.large
                ) {

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {

                        Text(
                            "Cloud Backup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SettingsText
                        )

                        Text(
                            "Choose one or both destinations for your Recall memories.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // GOOGLE DRIVE BACKUP SWITCH

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {

                                Text(
                                    "Google Drive",
                                    color = SettingsText,
                                    fontWeight = FontWeight.Medium
                                )

                                Text(
                                    if (driveBackupEnabled) {
                                        "Drive backup enabled"
                                    } else {
                                        "Drive backup disabled"
                                    },
                                    color = SettingsMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Switch(
                                checked = driveBackupEnabled,
                                onCheckedChange = { enabled ->

                                    driveBackupEnabled = enabled

                                    RecallBackupPreferences.setDriveBackupEnabled(
                                        context,
                                        enabled
                                    )

                                    RecallBackupScheduler.schedule(context)
                                }
                            )
                        }

                        HorizontalDivider(color = Color(0xFF494354))

                        // GOOGLE PHOTOS BACKUP SWITCH

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {

                                Text(
                                    "Google Photos",
                                    color = SettingsText,
                                    fontWeight = FontWeight.Medium
                                )

                                Text(
                                    if (photosBackupEnabled) {
                                        "Photos backup enabled"
                                    } else {
                                        "Photos backup disabled"
                                    },
                                    color = SettingsMuted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Switch(
                                checked = photosBackupEnabled,
                                onCheckedChange = { enabled ->

                                    photosBackupEnabled = enabled

                                    RecallBackupPreferences.setPhotosBackupEnabled(
                                        context,
                                        enabled
                                    )

                                    RecallBackupScheduler.schedule(context)
                                }
                            )
                        }

                        HorizontalDivider(color = Color(0xFF494354))

                        // AUTOMATIC BACKUP

                        Text(
                            "Automatic backup",
                            color = SettingsText,
                            fontWeight = FontWeight.Medium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {

                            Text(
                                if (autoBackupEnabled) "Enabled" else "Paused",
                                color = if (autoBackupEnabled) {
                                    SettingsPurple
                                } else {
                                    SettingsMuted
                                }
                            )

                            Switch(
                                checked = autoBackupEnabled,
                                onCheckedChange = { enabled ->

                                    autoBackupEnabled = enabled

                                    RecallBackupPreferences.setAutoBackupEnabled(
                                        context,
                                        enabled
                                    )

                                    RecallBackupScheduler.schedule(context)
                                }
                            )
                        }

                        // BACKUP FREQUENCY

                        Text(
                            "Backup frequency",
                            color = SettingsText,
                            fontWeight = FontWeight.Medium
                        )

                        listOf(
                            RecallBackupPreferences.DAILY,
                            RecallBackupPreferences.WEEKLY,
                            RecallBackupPreferences.MONTHLY
                        ).forEach { frequency ->

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {

                                Text(frequency, color = SettingsText)

                                RadioButton(
                                    selected = backupFrequency == frequency,
                                    onClick = {

                                        backupFrequency = frequency

                                        RecallBackupPreferences.setFrequency(
                                            context,
                                            frequency
                                        )

                                        RecallBackupScheduler.schedule(context)
                                    }
                                )
                            }
                        }

                        Text(
                            "When automatic backup is enabled, Recall uploads new memories to every enabled and connected destination at the selected frequency. You can enable both Drive and Photos. Disabling automatic backup keeps new memories on this device.",
                            color = SettingsMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    // RECALL SIGN OUT CONFIRMATION

    if (showSignOutConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showSignOutConfirmation = false
            },

            title = {
                Text("Sign out of Recall?")
            },

            text = {
                Text(
                    "You will need to sign in again to access cloud sync. " +
                            "Your memories stored locally on this device will remain."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {

                        showSignOutConfirmation = false

                        scope.launch {

                            try {

                                googleAuth.signOut()
                                accountMessage = "Signed out successfully."

                            } catch (e: Exception) {

                                accountMessage =
                                    e.localizedMessage ?: "Sign-out failed."
                            }
                        }
                    }
                ) {
                    Text("Sign Out", color = SettingsPurple)
                }
            },

            dismissButton = {
                TextButton(
                    onClick = { showSignOutConfirmation = false }
                ) {
                    Text("Cancel")
                }
            },

            containerColor = SettingsCard,
            titleContentColor = SettingsText,
            textContentColor = SettingsMuted
        )
    }

    // GOOGLE DRIVE DISCONNECT CONFIRMATION

    if (showDriveDisconnectConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showDriveDisconnectConfirmation = false
            },

            title = {
                Text("Disconnect Google Drive?")
            },

            text = {
                Text(
                    "Recall will clear its current Google Drive access token. " +
                            "Files already stored in your Drive will not be deleted."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showDriveDisconnectConfirmation = false
                        disconnectGoogleDrive()
                    }
                ) {
                    Text("Disconnect", color = SettingsPurple)
                }
            },

            dismissButton = {
                TextButton(
                    onClick = { showDriveDisconnectConfirmation = false }
                ) {
                    Text("Cancel")
                }
            },

            containerColor = SettingsCard,
            titleContentColor = SettingsText,
            textContentColor = SettingsMuted
        )
    }

    // GOOGLE PHOTOS DISCONNECT CONFIRMATION

    if (showPhotosDisconnectConfirmation) {

        AlertDialog(
            onDismissRequest = {
                showPhotosDisconnectConfirmation = false
            },

            title = {
                Text("Disconnect Google Photos?")
            },

            text = {
                Text(
                    "Recall will clear its current Google Photos access. " +
                            "Images already uploaded to Google Photos will not be deleted."
                )
            },

            confirmButton = {

                TextButton(
                    onClick = {
                        showPhotosDisconnectConfirmation = false
                        disconnectGooglePhotos()
                    }
                ) {
                    Text("Disconnect", color = SettingsPurple)
                }
            },

            dismissButton = {
                TextButton(
                    onClick = { showPhotosDisconnectConfirmation = false }
                ) {
                    Text("Cancel")
                }
            },

            containerColor = SettingsCard,
            titleContentColor = SettingsText,
            textContentColor = SettingsMuted
        )
    }
}