package com.thenile.vault.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.thenile.vault.state.VaultStateManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.root.StorageMountManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.ui.res.painterResource
import com.thenile.vault.R
import com.thenile.vault.backup.BackupManager
import com.thenile.vault.state.Profile
import com.thenile.vault.state.SettingsManager
import java.util.UUID

// The only four hosts SecretCodeReceiver's manifest intent-filter declares — Android's dialer
// won't deliver android.provider.Telephony.SECRET_CODE for any host that isn't statically
// declared there, so these can't be freely retyped; users may only reassign which action each
// fires (see DialCodeDropdown below).
val FIXED_DIAL_CODES = listOf("1234", "9876", "1111", "3333")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialCodeDropdown(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = "*#$selected#",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = { Icon(icon, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FIXED_DIAL_CODES.forEach { code ->
                DropdownMenuItem(
                    text = { Text("*#$code#") },
                    onClick = { onSelect(code); expanded = false }
                )
            }
        }
    }
}

fun authenticate(activity: FragmentActivity, settings: SettingsManager, requestCustomPin: () -> Unit, onSuccess: () -> Unit) {
    if (settings.adminLockMethod == "custom_pin") {
        requestCustomPin()
        return
    }

    val executor = ContextCompat.getMainExecutor(activity)
    val biometricPrompt = BiometricPrompt(activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode != BiometricPrompt.ERROR_CANCELED && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS || errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE || errorCode == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                        onSuccess()
                    } else {
                        activity.finish()
                    }
                }
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }
        })

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Authentication Required")
        .setSubtitle("Confirm your identity to access Vault Admin")
        .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()

    biometricPrompt.authenticate(promptInfo)
}

class AdminActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val settings = remember { SettingsManager.getInstance(context) }
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            
            var isAuthenticated by remember { mutableStateOf(false) }
            var authCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            var currentTab by remember { mutableStateOf(0) } // 0 = Profiles, 1 = Settings
            var isFakeCrashBypassed by remember { mutableStateOf(!settings.enableFakeCrash) }

            // Only trigger auth AFTER fake crash is bypassed (or if fake crash is disabled)
            LaunchedEffect(isFakeCrashBypassed) {
                if (isFakeCrashBypassed && !isAuthenticated) {
                    authenticate(this@AdminActivity, settings, requestCustomPin = { 
                        authCallback = { isAuthenticated = true }
                    }) { isAuthenticated = true }
                }
            }
            
            if (authCallback != null) {
                var pinInput by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = { authCallback = null },
                    title = { Text("App Locked") },
                    text = {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("Custom App PIN") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (pinInput == settings.adminCustomPin) {
                                authCallback?.invoke()
                                authCallback = null
                            } else {
                                Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Unlock") }
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            authCallback = null 
                            if (!isAuthenticated) finish()
                        }) { Text("Cancel") }
                    }
                )
            }
            
            val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    // Step 1: Fake crash screen (if enabled and not yet bypassed)
                    if (settings.enableFakeCrash && !isFakeCrashBypassed) {
                        FakeCrashScreen(
                            onBypass = { isFakeCrashBypassed = true },
                            onExit = { this@AdminActivity.finish() }
                        )
                    } else if (isAuthenticated) {
                        // Step 2: After bypass + auth, show admin UI
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            AdminScreen(this@AdminActivity, settings, currentTab) { cb -> authCallback = cb }
                            
                            // Material 3 Expressive Icon-Only Floating Navigation Bar
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp)
                                    .height(64.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                shadowElevation = 16.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        0 to Icons.Filled.AccountCircle,
                                        1 to Icons.Filled.Settings
                                    ).forEach { (tabIndex, icon) ->
                                        val isSelected = currentTab == tabIndex
                                        val animatedBg by animateColorAsState(
                                            targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                        val animatedIconColor by animateColorAsState(
                                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                            animationSpec = tween(300, easing = FastOutSlowInEasing)
                                        )
                                        val animatedScale by animateFloatAsState(
                                            targetValue = if (isSelected) 1.15f else 1.0f,
                                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                        )

                                        Surface(
                                            onClick = { currentTab = tabIndex },
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = animatedBg,
                                            modifier = Modifier.size(50.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    icon,
                                                    contentDescription = if (tabIndex == 0) "Profiles" else "Settings",
                                                    tint = animatedIconColor,
                                                    modifier = Modifier.size(24.dp).graphicsLayer(scaleX = animatedScale, scaleY = animatedScale)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileListDialog(
    profiles: List<Profile>,
    currentProfileId: String,
    onSelectProfile: (Profile) -> Unit,
    onDeleteProfile: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var profileToDelete by remember { mutableStateOf<Profile?>(null) }

    if (profileToDelete != null) {
        AlertDialog(
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Are you sure you want to delete profile '${profileToDelete?.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    profileToDelete?.let { onDeleteProfile(it) }
                    profileToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { profileToDelete = null }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Profiles") },
        text = {
            if (profiles.isEmpty()) {
                Text("No profiles available.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(profiles, key = { it.id }) { profile ->
                        val isSelected = profile.id == currentProfileId
                        val containerColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = tween(durationMillis = 300)
                        )
                        val contentColor by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(durationMillis = 300)
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = containerColor
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(profile.name, fontWeight = FontWeight.Bold, color = contentColor)
                                    Text(
                                        if (profile.isActive) "Active (Hidden)" else "Inactive",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = contentColor.copy(alpha = 0.8f)
                                    )
                                }
                                IconButton(onClick = { onSelectProfile(profile) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit Profile", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { profileToDelete = profile }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Profile", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun UnsavedChangesDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Changes") },
        text = { Text("Do you want to save your profile before switching or creating a new one?") },
        confirmButton = {
            TextButton(onClick = onSave) { Text("Save Profile") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(activity: FragmentActivity, settings: SettingsManager, currentTab: Int, requestCustomAuth: ((() -> Unit) -> Unit)) {
    val context = LocalContext.current

    var profiles by remember { mutableStateOf(settings.profiles) }
    
    // Ensure there is at least one profile to display
    LaunchedEffect(Unit) {
        if (profiles.isEmpty()) {
            val defaultProfile = Profile(
                id = UUID.randomUUID().toString(),
                name = "Main Profile",
                packages = emptyList(),
                directories = emptyList(),
                dummyDirectories = emptyList(),
                isActive = true,
                hideOnDecoy = true,
                decoyPin = "1234"
            )
            profiles = listOf(defaultProfile)
            settings.profiles = profiles
        }
    }

    var selectedProfileId by remember { mutableStateOf(profiles.firstOrNull()?.id ?: "") }
    var editingProfileState by remember(selectedProfileId) {
        mutableStateOf(profiles.find { it.id == selectedProfileId } ?: (profiles.firstOrNull() ?: Profile(UUID.randomUUID().toString(), "New Profile", emptyList(), emptyList(), emptyList(), false, false, "")))
    }
    var originalProfileState by remember(selectedProfileId) { mutableStateOf(editingProfileState.copy()) }

    val isDirty by remember(editingProfileState, originalProfileState) { derivedStateOf { editingProfileState != originalProfileState } }

    var showProfileListModal by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var codeUnlock by remember { mutableStateOf(settings.codeUnlock) }
    var codeLock by remember { mutableStateOf(settings.codeLock) }
    var codeDecoy by remember { mutableStateOf(settings.codeDecoy) }
    var codeAdmin by remember { mutableStateOf(settings.codeAdmin) }
    var decoyLockScreenMode by remember { mutableStateOf(settings.decoyLockScreenMode) }
    var decoyUnlockLimit by remember { mutableStateOf(settings.decoyUnlockLimit) }

    var isAppPickerOpen by remember { mutableStateOf(false) }
    var showAddDummyDialog by remember { mutableStateOf(false) }
    var showPinPromptForProfile by remember { mutableStateOf(false) }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: ""
            val absolutePath = if (path.contains("primary:")) {
                "/sdcard/" + path.substringAfter("primary:")
            } else {
                "/storage/" + path.replace("/tree/", "").replace(":", "/")
            }
            if (!editingProfileState.directories.contains(absolutePath)) {
                editingProfileState = editingProfileState.copy(directories = editingProfileState.directories + absolutePath)
            }
        }
    }

    fun saveCurrentProfile() {
        val updated = profiles.map { if (it.id == editingProfileState.id) editingProfileState else it }
        val finalProfiles = if (updated.any { it.id == editingProfileState.id }) updated else updated + editingProfileState
        profiles = finalProfiles
        settings.profiles = finalProfiles
        originalProfileState = editingProfileState.copy()
        Toast.makeText(context, "Profile saved", Toast.LENGTH_SHORT).show()
    }

    fun createNewProfile() {
        val newProf = Profile(
            id = UUID.randomUUID().toString(),
            name = "New Profile",
            packages = emptyList(),
            directories = emptyList(),
            dummyDirectories = emptyList(),
            isActive = true,
            hideOnDecoy = false,
            decoyPin = ""
        )
        profiles = profiles + newProf
        settings.profiles = profiles
        selectedProfileId = newProf.id
        editingProfileState = newProf
        originalProfileState = newProf.copy()
    }

    if (pendingAction != null) {
        UnsavedChangesDialog(
            onSave = {
                saveCurrentProfile()
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            onDiscard = {
                editingProfileState = originalProfileState.copy()
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            onCancel = {
                pendingAction = null
            }
        )
    }

    if (showProfileListModal) {
        ProfileListDialog(
            profiles = profiles,
            currentProfileId = selectedProfileId,
            onSelectProfile = { prof ->
                selectedProfileId = prof.id
                editingProfileState = prof.copy()
                originalProfileState = prof.copy()
                showProfileListModal = false
            },
            onDeleteProfile = { prof ->
                profiles = profiles.filter { it.id != prof.id }
                settings.profiles = profiles
                if (selectedProfileId == prof.id) {
                    val nextProf = profiles.firstOrNull()
                    if (nextProf != null) {
                        selectedProfileId = nextProf.id
                        editingProfileState = nextProf.copy()
                        originalProfileState = nextProf.copy()
                    } else {
                        createNewProfile()
                    }
                }
            },
            onDismiss = { showProfileListModal = false }
        )
    }

    if (isAppPickerOpen) {
        AppPickerDialog(
            initialSelection = editingProfileState.packages,
            onDismiss = { isAppPickerOpen = false },
            onConfirm = { selected ->
                editingProfileState = editingProfileState.copy(packages = selected)
                isAppPickerOpen = false
            }
        )
    }
    
    if (showPinPromptForProfile) {
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinPromptForProfile = false },
            title = { Text("Unlock Vault") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Vault PIN") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val stateManager = VaultStateManager(context)
                    val ok = StorageMountManager.unhideProfile(editingProfileState, pinInput, stateManager.keySalt())
                    if (ok) {
                        Toast.makeText(context, "Profile Unhidden", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Failed to unlock vault", Toast.LENGTH_SHORT).show()
                    }
                    showPinPromptForProfile = false
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { showPinPromptForProfile = false }) { Text("Cancel") }
            }
        )
    }

    var pendingTargetFolder by remember { mutableStateOf<String?>(null) }
    var pendingDummyFolder by remember { mutableStateOf<String?>(null) }

    val dummyTargetPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: ""
            val absolutePath = if (path.contains("primary:")) {
                "/sdcard/" + path.substringAfter("primary:")
            } else {
                "/storage/" + path.replace("/tree/", "").replace(":", "/")
            }
            pendingTargetFolder = absolutePath
        }
    }

    val dummyFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val path = it.path ?: ""
            val absolutePath = if (path.contains("primary:")) {
                "/sdcard/" + path.substringAfter("primary:")
            } else {
                "/storage/" + path.replace("/tree/", "").replace(":", "/")
            }
            pendingDummyFolder = absolutePath
        }
    }

    if (showAddDummyDialog) {
        var targetPath by remember { mutableStateOf(pendingTargetFolder ?: "") }
        var dummyPath by remember { mutableStateOf(pendingDummyFolder ?: "") }
        var encrypt by remember { mutableStateOf(false) }

        LaunchedEffect(pendingTargetFolder) {
            pendingTargetFolder?.let { targetPath = it }
        }
        LaunchedEffect(pendingDummyFolder) {
            pendingDummyFolder?.let { dummyPath = it }
        }

        AlertDialog(
            onDismissRequest = { 
                showAddDummyDialog = false
                pendingTargetFolder = null
                pendingDummyFolder = null
            },
            title = { Text("Add Dummy Folder Mapping") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = targetPath,
                            onValueChange = { targetPath = it },
                            label = { Text("Folder to hide (Target)") },
                            placeholder = { Text("/sdcard/SecretFolder") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { dummyTargetPickerLauncher.launch(null) }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Choose Target Folder", tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = dummyPath,
                            onValueChange = { dummyPath = it },
                            label = { Text("Folder to show (Dummy)") },
                            placeholder = { Text("/sdcard/FakeFolder") },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = { dummyFolderPickerLauncher.launch(null) }) {
                            Icon(Icons.Filled.FolderSpecial, contentDescription = "Choose Dummy Folder", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Text(
                        "When locked, the Dummy folder will be mounted over the Target folder. Anyone opening the Target folder will see the Dummy folder's contents instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = encrypt,
                            onCheckedChange = { encrypt = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Encrypt original contents", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Moves the Target folder into the encrypted Vault.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (targetPath.isNotBlank() && dummyPath.isNotBlank()) {
                        editingProfileState = editingProfileState.copy(dummyDirectories = editingProfileState.dummyDirectories + com.thenile.vault.state.DummyDir(targetPath, dummyPath, encrypt))
                    }
                    showAddDummyDialog = false
                    pendingTargetFolder = null
                    pendingDummyFolder = null
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDummyDialog = false
                    pendingTargetFolder = null
                    pendingDummyFolder = null
                }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally { width -> width } + fadeIn(tween(250))).togetherWith(
                        slideOutHorizontally { width -> -width } + fadeOut(tween(250))
                    )
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn(tween(250))).togetherWith(
                        slideOutHorizontally { width -> width } + fadeOut(tween(250))
                    )
                }
            },
            label = "TabTransition"
        ) { targetTab ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .padding(bottom = 100.dp) // Extra padding for the floating bar
            ) {
                if (targetTab == 0) {
                    // Expressive Shield Header Card
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_nile_river_transparent),
                                    contentDescription = "The Nile Logo",
                                    modifier = Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("The Nile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("Denial is not just a river in Egypt", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AssistChip(
                                onClick = {},
                                label = { Text(if (editingProfileState.isActive) "ACTIVE" else "INACTIVE") },
                                leadingIcon = {
                                    Icon(if (editingProfileState.isActive) Icons.Filled.CheckCircle else Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (editingProfileState.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                                    labelColor = if (editingProfileState.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                ),
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                        }
                    }

                    // Profile Selection Banner
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("CURRENT PROFILE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(editingProfileState.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        if (isDirty) {
                                            pendingAction = { showProfileListModal = true }
                                        } else {
                                            showProfileListModal = true
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Switch")
                                }
                                IconButton(
                                    onClick = {
                                        if (isDirty) {
                                            pendingAction = { createNewProfile() }
                                        } else {
                                            createNewProfile()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add Profile", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // Single Profile Editor Card
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            
                            OutlinedTextField(
                                value = editingProfileState.name,
                                onValueChange = { newName ->
                                    editingProfileState = editingProfileState.copy(name = newName)
                                },
                                label = { Text("Profile Name") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = editingProfileState.decoyPin,
                                onValueChange = { newDecoyPin ->
                                    editingProfileState = editingProfileState.copy(decoyPin = newDecoyPin, hideOnDecoy = newDecoyPin.isNotBlank())
                                },
                                label = { Text("Decoy PIN for THIS profile") },
                                placeholder = { Text("e.g. 1234") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Active (Hidden)", modifier = Modifier.weight(1f))
                                Switch(
                                    checked = editingProfileState.isActive,
                                    onCheckedChange = { checked ->
                                        editingProfileState = editingProfileState.copy(isActive = checked)
                                    }
                                )
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                FilledTonalButton(
                                    onClick = {
                                        saveCurrentProfile()
                                        val stateManager = VaultStateManager.getInstance(context)
                                        stateManager.updateState(VaultState.LOCKED)
                                        authenticate(activity, settings, requestCustomPin = {
                                            requestCustomAuth {
                                                StorageMountManager.unmountAndLock(settings.targetPackages, settings.targetDirectories, settings.targetDummyDirectories)
                                                Toast.makeText(context, "Profile and apps hidden", Toast.LENGTH_SHORT).show()
                                            }
                                        }) {
                                            StorageMountManager.unmountAndLock(settings.targetPackages, settings.targetDirectories, settings.targetDummyDirectories)
                                            Toast.makeText(context, "Profile and apps hidden", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                ) { 
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Hide") 
                                }
                                
                                FilledTonalButton(
                                    onClick = {
                                        saveCurrentProfile()
                                        val stateManager = VaultStateManager.getInstance(context)
                                        val onAuthSuccess = {
                                            stateManager.updateState(VaultState.UNLOCKED)
                                            val ok = StorageMountManager.mountRealContainer(settings.targetPackages, settings.targetDirectories, settings.targetDummyDirectories, "", stateManager.keySalt())
                                            if (ok) {
                                                Toast.makeText(context, "Profile and apps unhidden", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showPinPromptForProfile = true
                                            }
                                        }
                                        authenticate(activity, settings, requestCustomPin = {
                                            requestCustomAuth(onAuthSuccess)
                                        }, onSuccess = onAuthSuccess)
                                    },
                                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                                ) { 
                                    Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Unhide") 
                                }
                            }

                            // Hidden Apps Section
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Hidden Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                FilledTonalButton(onClick = { isAppPickerOpen = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Choose Apps")
                                }
                            }
                            if (editingProfileState.packages.isEmpty()) {
                                Text("No apps selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                editingProfileState.packages.forEach { pkg ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            IconButton(onClick = { 
                                                editingProfileState = editingProfileState.copy(packages = editingProfileState.packages.filter { p -> p != pkg })
                                            }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            // Hidden Directories Section
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Hidden Directories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                FilledTonalButton(onClick = { dirPickerLauncher.launch(null) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Choose Folder")
                                }
                            }
                            if (editingProfileState.directories.isEmpty()) {
                                Text("No directories selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                editingProfileState.directories.forEach { dir ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(dir, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            IconButton(onClick = { 
                                                editingProfileState = editingProfileState.copy(directories = editingProfileState.directories.filter { d -> d != dir })
                                            }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            // Dummy Directories Section
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Dummy Folders (Replace)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                FilledTonalButton(onClick = { showAddDummyDialog = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Add Mapping")
                                }
                            }
                            if (editingProfileState.dummyDirectories.isEmpty()) {
                                Text("No dummy folders configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                editingProfileState.dummyDirectories.forEach { dummy ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Target: ${dummy.target}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                                Text("Dummy: ${dummy.dummy}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { 
                                                editingProfileState = editingProfileState.copy(dummyDirectories = editingProfileState.dummyDirectories.filter { d -> d != dummy })
                                            }, modifier = Modifier.size(32.dp)) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = { saveCurrentProfile() },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = androidx.compose.foundation.shape.CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Save Profile", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (targetTab == 1) {
                    var adminLockMethod by remember { mutableStateOf(settings.adminLockMethod) }
                    var adminCustomPin by remember { mutableStateOf(settings.adminCustomPin) }
                    var hideAppIcon by remember { mutableStateOf(settings.hideAppIcon) }

                    // Header Card
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Global Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Security, Dial Codes & Stealth", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    var enableTile by remember { mutableStateOf(settings.enableTile) }
                    var enableDeepLink by remember { mutableStateOf(settings.enableDeepLink) }
                    var enableVolumeKeys by remember { mutableStateOf(settings.enableVolumeKeys) }
                    var enableCalculatorDecoy by remember { mutableStateOf(settings.enableCalculatorDecoy) }
                    var calculatorTriggerExpression by remember { mutableStateOf(settings.calculatorTriggerExpression) }
                    var enableFakeCrash by remember { mutableStateOf(settings.enableFakeCrash) }

                    // Card 1: Admin Protection & Security
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Admin Authentication", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = adminLockMethod == "biometric", onClick = { adminLockMethod = "biometric" })
                                Text("Device Fingerprint / System Lock")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = adminLockMethod == "custom_pin", onClick = { adminLockMethod = "custom_pin" })
                                Text("Custom Admin PIN")
                            }

                            AnimatedVisibility(visible = adminLockMethod == "custom_pin") {
                                OutlinedTextField(
                                    value = adminCustomPin,
                                    onValueChange = { adminCustomPin = it },
                                    label = { Text("Custom App PIN") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    // Card 2: Launch & Opening Methods
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Vault Opening Methods", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            // Quick Settings Tile Toggle
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Quick Settings Tile", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Add tile to notification shade for 1-tap open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = enableTile, onCheckedChange = { enableTile = it })
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Browser Deep Link Toggle
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Browser Deep Link (nile://admin)", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Open vault by typing nile://admin in any browser", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = enableDeepLink, onCheckedChange = { enableDeepLink = it })
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Hardware Volume Buttons Toggle
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Volume Down Double-Tap", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text("Double tap Volume Down key to open", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = enableVolumeKeys, onCheckedChange = { enableVolumeKeys = it })
                                }
                                AnimatedVisibility(visible = enableVolumeKeys) {
                                    Column {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Enable Nile Accessibility Service")
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Calculator Decoy Trigger
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Calculator Decoy Trigger", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Hooks the REAL Calculator app (no fake app to spot) — typing the expression below opens Admin.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(checked = enableCalculatorDecoy, onCheckedChange = { enableCalculatorDecoy = it })
                                }
                                AnimatedVisibility(visible = enableCalculatorDecoy) {
                                    Column {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = calculatorTriggerExpression,
                                            onValueChange = { calculatorTriggerExpression = it },
                                            label = { Text("Trigger expression") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            "One-time setup: grant Nile Xposed scope on your Calculator app, then reboot. Works with Google Calculator and AOSP Calculator.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = {
                                                Thread {
                                                    Shell.cmd(
                                                        "/data/adb/modules/zygisk_vector/cli scope add com.thenile.vault " +
                                                            "com.android.calculator2/0 com.google.android.calculator/0"
                                                    ).exec()
                                                }.start()
                                                Toast.makeText(context, "Scope granted — reboot for it to take effect", Toast.LENGTH_LONG).show()
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Filled.Calculate, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Grant Calculator App Access")
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Fake Crash Disguise Toggle
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Fake Crash Disguise", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Show fake crash screen on app launch; long press 'Close app' to bypass", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = enableFakeCrash, onCheckedChange = { enableFakeCrash = it })
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Hide App Icon Toggle
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Hide Nile App Icon", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Text("Hides launcher icon; open via dialer, tile, or deep link", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = hideAppIcon, onCheckedChange = { hideAppIcon = it })
                            }
                        }
                    }

                    // Card 3: Secret Dial Codes
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Dialpad, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Secret Dial Codes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            Text(
                                "Android only delivers a dial code to an app if that exact code is " +
                                    "built into the app beforehand — so these four are fixed, you're " +
                                    "just choosing which action each one triggers (*#<CODE>#). Picking " +
                                    "a code already used elsewhere swaps the two.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            DialCodeDropdown(
                                label = "Unlock Code",
                                icon = Icons.Filled.LockOpen,
                                selected = codeUnlock,
                                onSelect = { new ->
                                    val old = codeUnlock
                                    codeUnlock = new
                                    if (codeLock == new) codeLock = old
                                    else if (codeDecoy == new) codeDecoy = old
                                    else if (codeAdmin == new) codeAdmin = old
                                }
                            )
                            DialCodeDropdown(
                                label = "Lock Code",
                                icon = Icons.Filled.Lock,
                                selected = codeLock,
                                onSelect = { new ->
                                    val old = codeLock
                                    codeLock = new
                                    if (codeUnlock == new) codeUnlock = old
                                    else if (codeDecoy == new) codeDecoy = old
                                    else if (codeAdmin == new) codeAdmin = old
                                }
                            )
                            DialCodeDropdown(
                                label = "Master Decoy Code",
                                icon = Icons.Filled.Shield,
                                selected = codeDecoy,
                                onSelect = { new ->
                                    val old = codeDecoy
                                    codeDecoy = new
                                    if (codeUnlock == new) codeUnlock = old
                                    else if (codeLock == new) codeLock = old
                                    else if (codeAdmin == new) codeAdmin = old
                                }
                            )
                            DialCodeDropdown(
                                label = "Admin Code",
                                icon = Icons.Filled.AdminPanelSettings,
                                selected = codeAdmin,
                                onSelect = { new ->
                                    val old = codeAdmin
                                    codeAdmin = new
                                    if (codeUnlock == new) codeUnlock = old
                                    else if (codeLock == new) codeLock = old
                                    else if (codeDecoy == new) codeDecoy = old
                                }
                            )
                        }
                    }

                    // Card 3b: Real Lock Screen Decoy
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Real Lock Screen Decoy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }

                            Text(
                                "Advanced. Entering the Master Decoy Code or any profile's decoy PIN on the ACTUAL Android lock screen (not just Nile's own PIN pad) triggers the hide. A bug here risks getting locked out of your real phone — leave Off unless you understand that risk.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    "off" to "Off (default)",
                                    "fake_wrong_pin" to "Fake wrong-PIN, then real PIN unlocks normally",
                                    "one_time_unlock" to "Decoy also unlocks once, then reverts to a normal wrong PIN",
                                ).forEach { (value, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable { decoyLockScreenMode = value }
                                    ) {
                                        RadioButton(selected = decoyLockScreenMode == value, onClick = { decoyLockScreenMode = value })
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }

                            if (decoyLockScreenMode == "one_time_unlock") {
                                val usedCount = settings.decoyUnlockUsedCount
                                val unlimited = decoyUnlockLimit == 0
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (unlimited) "$usedCount used so far (unlimited)." else "$usedCount of $decoyUnlockLimit used.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (usedCount > 0) {
                                        OutlinedButton(onClick = { settings.rearmDecoyOneTimeUnlock() }) { Text("Re-arm") }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(
                                        checked = unlimited,
                                        onCheckedChange = { checked -> decoyUnlockLimit = if (checked) 0 else 1 }
                                    )
                                    Text("Unlimited uses", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                    if (!unlimited) {
                                        OutlinedTextField(
                                            value = decoyUnlockLimit.toString(),
                                            onValueChange = { text ->
                                                val n = text.filter { it.isDigit() }.toIntOrNull()
                                                if (n != null && n > 0) decoyUnlockLimit = n
                                            },
                                            label = { Text("Times") },
                                            singleLine = true,
                                            modifier = Modifier.width(90.dp)
                                        )
                                    }
                                }
                                Text(
                                    "Uses an unsupported internal Android API to unlock (no sanctioned " +
                                        "public API exists for this). Test thoroughly on the emulator " +
                                        "before relying on it on your real phone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // Card 4: Profile Backup & Restore
                    var showExportPasswordDialog by remember { mutableStateOf(false) }
                    var showImportPasswordDialog by remember { mutableStateOf(false) }
                    var backupPassword by remember { mutableStateOf("") }
                    var backupPasswordConfirm by remember { mutableStateOf("") }
                    var backupError by remember { mutableStateOf<String?>(null) }

                    val exportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/octet-stream")
                    ) { uri ->
                        uri?.let {
                            try {
                                context.contentResolver.openOutputStream(it)?.use { stream ->
                                    BackupManager.exportBackup(settings.profiles, backupPassword, stream)
                                    Toast.makeText(context, "Backup exported successfully", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            backupPassword = ""
                            backupPasswordConfirm = ""
                        }
                    }

                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        uri?.let {
                            try {
                                context.contentResolver.openInputStream(it)?.use { stream ->
                                    val count = BackupManager.importBackup(settings, backupPassword, stream)
                                    profiles = settings.profiles
                                    Toast.makeText(context, "Imported $count profiles successfully", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            backupPassword = ""
                        }
                    }

                    if (showExportPasswordDialog) {
                        AlertDialog(
                            onDismissRequest = { showExportPasswordDialog = false; backupPassword = ""; backupPasswordConfirm = ""; backupError = null },
                            title = { Text("Export Backup Password") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Enter a password to encrypt your backup file.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedTextField(
                                        value = backupPassword,
                                        onValueChange = { backupPassword = it; backupError = null },
                                        label = { Text("Password") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = backupPasswordConfirm,
                                        onValueChange = { backupPasswordConfirm = it; backupError = null },
                                        label = { Text("Confirm Password") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (backupError != null) {
                                        Text(backupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    when {
                                        backupPassword.isBlank() -> backupError = "Password cannot be empty"
                                        backupPassword != backupPasswordConfirm -> backupError = "Passwords do not match"
                                        else -> {
                                            showExportPasswordDialog = false
                                            backupError = null
                                            exportLauncher.launch("nile_backup.nile")
                                        }
                                    }
                                }) { Text("Export") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showExportPasswordDialog = false; backupPassword = ""; backupPasswordConfirm = ""; backupError = null }) { Text("Cancel") }
                            }
                        )
                    }

                    if (showImportPasswordDialog) {
                        AlertDialog(
                            onDismissRequest = { showImportPasswordDialog = false; backupPassword = "" },
                            title = { Text("Import Backup Password") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Enter the password used when exporting this backup.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedTextField(
                                        value = backupPassword,
                                        onValueChange = { backupPassword = it },
                                        label = { Text("Password") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showImportPasswordDialog = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }) { Text("Import") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showImportPasswordDialog = false; backupPassword = "" }) { Text("Cancel") }
                            }
                        )
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(32.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Profile Backup & Restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Export or import encrypted .nile vault backups", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showExportPasswordDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Export Backup")
                                }
                                OutlinedButton(
                                    onClick = { showImportPasswordDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Import Backup")
                                }
                            }
                        }
                    }

                    // Save Settings Button
                    Button(
                        onClick = {
                            settings.profiles = profiles
                            settings.codeUnlock = codeUnlock.trim()
                            settings.codeLock = codeLock.trim()
                            settings.codeDecoy = codeDecoy.trim()
                            settings.codeAdmin = codeAdmin.trim()
                            settings.decoyLockScreenMode = decoyLockScreenMode
                            settings.decoyUnlockLimit = decoyUnlockLimit
                            settings.adminLockMethod = adminLockMethod
                            settings.adminCustomPin = adminCustomPin.trim()
                            settings.hideAppIcon = hideAppIcon
                            settings.enableTile = enableTile
                            settings.enableDeepLink = enableDeepLink
                            settings.enableVolumeKeys = enableVolumeKeys
                            settings.enableCalculatorDecoy = enableCalculatorDecoy
                            settings.calculatorTriggerExpression = calculatorTriggerExpression.trim()
                            settings.enableFakeCrash = enableFakeCrash
                            Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = androidx.compose.foundation.shape.CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Settings & Apply", fontWeight = FontWeight.Bold)
                    }

                    // About & Developer Card
                    Spacer(modifier = Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_nile_river_transparent),
                                contentDescription = "The Nile Logo",
                                modifier = Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("The Nile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Text("v1.0 \u2022 Stealth Vault Engine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Denial is not just a river in Egypt", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 11.sp)
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/binkemet/thenile")))
                                }
                            ) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Developed by", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("binkemet", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    Text("github.com/binkemet/thenile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                            Text("Donate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)

                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            val btcAddress = "sp1qqdvl0u637wtyjf4paa2khvc4dgy4ehsf8grsaqqwpmsxcnzagc0tcqjffw6k4jvd5dwf454r9qrnmgp5g25w2fkkf76w5hz47zzmmgnkpgyvmxjy"
                            val xmrAddress = "89Sd2SnrwCtJEzoens2R5T13uBoqe9ru5VVJDDfBR3Md14jEFA5fFkZB4D9CAdz7fHNS8fyKZK5DYXrMSXWpMnZcQnaqRuu"

                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BTC", btcAddress))
                                    Toast.makeText(context, "Bitcoin address copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text("\u20BF", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bitcoin (BTC)", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }

                            OutlinedButton(
                                onClick = {
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("XMR", xmrAddress))
                                    Toast.makeText(context, "Monero address copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text("ɱ", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Monero (XMR)", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }

                            Text(
                                "Free & open source \u2022 No ads \u2022 No tracking",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    initialSelection: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager
    
    // Get ALL apps
    val apps = remember {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .sortedBy { pm.getApplicationLabel(it).toString() }
    }
    
    var selected by remember { mutableStateOf(initialSelection.toSet()) }
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredApps = remember(searchQuery, apps) {
        apps.filter { 
            pm.getApplicationLabel(it).toString().contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Apps to Hide") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...") },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                LazyColumn {
                    items(filteredApps) { app ->
                    val pkg = app.packageName
                    val name = pm.getApplicationLabel(app).toString()
                    val isChecked = selected.contains(pkg)
                    
                    val iconBitmap = remember(pkg) {
                        try {
                            pm.getApplicationIcon(app).toBitmap(128, 128).asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = if (isChecked) selected - pkg else selected + pkg
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Column {
                            Text(name, fontWeight = FontWeight.Bold)
                            Text(pkg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.toList()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
