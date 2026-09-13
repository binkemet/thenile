package com.thenile.vault.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.topjohnwu.superuser.Shell
import com.thenile.vault.R
import com.thenile.vault.backup.BackupManager
import com.thenile.vault.root.PrivilegeManager
import com.thenile.vault.root.PrivilegeTier
import com.thenile.vault.root.StorageMountManager
import com.thenile.vault.state.Vault
import com.thenile.vault.state.SettingsManager
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager
import rikka.shizuku.Shizuku
import java.util.UUID
import kotlinx.coroutines.delay

// -------------------------------------------------------------------------------------------------
// System Android Users
// -------------------------------------------------------------------------------------------------

data class AndroidUser(
    val id: Int,
    val name: String,
    val isOwner: Boolean
)

fun fetchAndroidUsers(): List<AndroidUser> {
    return try {
        val res = Shell.cmd("pm list users").exec()
        if (!res.isSuccess) return emptyList()
        res.out.mapNotNull { line ->
            // Match UserInfo{0:Owner:4c13} or UserInfo{10:Decoy:410}
            val match = "UserInfo\\{([0-9]+):([^:]+):".toRegex().find(line)
            if (match != null) {
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val name = match.groupValues[2]
                AndroidUser(id = id, name = name, isOwner = id == 0)
            } else null
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun createDecoyAndroidUser(name: String = "Decoy"): Int? {
    return try {
        Shell.cmd("setprop fw.max_users 8; setprop config.fw_max_users 8; settings put global fw_max_users 8").exec()
        val res = Shell.cmd("pm create-user '$name'").exec()
        if (res.isSuccess) {
            // Output: "Success: created user id 10"
            val match = "([0-9]+)".toRegex().find(res.out.joinToString(" "))
            match?.groupValues?.get(1)?.toIntOrNull()
        } else null
    } catch (e: Exception) {
        null
    }
}

// -------------------------------------------------------------------------------------------------
// Dial Code Text Field
// -------------------------------------------------------------------------------------------------

@Composable
fun DialCodeTextField(
    label: String,
    icon: ImageVector,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val cleaned = input
                .removePrefix("*#")
                .removeSuffix("#")
                .trim()
            onValueChange(cleaned)
        },
        label = { Text(label) },
        prefix = { Text("*#", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        suffix = { Text("#", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        placeholder = { Text("e.g. 1234") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    )
}

// -------------------------------------------------------------------------------------------------
// KernelSU-Next Inspired Floating Navigation Bar & Expressive UI Components
// -------------------------------------------------------------------------------------------------

@Composable
fun FloatingNavigationBar(
    currentTab: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                ambientColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.2f)
            )
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tabs = listOf(
                Triple(0, "Vaults", Icons.Filled.Shield),
                Triple(1, "Settings", Icons.Filled.Settings)
            )

            tabs.forEach { (index, label, icon) ->
                val isSelected = currentTab == index
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) 1.04f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "tab_scale_$index"
                )
                val backgroundColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = 220),
                    label = "tab_bg_$index"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 220),
                    label = "tab_color_$index"
                )

                Surface(
                    modifier = Modifier
                        .graphicsLayer(scaleX = scale, scaleY = scale)
                        .clip(CircleShape)
                        .clickable {
                            if (!isSelected) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onTabSelected(index)
                            }
                        },
                    shape = CircleShape,
                    color = backgroundColor
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = contentColor,
                            modifier = Modifier.size(20.dp)
                        )
                        AnimatedVisibility(
                            visible = isSelected,
                            enter = fadeIn(tween(180)) + expandHorizontally(
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            ),
                            exit = fadeOut(tween(120)) + shrinkHorizontally(animationSpec = tween(120))
                        ) {
                            Row {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    color = contentColor,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreferenceSwitchRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Surface(
                    shape = CircleShape,
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
fun SelectableOptionCard(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeaderCard(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = iconContainerColor,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = iconContentColor, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (trailingContent != null) {
                    trailingContent()
                }
            }
            content()
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Authentication
// -------------------------------------------------------------------------------------------------

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

// -------------------------------------------------------------------------------------------------
// Activity Entry
// -------------------------------------------------------------------------------------------------

class AdminActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureFlag(this)

        setContent {
            val context = LocalContext.current
            val settings = remember { SettingsManager.getInstance(context) }
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            
            var isAuthenticated by remember { mutableStateOf(false) }
            var authCallback by remember { mutableStateOf<(() -> Unit)?>(null) }
            var currentTab by remember { mutableStateOf(0) } // 0 = Vaults, 1 = Settings
            // Back gesture returns to Vaults from Settings before exiting the app. The
            // Settings-tab category sub-navigation has its own nested BackHandler (in
            // AdminScreen) that takes priority while a category is open, so a single back
            // press there closes the category first, and only a second press lands here.
            BackHandler(enabled = currentTab != 0) { currentTab = 0 }
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
                var showPinInput by remember { mutableStateOf(false) }
                AlertDialog(
                    // Must match Cancel's behavior, not just clear the callback: the LaunchedEffect
                    // that triggers authentication only fires once per activity instance, so a
                    // back-press/outside-tap dismiss that leaves the activity alive strands the user
                    // on a permanently blank screen (isAuthenticated=false, authCallback=null, no
                    // path back to the dialog) — confirmed on-device, force-stop was the only way out.
                    onDismissRequest = {
                        authCallback = null
                        if (!isAuthenticated) finish()
                    },
                    title = { Text("App Locked", fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it },
                            label = { Text("Admin Password") },
                            visualTransformation = if (showPinInput) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showPinInput = !showPinInput }) {
                                    Icon(
                                        if (showPinInput) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showPinInput) "Hide password" else "Show password"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(16.dp)
                        )
                    },
                    confirmButton = {
                        FilledTonalButton(onClick = {
                            if (pinInput == settings.adminCustomPin) {
                                authCallback?.invoke()
                                authCallback = null
                            } else {
                                Toast.makeText(context, "Incorrect password", Toast.LENGTH_SHORT).show()
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Step 1: Fake crash screen (if enabled and not yet bypassed)
                    if (settings.enableFakeCrash && !isFakeCrashBypassed) {
                        FakeCrashScreen(
                            onBypass = { isFakeCrashBypassed = true },
                            onExit = { this@AdminActivity.finish() }
                        )
                    } else if (isAuthenticated) {
                        // Step 2: Main container with Floating Navigation Bar
                        Box(modifier = Modifier.fillMaxSize()) {
                            AdminScreen(this@AdminActivity, settings, currentTab) { cb -> authCallback = cb }

                            // Floating Navigation Bar (KernelSU-Next style)
                            FloatingNavigationBar(
                                currentTab = currentTab,
                                onTabSelected = { currentTab = it },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Dialogs
// -------------------------------------------------------------------------------------------------

@Composable
fun VaultListDialog(
    vaults: List<Vault>,
    currentVaultId: String,
    onSelectVault: (Vault) -> Unit,
    onDeleteVault: (Vault) -> Unit,
    onDismiss: () -> Unit
) {
    var vaultToDelete by remember { mutableStateOf<Vault?>(null) }

    if (vaultToDelete != null) {
        AlertDialog(
            onDismissRequest = { vaultToDelete = null },
            title = { Text("Delete Vault", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to delete vault '${vaultToDelete?.name}'?") },
            confirmButton = {
                TextButton(onClick = {
                    vaultToDelete?.let { onDeleteVault(it) }
                    vaultToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { vaultToDelete = null }) { Text("Cancel") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Vaults", fontWeight = FontWeight.Bold) },
        text = {
            if (vaults.isEmpty()) {
                Text("No vaults available.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    items(vaults, key = { it.id }) { vault ->
                        val isSelected = vault.id == currentVaultId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        vault.name,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (vault.isActive) "Active (Hidden)" else "Inactive",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onSelectVault(vault) }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit Vault", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { vaultToDelete = vault }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Vault", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss) { Text("Close") }
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
        title = { Text("Unsaved Changes", fontWeight = FontWeight.Bold) },
        text = { Text("Do you want to save your current vault changes before switching?") },
        confirmButton = {
            FilledTonalButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    )
}

@Composable
fun HelpStepRow(step: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(step, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Main Screen Router
// -------------------------------------------------------------------------------------------------

@Composable
fun AdminScreen(activity: FragmentActivity, settings: SettingsManager, currentTab: Int, requestCustomAuth: ((() -> Unit) -> Unit)) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Shizuku's permission grant happens in its own manager app, so the result comes back via
    // this listener rather than an ActivityResultLauncher — bump privilegeTick so the status row
    // below recomputes PrivilegeManager.currentTier() once it lands. The "All files access" grant
    // (MANAGE_EXTERNAL_STORAGE) has no callback at all — it's a plain Settings screen — so that
    // one's covered by the ON_RESUME observer below instead.
    var privilegeTick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> privilegeTick++ }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) privilegeTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val privilegeTier = remember(privilegeTick) { PrivilegeManager.currentTier() }
    val manageStorageGranted = remember(privilegeTick) { PrivilegeManager.isManageStorageGranted() }

    var softVaultDirUri by remember { mutableStateOf(settings.softVaultDirectoryUri) }
    var showSoftVaultWarning by remember { mutableStateOf(false) }
    val pickSoftVaultDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w("AdminScreen", "takePersistableUriPermission failed: ${e.message}")
            }
            settings.softVaultDirectoryUri = uri.toString()
            softVaultDirUri = uri.toString()
        }
    }

    var vaults by remember { mutableStateOf(settings.vaults) }
    
    // Ensure there is at least one vault to display
    LaunchedEffect(Unit) {
        if (vaults.isEmpty()) {
            val defaultVault = Vault(
                id = UUID.randomUUID().toString(),
                name = "Main Vault",
                actionType = "switch_user",
                targetUserId = 10,
                decoyPin = "1234",
                decoyDialerCode = "1234",
                decoyCalculatorExpression = "47-87+23",
                packages = emptyList(),
                directories = emptyList(),
                dummyDirectories = emptyList(),
                files = emptyList(),
                isActive = true,
                hideOnDecoy = true
            )
            vaults = listOf(defaultVault)
            settings.vaults = vaults
        }
    }

    var selectedVaultId by remember { mutableStateOf(vaults.firstOrNull()?.id ?: "") }
    var editingVaultState by remember(selectedVaultId) {
        mutableStateOf(vaults.find { it.id == selectedVaultId } ?: (vaults.firstOrNull() ?: Vault(
            id = UUID.randomUUID().toString(),
            name = "New Vault",
            actionType = "switch_user",
            targetUserId = 10,
            decoyPin = "",
            decoyDialerCode = "",
            decoyCalculatorExpression = "",
            packages = emptyList(),
            directories = emptyList(),
            dummyDirectories = emptyList(),
            files = emptyList(),
            isActive = true,
            hideOnDecoy = true
        )))
    }
    var originalVaultState by remember(selectedVaultId) { mutableStateOf(editingVaultState.copy()) }

    val isDirty by remember(editingVaultState, originalVaultState) { derivedStateOf { editingVaultState != originalVaultState } }

    var showVaultListModal by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var codeUnlock by remember { mutableStateOf(settings.codeUnlock) }
    var codeLock by remember { mutableStateOf(settings.codeLock) }
    var codeDecoy by remember { mutableStateOf(settings.codeDecoy) }
    var codeAdmin by remember { mutableStateOf(settings.codeAdmin) }
    var decoyLockScreenMode by remember { mutableStateOf(settings.decoyLockScreenMode) }
    var decoyUnlockLimit by remember { mutableStateOf(settings.decoyUnlockLimit) }
    var decoyUserId by remember { mutableStateOf(settings.decoyUserId) }
    var suppressUserSwitchAnimation by remember { mutableStateOf(settings.suppressUserSwitchAnimation) }
    var hideUserSwitcherInQuickSettings by remember { mutableStateOf(settings.hideUserSwitcherInQuickSettings) }
    var hideUserSwitcherInSettings by remember { mutableStateOf(settings.hideUserSwitcherInSettings) }

    var isAppPickerOpen by remember { mutableStateOf(false) }
    var isVaultAppPickerOpen by remember { mutableStateOf(false) }
    var isAccountPickerOpen by remember { mutableStateOf(false) }
    var isRestoreAccountPickerOpen by remember { mutableStateOf(false) }
    var showAddDummyDialog by remember { mutableStateOf(false) }
    var showPinPromptForVault by remember { mutableStateOf(false) }
    var showHowItWorks by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val newFiles = uris.mapNotNull { uri ->
            val path = uri.path ?: return@mapNotNull null
            if (path.contains("primary:")) {
                "/sdcard/" + path.substringAfter("primary:")
            } else if (path.contains("/document/")) {
                val docId = path.substringAfter("/document/")
                if (docId.startsWith("primary:")) {
                    "/sdcard/" + docId.substringAfter("primary:")
                } else {
                    "/storage/" + docId.replace(":", "/")
                }
            } else if (path.contains("/tree/")) {
                "/storage/" + path.replace("/tree/", "").replace(":", "/")
            } else {
                path
            }
        }
        if (newFiles.isNotEmpty()) {
            val combined = (editingVaultState.files + newFiles).distinct()
            editingVaultState = editingVaultState.copy(files = combined)
        }
    }

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
            if (!editingVaultState.directories.contains(absolutePath)) {
                editingVaultState = editingVaultState.copy(directories = editingVaultState.directories + absolutePath)
            }
        }
    }

    fun saveCurrentVault() {
        val updated = vaults.map { if (it.id == editingVaultState.id) editingVaultState else it }
        val finalVaults = if (updated.any { it.id == editingVaultState.id }) updated else updated + editingVaultState
        vaults = finalVaults
        settings.vaults = finalVaults
        originalVaultState = editingVaultState.copy()
        Toast.makeText(context, "Vault saved", Toast.LENGTH_SHORT).show()
    }

    fun createNewVault() {
        val newIndex = vaults.size + 1
        val newProf = Vault(
            id = UUID.randomUUID().toString(),
            name = "Decoy Vault $newIndex",
            actionType = "switch_user",
            targetUserId = 10,
            decoyPin = "",
            decoyDialerCode = "",
            decoyCalculatorExpression = "",
            packages = emptyList(),
            directories = emptyList(),
            dummyDirectories = emptyList(),
            files = emptyList(),
            isActive = true,
            hideOnDecoy = true
        )
        vaults = vaults + newProf
        settings.vaults = vaults
        selectedVaultId = newProf.id
        editingVaultState = newProf
        originalVaultState = newProf.copy()
    }

    var showHideTestWarning by remember { mutableStateOf(!settings.hideTestWarningAck) }
    if (showHideTestWarning) {
        AlertDialog(
            onDismissRequest = { showHideTestWarning = false },
            title = { Text("Test your vault before relying on it", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Hiding can silently fail on some devices (root/Shizuku denied, scoped-storage limits, " +
                        "OEM quirks). Before you trust a vault, trigger it once and confirm the files and apps " +
                        "you hid are actually gone. Nile won't warn you at trigger time — it stays invisible on purpose."
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    settings.hideTestWarningAck = true
                    showHideTestWarning = false
                }) { Text("Got it") }
            }
        )
    }

    if (pendingAction != null) {
        UnsavedChangesDialog(
            onSave = {
                saveCurrentVault()
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            onDiscard = {
                editingVaultState = originalVaultState.copy()
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            },
            onCancel = {
                pendingAction = null
            }
        )
    }

    if (showVaultListModal) {
        VaultListDialog(
            vaults = vaults,
            currentVaultId = selectedVaultId,
            onSelectVault = { prof ->
                selectedVaultId = prof.id
                editingVaultState = prof.copy()
                originalVaultState = prof.copy()
                showVaultListModal = false
            },
            onDeleteVault = { prof ->
                vaults = vaults.filter { it.id != prof.id }
                settings.vaults = vaults
                if (selectedVaultId == prof.id) {
                    val nextProf = vaults.firstOrNull()
                    if (nextProf != null) {
                        selectedVaultId = nextProf.id
                        editingVaultState = nextProf.copy()
                        originalVaultState = nextProf.copy()
                    } else {
                        createNewVault()
                    }
                }
            },
            onDismiss = { showVaultListModal = false }
        )
    }

    if (isAppPickerOpen) {
        AppPickerDialog(
            initialSelection = editingVaultState.packages,
            onDismiss = { isAppPickerOpen = false },
            onConfirm = { selected ->
                editingVaultState = editingVaultState.copy(packages = selected)
                isAppPickerOpen = false
            }
        )
    }

    if (isVaultAppPickerOpen) {
        AppPickerDialog(
            initialSelection = editingVaultState.hiddenApps + editingVaultState.uninstallApps,
            onDismiss = { isVaultAppPickerOpen = false },
            onConfirm = { selected ->
                // Newly picked apps default to data-swap mode; deselected ones drop from both lists.
                val sel = selected.toSet()
                val known = (editingVaultState.hiddenApps + editingVaultState.uninstallApps).toSet()
                val added = sel - known
                editingVaultState = editingVaultState.copy(
                    hiddenApps = (editingVaultState.hiddenApps + added).filter { it in sel },
                    uninstallApps = editingVaultState.uninstallApps.filter { it in sel }
                )
                isVaultAppPickerOpen = false
            }
        )
    }

    if (isAccountPickerOpen) {
        AccountPickerDialog(
            initialSelection = editingVaultState.removeAccounts,
            onDismiss = { isAccountPickerOpen = false },
            onConfirm = { selected ->
                editingVaultState = editingVaultState.copy(removeAccounts = selected)
                isAccountPickerOpen = false
            }
        )
    }

    if (isRestoreAccountPickerOpen) {
        AccountPickerDialog(
            initialSelection = editingVaultState.restoreAccounts,
            onDismiss = { isRestoreAccountPickerOpen = false },
            onConfirm = { selected ->
                editingVaultState = editingVaultState.copy(restoreAccounts = selected)
                isRestoreAccountPickerOpen = false
            }
        )
    }

    if (showPinPromptForVault) {
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPinPromptForVault = false },
            title = { Text("Unlock Vault", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Vault PIN") },
                    shape = RoundedCornerShape(16.dp)
                )
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    val stateManager = VaultStateManager(context)
                    val salt = stateManager.keySalt()
                    val vault = editingVaultState
                    val pin = pinInput
                    showPinPromptForVault = false
                    // Off main thread — see the Hide/Unhide Vault buttons' comment: blocking main
                    // here self-deadlocks the Shizuku tier's bindUserService callback.
                    Thread {
                        val ok = StorageMountManager.unhideVault(vault, pin, salt, context)
                        mainHandler.post {
                            Toast.makeText(context, if (ok) "Vault Unhidden" else "Failed to unlock vault", Toast.LENGTH_SHORT).show()
                        }
                    }.start()
                }) { Text("Unlock") }
            },
            dismissButton = {
                TextButton(onClick = { showPinPromptForVault = false }) { Text("Cancel") }
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
            title = { Text("Add Dummy Folder Mapping", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = targetPath,
                            onValueChange = { targetPath = it },
                            label = { Text("Folder to hide (Target)") },
                            placeholder = { Text("/sdcard/SecretFolder") },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
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
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = { dummyFolderPickerLauncher.launch(null) }) {
                            Icon(Icons.Filled.FolderSpecial, contentDescription = "Choose Dummy Folder", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }

                    Text(
                        "When locked, the Dummy folder will be mounted over the Target folder. Anyone opening the Target folder will see the Dummy folder's contents instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    PreferenceSwitchRow(
                        title = "Encrypt original contents",
                        subtitle = "Moves the Target folder into the encrypted Vault.",
                        checked = encrypt,
                        onCheckedChange = { encrypt = it }
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = {
                    if (targetPath.isNotBlank() && dummyPath.isNotBlank()) {
                        editingVaultState = editingVaultState.copy(dummyDirectories = editingVaultState.dummyDirectories + com.thenile.vault.state.DummyDir(targetPath, dummyPath, encrypt))
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

    AnimatedContent(
        targetState = currentTab,
        transitionSpec = {
            if (targetState > initialState) {
                (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { width -> width / 3 } + fadeIn(tween(220))).togetherWith(
                    slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { width -> -width / 3 } + fadeOut(tween(180))
                )
            } else {
                (slideInHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { width -> -width / 3 } + fadeIn(tween(220))).togetherWith(
                    slideOutHorizontally(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { width -> width / 3 } + fadeOut(tween(180))
                )
            }
        },
        label = "TabTransition"
    ) { targetTab ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (targetTab == 0) {
                // =================================================================================
                // VAULTS TAB
                // =================================================================================

                // 1a. Branding Header (identity only — no actions live here)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_nile_river_transparent),
                        contentDescription = "The Nile Logo",
                        modifier = Modifier.size(40.dp).clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("The Nile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("Denial is not just a river in Egypt", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SuggestionChip(
                        onClick = {},
                        label = { Text(if (editingVaultState.isActive) "PROTECTED" else "INACTIVE", fontWeight = FontWeight.Bold, fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (editingVaultState.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            labelColor = if (editingVaultState.isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        ),
                        shape = CircleShape
                    )
                }

                // 1b. Currently editing which vault — switching/adding vaults, kept
                // separate from the "Quick Actions" card below so it's clear this card is
                // about NAVIGATING between vaults, not about doing anything to this one.
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("EDITING VAULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(editingVaultState.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    if (isDirty) {
                                        pendingAction = { showVaultListModal = true }
                                    } else {
                                        showVaultListModal = true
                                    }
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Switch")
                            }
                            IconButton(
                                onClick = {
                                    if (isDirty) {
                                        pendingAction = { createNewVault() }
                                    } else {
                                        createNewVault()
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add User", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                // 1c. Quick Actions — these apply immediately to this vault's saved hide
                // targets, separate from the editing form below (which needs its own Save).
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column {
                            Text("Quick Actions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "Applies right now using this vault's saved hide targets — not a preview of unsaved edits below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    saveCurrentVault()
                                    val stateManager = VaultStateManager.getInstance(context)
                                    stateManager.updateState(VaultState.LOCKED)
                                    val packages = settings.targetPackages
                                    val directories = settings.targetDirectories
                                    val dummyDirectories = settings.targetDummyDirectories
                                    val files = settings.targetFiles
                                    // Off the main thread: under the Shizuku tier, PrivilegedShell blocks
                                    // waiting for Shizuku's bindUserService callback, which Android delivers
                                    // on the main thread — calling this from Compose's onClick (main thread)
                                    // is a self-deadlock (confirmed on-device: every call times out at
                                    // exactly the 5s bind limit). Root's blocking libsu shell call doesn't
                                    // have this problem, but routes through the same call now, so keep both
                                    // off main for consistency.
                                    Thread {
                                        StorageMountManager.unmountAndLock(packages, directories, dummyDirectories, files, context = context)
                                        mainHandler.post { Toast.makeText(context, "Vault and files hidden", Toast.LENGTH_SHORT).show() }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Hide Vault", fontWeight = FontWeight.Bold)
                            }

                            FilledTonalButton(
                                onClick = {
                                    saveCurrentVault()
                                    val stateManager = VaultStateManager.getInstance(context)
                                    val packages = settings.targetPackages
                                    val directories = settings.targetDirectories
                                    val dummyDirectories = settings.targetDummyDirectories
                                    val files = settings.targetFiles
                                    val salt = stateManager.keySalt()
                                    Thread {
                                        val ok = StorageMountManager.mountRealContainer(packages, directories, dummyDirectories, files, "", salt, context)
                                        mainHandler.post {
                                            if (ok) {
                                                stateManager.updateState(VaultState.UNLOCKED)
                                                Toast.makeText(context, "Vault and files unhidden", Toast.LENGTH_SHORT).show()
                                            } else {
                                                showPinPromptForVault = true
                                            }
                                        }
                                    }.start()
                                },
                                modifier = Modifier.weight(1f).height(50.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Filled.LockOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Unhide Vault", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 1b. Expandable Quick Guide Card
                Surface(
                    onClick = { showHowItWorks = !showHowItWorks },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("How The Nile Works", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                    Text(if (showHowItWorks) "Tap to hide guide" else "Tap to see how stealth vault protects you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(
                                if (showHowItWorks) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        AnimatedVisibility(visible = showHowItWorks) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                                HelpStepRow(step = "1", title = "Choose what to hide", desc = "Add apps, individual files (photos, documents, videos), or entire folders into this vault below.")
                                HelpStepRow(step = "2", title = "Set your decoy triggers", desc = "Configure Decoy PIN, dial code (*#1234#), or calculator trigger in Settings.")
                                HelpStepRow(step = "3", title = "Stealth in action", desc = "Entering the decoy code secretly switches to a decoy vault or hides all trace under duress.")
                                HelpStepRow(step = "4", title = "Switch back anytime", desc = "Dial *#8888# or type your Master PIN on the lockscreen to return to your main vault.")
                            }
                        }
                    }
                }

                // 2. Vault Identity & Behavior
                SectionHeaderCard(
                    title = "Vault Identity & Behavior",
                    subtitle = "Configure decoy mode, trigger PIN, dial code, and calculator math formula",
                    icon = Icons.Filled.Badge
                ) {
                    OutlinedTextField(
                        value = editingVaultState.name,
                        onValueChange = { newName ->
                            editingVaultState = editingVaultState.copy(name = newName)
                        },
                        label = { Text("Vault Name") },
                        placeholder = { Text("e.g. Work Decoy, Casual Guest, Border Inspection") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "Decoy Action on Trigger",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = editingVaultState.actionType == "switch_user",
                            onClick = {
                                editingVaultState = editingVaultState.copy(actionType = "switch_user")
                            },
                            label = { Text("👤 Switch to Android User") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )

                        FilterChip(
                            selected = editingVaultState.actionType == "hide_inplace",
                            onClick = {
                                editingVaultState = editingVaultState.copy(actionType = "hide_inplace")
                            },
                            label = { Text("🔒 In-Place Hide") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        )
                    }

                    AnimatedVisibility(visible = editingVaultState.actionType == "switch_user") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = if (editingVaultState.targetUserId >= 0) editingVaultState.targetUserId.toString() else "",
                                onValueChange = { input ->
                                    val id = input.filter { it.isDigit() }.toIntOrNull() ?: 10
                                    editingVaultState = editingVaultState.copy(targetUserId = id)
                                },
                                label = { Text("Target Android User ID") },
                                placeholder = { Text("e.g. 10 or 11") },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            FilledTonalButton(
                                onClick = {
                                    Thread {
                                        val newId = createDecoyAndroidUser(editingVaultState.name.ifBlank { "Decoy" })
                                        (context as? android.app.Activity)?.runOnUiThread {
                                            if (newId != null) {
                                                editingVaultState = editingVaultState.copy(targetUserId = newId)
                                                Toast.makeText(context, "Created User $newId successfully", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Failed to create user. Make sure root is enabled.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }.start()
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Create New Android Secondary User")
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        "Secret Triggers for THIS Vault",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    OutlinedTextField(
                        value = editingVaultState.decoyPin,
                        onValueChange = { newDecoyPin ->
                            editingVaultState = editingVaultState.copy(
                                decoyPin = newDecoyPin,
                                hideOnDecoy = newDecoyPin.isNotBlank(),
                                decoyDialerCode = if (editingVaultState.decoyDialerCode.isBlank()) newDecoyPin else editingVaultState.decoyDialerCode
                            )
                        },
                        label = { Text("🔢 Decoy Lockscreen PIN") },
                        placeholder = { Text("e.g. 1234") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    DialCodeTextField(
                        label = "📞 Secret Dialer Code",
                        icon = Icons.Filled.Phone,
                        value = editingVaultState.decoyDialerCode,
                        onValueChange = { newCode ->
                            editingVaultState = editingVaultState.copy(decoyDialerCode = newCode)
                        }
                    )

                    OutlinedTextField(
                        value = editingVaultState.decoyCalculatorExpression,
                        onValueChange = { newExpr ->
                            editingVaultState = editingVaultState.copy(decoyCalculatorExpression = newExpr)
                        },
                        label = { Text("🧮 Calculator Secret Formula") },
                        placeholder = { Text("e.g. 12+34 or 47-87+23") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    PreferenceSwitchRow(
                        title = "Active (Protection Enabled)",
                        subtitle = "When enabled, this vault's triggers switch into this decoy state.",
                        checked = editingVaultState.isActive,
                        onCheckedChange = { checked ->
                            editingVaultState = editingVaultState.copy(isActive = checked)
                        }
                    )
                }

                // 3. Hidden Applications
                SectionHeaderCard(
                    title = "Hidden Applications",
                    subtitle = "${editingVaultState.packages.size} app(s) selected for stealth hiding",
                    icon = Icons.Filled.Apps,
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { isAppPickerOpen = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Choose")
                        }
                    }
                ) {
                    if (editingVaultState.packages.isEmpty()) {
                        Text("No apps selected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        editingVaultState.packages.forEach { pkg ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    IconButton(onClick = { 
                                        editingVaultState = editingVaultState.copy(packages = editingVaultState.packages.filter { p -> p != pkg })
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 3b. Vault Apps — copy-based: data-swap (app stays, data swaps) or uninstall (app removed)
                SectionHeaderCard(
                    title = "Vault Apps",
                    subtitle = "${editingVaultState.hiddenApps.size + editingVaultState.uninstallApps.size} app(s): data-swap or full uninstall",
                    icon = Icons.Filled.Lock,
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { isVaultAppPickerOpen = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Choose")
                        }
                    }
                ) {
                    val vaultApps = editingVaultState.hiddenApps + editingVaultState.uninstallApps
                    if (vaultApps.isEmpty()) {
                        Text("No vault apps. Data-swap shows anodyne data on the decoy PIN; uninstall removes the app entirely.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        fun capture(pkg: String, block: () -> Boolean) {
                            Toast.makeText(context, "Capturing $pkg…", Toast.LENGTH_SHORT).show()
                            Thread {
                                val ok = block()
                                mainHandler.post { Toast.makeText(context, if (ok) "Captured $pkg" else "Capture failed (see logs)", Toast.LENGTH_SHORT).show() }
                            }.start()
                        }
                        vaultApps.forEach { pkg ->
                            val isUninstall = pkg in editingVaultState.uninstallApps
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(pkg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        IconButton(onClick = {
                                            editingVaultState = editingVaultState.copy(
                                                hiddenApps = editingVaultState.hiddenApps.filter { it != pkg },
                                                uninstallApps = editingVaultState.uninstallApps.filter { it != pkg }
                                            )
                                        }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    // Mode toggle
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = !isUninstall,
                                            onClick = {
                                                editingVaultState = editingVaultState.copy(
                                                    hiddenApps = (editingVaultState.hiddenApps + pkg).distinct(),
                                                    uninstallApps = editingVaultState.uninstallApps.filter { it != pkg }
                                                )
                                            },
                                            label = { Text("Data swap") }
                                        )
                                        FilterChip(
                                            selected = isUninstall,
                                            onClick = {
                                                editingVaultState = editingVaultState.copy(
                                                    uninstallApps = (editingVaultState.uninstallApps + pkg).distinct(),
                                                    hiddenApps = editingVaultState.hiddenApps.filter { it != pkg }
                                                )
                                            },
                                            label = { Text("Uninstall") }
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    // Capture buttons. Real PIN = the vault unlock code; decoy PIN = this vault's.
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (isUninstall) {
                                            OutlinedButton(onClick = {
                                                capture(pkg) { com.thenile.vault.root.HiddenAppManager.captureUninstall(context, pkg, settings.codeUnlock) }
                                            }) { Text("Capture & remove") }
                                        } else {
                                            OutlinedButton(onClick = {
                                                capture(pkg) { com.thenile.vault.root.HiddenAppManager.captureDecoy(context, pkg, editingVaultState.decoyPin) }
                                            }) { Text("Capture decoy") }
                                            OutlinedButton(onClick = {
                                                capture(pkg) { com.thenile.vault.root.HiddenAppManager.captureReal(context, pkg, settings.codeUnlock) }
                                            }) { Text("Capture real") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3c. Vault Accounts — a manual admin action (Remove Accounts Now), not a hide/decoy
                // trigger: removal needs an Activity + generally shows the account's own system
                // confirmation, so it can't run silently on the same root-shell path as the rest.
                SectionHeaderCard(
                    title = "Vault Accounts",
                    subtitle = "${editingVaultState.removeAccounts.size} account(s) marked for removal",
                    icon = Icons.Filled.AccountCircle,
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { isAccountPickerOpen = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Choose")
                        }
                    }
                ) {
                    if (editingVaultState.removeAccounts.isEmpty()) {
                        Text(
                            "No accounts selected. Removal runs immediately below (not on a hide/decoy trigger) and may show that account's own \"Remove account?\" confirmation.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        editingVaultState.removeAccounts.forEach { accountName ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(accountName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    IconButton(onClick = {
                                        editingVaultState = editingVaultState.copy(removeAccounts = editingVaultState.removeAccounts.filter { it != accountName })
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = {
                                val am = android.accounts.AccountManager.get(context)
                                val targets = am.accounts.filter { it.name in editingVaultState.removeAccounts }
                                if (targets.isEmpty()) {
                                    Toast.makeText(context, "No matching device accounts found", Toast.LENGTH_SHORT).show()
                                } else {
                                    targets.forEach { account ->
                                        am.removeAccount(account, activity, { future ->
                                            mainHandler.post {
                                                val removed = try { future.result.getBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT) } catch (e: Exception) { false }
                                                Toast.makeText(context, if (removed) "Removed ${account.name}" else "Could not remove ${account.name} (see logs)", Toast.LENGTH_SHORT).show()
                                            }
                                        }, null)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Remove Accounts Now")
                        }
                    }
                }

                // 3d. Restore-on-Unlock Accounts — snapshotted into the hidden volume, removed in
                // decoy/locked state, and brought back (with cached tokens) on real unlock. Applying
                // these reboots the UI layer (framework restart to flush AccountManager's cache).
                SectionHeaderCard(
                    title = "Restore-on-Unlock Accounts",
                    subtitle = "${editingVaultState.restoreAccounts.size} account(s) hidden then restored",
                    icon = Icons.Filled.Restore,
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { isRestoreAccountPickerOpen = true },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Choose")
                        }
                    }
                ) {
                    if (editingVaultState.restoreAccounts.isEmpty()) {
                        Text(
                            "No accounts selected. Chosen accounts vanish in decoy/locked state and come back on real unlock. Capture them first with \"Capture Real\". Note: reveal and hide each trigger a brief framework restart, and expired tokens may still force a re-login.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        editingVaultState.restoreAccounts.forEach { accountName ->
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Icon(Icons.Filled.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(accountName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    IconButton(onClick = {
                                        editingVaultState = editingVaultState.copy(restoreAccounts = editingVaultState.restoreAccounts.filter { it != accountName })
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val snapshotVault = editingVaultState
                                Toast.makeText(context, "Capturing accounts…", Toast.LENGTH_SHORT).show()
                                Thread {
                                    val ok = com.thenile.vault.root.HiddenAppManager.captureAccounts(context, snapshotVault, settings.codeUnlock)
                                    mainHandler.post { Toast.makeText(context, if (ok) "Accounts captured" else "Capture failed (see logs)", Toast.LENGTH_SHORT).show() }
                                }.start()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Capture Accounts Now")
                        }
                    }
                }

                // 4. Hidden Folders & Dummy Mappings
                SectionHeaderCard(
                    title = "Hidden Folders & Dummies",
                    subtitle = "Folder encryption & decoy replacement mappings",
                    icon = Icons.Filled.Folder,
                    iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    // Folders Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { dirPickerLauncher.launch(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Folder")
                        }
                        OutlinedButton(
                            onClick = { showAddDummyDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Filled.FolderSpecial, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Dummy")
                        }
                    }

                    if (editingVaultState.directories.isEmpty() && editingVaultState.dummyDirectories.isEmpty()) {
                        Text("No directories or dummy mappings configured.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Directories List
                    editingVaultState.directories.forEach { dir ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(dir, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                IconButton(onClick = { 
                                    editingVaultState = editingVaultState.copy(directories = editingVaultState.directories.filter { d -> d != dir })
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    // Dummy Mappings List
                    editingVaultState.dummyDirectories.forEach { dummy ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Icon(Icons.Filled.FolderSpecial, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Target: ${dummy.target}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    Text("Dummy: ${dummy.dummy}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { 
                                    editingVaultState = editingVaultState.copy(dummyDirectories = editingVaultState.dummyDirectories.filter { d -> d != dummy })
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                // 4b. Hidden Individual Files
                SectionHeaderCard(
                    title = "Hidden Individual Files",
                    subtitle = "${editingVaultState.files.size} file(s) selected for encryption & stealth",
                    icon = Icons.Filled.InsertDriveFile,
                    iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    trailingContent = {
                        FilledTonalButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Files")
                        }
                    }
                ) {
                    if (editingVaultState.files.isEmpty()) {
                        Text("No individual files selected. Tap 'Add Files' to choose photos, videos, or documents to hide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        editingVaultState.files.forEach { filePath ->
                            val fileName = filePath.substringAfterLast("/")
                            Surface(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(fileName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                        Text(filePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        editingVaultState = editingVaultState.copy(files = editingVaultState.files.filter { f -> f != filePath })
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Save Vault Button
                Button(
                    onClick = { saveCurrentVault() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    )
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isDirty) "Save Vault Changes *" else "Save Vault", fontWeight = FontWeight.Bold)
                }

            } else {
                // =================================================================================
                // SETTINGS TAB
                // =================================================================================

                var adminLockMethod by remember { mutableStateOf(settings.adminLockMethod) }
                var adminCustomPin by remember { mutableStateOf(settings.adminCustomPin) }
                var hideAppIcon by remember { mutableStateOf(settings.hideAppIcon) }
                var enableTile by remember { mutableStateOf(settings.enableTile) }
                var enableDeepLink by remember { mutableStateOf(settings.enableDeepLink) }
                var enableVolumeKeys by remember { mutableStateOf(settings.enableVolumeKeys) }
                var enableCalculatorDecoy by remember { mutableStateOf(settings.enableCalculatorDecoy) }
                var calculatorTriggerExpression by remember { mutableStateOf(settings.calculatorTriggerExpression) }
                var enableFakeCrash by remember { mutableStateOf(settings.enableFakeCrash) }
                var deadManSwitchEnabled by remember { mutableStateOf(settings.deadManSwitchEnabled) }
                var deadManSwitchHoursText by remember { mutableStateOf(settings.deadManSwitchHours.toString()) }
                var deadManSwitchVaultIds by remember { mutableStateOf(settings.deadManSwitchVaultIds) }
                var wrongPinSwitchEnabled by remember { mutableStateOf(settings.wrongPinSwitchEnabled) }
                var wrongPinSwitchLimitText by remember { mutableStateOf(settings.wrongPinSwitchLimit.toString()) }
                var wrongPinSwitchVaultIds by remember { mutableStateOf(settings.wrongPinSwitchVaultIds) }
                var usbSwitchEnabled by remember { mutableStateOf(settings.usbSwitchEnabled) }
                var usbSwitchVaultIds by remember { mutableStateOf(settings.usbSwitchVaultIds) }
                fun minuteToHhMm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
                fun hhMmToMinuteOrNull(s: String): Int? {
                    val parts = s.split(":")
                    val h = parts.getOrNull(0)?.toIntOrNull()
                    val mi = parts.getOrNull(1)?.toIntOrNull()
                    return if (h != null && mi != null && h in 0..23 && mi in 0..59) h * 60 + mi else null
                }
                var scheduledLockEnabled by remember { mutableStateOf(settings.scheduledLockEnabled) }
                var scheduledLockStartText by remember { mutableStateOf(minuteToHhMm(settings.scheduledLockStartMinute)) }
                var scheduledLockEndText by remember { mutableStateOf(minuteToHhMm(settings.scheduledLockEndMinute)) }
                var scheduledLockVaultIds by remember { mutableStateOf(settings.scheduledLockVaultIds) }
                var tamperSwitchEnabled by remember { mutableStateOf(settings.tamperSwitchEnabled) }
                var tamperSwitchVaultIds by remember { mutableStateOf(settings.tamperSwitchVaultIds) }
                var blockScreenshots by remember { mutableStateOf(settings.blockScreenshots) }
                var isAuditLogOpen by remember { mutableStateOf(false) }
                var auditLogEnabled by remember { mutableStateOf(settings.auditLogEnabled) }
                var screenOffSwitchEnabled by remember { mutableStateOf(settings.screenOffSwitchEnabled) }
                var screenOffTimeoutText by remember { mutableStateOf(settings.screenOffTimeoutMinutes.toString()) }
                var screenOffVaultIds by remember { mutableStateOf(settings.screenOffVaultIds) }
                var geofenceSwitchEnabled by remember { mutableStateOf(settings.geofenceSwitchEnabled) }
                var geofenceHasLocation by remember { mutableStateOf(settings.geofenceHasLocation) }
                var geofenceRadiusText by remember { mutableStateOf(settings.geofenceRadiusMeters.toString()) }
                var geofenceVaultIds by remember { mutableStateOf(settings.geofenceVaultIds) }

                var androidUsers by remember { mutableStateOf<List<AndroidUser>>(emptyList()) }
                var showCreateUserDialog by remember { mutableStateOf(false) }
                var newUserNameInput by remember { mutableStateOf("Decoy") }
                var isCreatingUser by remember { mutableStateOf(false) }
                var showManualUserField by remember { mutableStateOf(false) }

                fun refreshUsers() {
                    androidUsers = fetchAndroidUsers()
                    if (decoyUserId < 0) {
                        val firstSecondary = androidUsers.firstOrNull { !it.isOwner }
                        if (firstSecondary != null) {
                            decoyUserId = firstSecondary.id
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    refreshUsers()
                }

                if (showCreateUserDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!isCreatingUser) showCreateUserDialog = false },
                        title = { Text("New Decoy Android User", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    "Choose a name for the new Android user. A new isolated user space will be created on your device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = newUserNameInput,
                                    onValueChange = { newUserNameInput = it },
                                    label = { Text("User Name") },
                                    placeholder = { Text("e.g. Decoy, Guest, Work") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            FilledTonalButton(
                                onClick = {
                                    val nameToCreate = newUserNameInput.trim().ifEmpty { "Decoy" }
                                    isCreatingUser = true
                                    val newId = createDecoyAndroidUser(nameToCreate)
                                    isCreatingUser = false
                                    if (newId != null) {
                                        decoyUserId = newId
                                        refreshUsers()
                                        showCreateUserDialog = false
                                        newUserNameInput = "Decoy"
                                        Toast.makeText(context, "Android user '$nameToCreate' created (User ID: $newId)", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to create Android user. Ensure root access is granted.", Toast.LENGTH_LONG).show()
                                    }
                                },
                                enabled = !isCreatingUser
                            ) {
                                if (isCreatingUser) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Creating...")
                                } else {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Create Vault")
                                }
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showCreateUserDialog = false },
                                enabled = !isCreatingUser
                            ) { Text("Cancel") }
                        }
                    )
                }

                // Settings land on this category menu first instead of dumping every section at
                // once — each category opens into its own focused screen with a back button.
                var selectedCategory by remember { mutableStateOf<String?>(null) }
                BackHandler(enabled = selectedCategory != null) { selectedCategory = null }
                data class SettingsCategory(val name: String, val subtitle: String, val icon: ImageVector)
                val categoryList = listOf(
                    SettingsCategory("Decoy & Stealth", "Lockscreen decoy PIN, switch behavior, one-time unlock", Icons.Filled.ShieldMoon),
                    SettingsCategory("Triggers & Codes", "Dial codes and app launch disguises", Icons.Filled.Dialpad),
                    SettingsCategory("Security & Auth", "Privilege, anti-forensics, admin authentication", Icons.Filled.LockPerson),
                    SettingsCategory("Backup & Data", "Export or restore encrypted vault backups", Icons.Filled.Backup)
                )

                val settingsHaptic = LocalHapticFeedback.current
                AnimatedContent(
                    targetState = selectedCategory == null,
                    transitionSpec = {
                        if (targetState) {
                            (slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { -it / 4 } + fadeIn(tween(220))).togetherWith(
                                slideOutVertically(animationSpec = tween(160)) { it / 4 } + fadeOut(tween(140))
                            )
                        } else {
                            (slideInVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) { it / 4 } + fadeIn(tween(220))).togetherWith(
                                slideOutVertically(animationSpec = tween(160)) { -it / 4 } + fadeOut(tween(140))
                            )
                        }
                    },
                    label = "SettingsMenuOrHeader"
                ) { showingMenu ->
                    if (showingMenu) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            categoryList.forEachIndexed { index, cat ->
                                var visible by remember { mutableStateOf(false) }
                                LaunchedEffect(Unit) {
                                    delay(index * 50L)
                                    visible = true
                                }
                                AnimatedVisibility(
                                    visible = visible,
                                    enter = fadeIn(tween(280)) + slideInVertically(
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                                    ) { it / 3 }
                                ) {
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val isPressed by interactionSource.collectIsPressedAsState()
                                    val cardScale by animateFloatAsState(
                                        targetValue = if (isPressed) 0.96f else 1f,
                                        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                                        label = "cardScale_$index"
                                    )
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .graphicsLayer(scaleX = cardScale, scaleY = cardScale)
                                            .clip(RoundedCornerShape(18.dp))
                                            .clickable(interactionSource = interactionSource, indication = null) {
                                                settingsHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                selectedCategory = cat.name
                                            },
                                        shape = RoundedCornerShape(18.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(cat.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(14.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(cat.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Text(cat.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = {
                                settingsHaptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                selectedCategory = null
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to categories")
                            }
                            Text(selectedCategory ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Crossfade(targetState = selectedCategory, label = "SettingsCategoryContent", animationSpec = tween(260)) { target ->
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // --- Category: Decoy & Stealth ---
                if (target == "Decoy & Stealth") {
                    SectionHeaderCard(
                        title = "Real Lock Screen Decoy",
                        subtitle = "Decoy PIN triggers on the system Android lockscreen",
                        icon = Icons.Filled.ShieldMoon,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Text(
                            "Advanced. Entering the Master Decoy Code or any vault's decoy PIN on the ACTUAL Android lock screen triggers the stealth action.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "off" to ("Off (default)" to "No action on real lock screen."),
                                "fake_wrong_pin" to ("Fake wrong-PIN" to "Shows wrong PIN error, then real PIN unlocks normally."),
                                "one_time_unlock" to ("One-time Unlock" to "Decoy unlocks once, then reverts to wrong PIN."),
                                "switch_user" to ("Switch to Android User" to "Switches to an isolated secondary Android user."),
                            ).forEach { (value, info) ->
                                SelectableOptionCard(
                                    title = info.first,
                                    subtitle = info.second,
                                    selected = decoyLockScreenMode == value,
                                    onClick = { decoyLockScreenMode = value }
                                )
                            }
                        }

                        if (decoyLockScreenMode == "switch_user") {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Decoy User Selection Header & Actions (+ and refresh)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Decoy Android User", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("Choose or add an Android user to switch into", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilledTonalButton(
                                        onClick = { showCreateUserDialog = true },
                                        shape = CircleShape,
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "Add User", modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add", fontWeight = FontWeight.Bold)
                                    }
                                    IconButton(onClick = { refreshUsers() }) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh Users", tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }

                            val secondaryUsers = androidUsers.filter { !it.isOwner }

                            if (secondaryUsers.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    secondaryUsers.forEach { user ->
                                        val isSelected = decoyUserId == user.id
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(16.dp))
                                                .clickable { decoyUserId = user.id },
                                            shape = RoundedCornerShape(16.dp),
                                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { decoyUserId = user.id }
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = user.name,
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                    )
                                                    Text(
                                                        text = "Android User • ID: ${user.id}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (isSelected) {
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                ) {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text("No Decoy User Yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                                Text("Create a decoy Android user to enable stealth user switching.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }

                                        FilledTonalButton(
                                            onClick = { showCreateUserDialog = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(14.dp)
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Create Decoy Vault", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }

                            // Optional Manual ID entry toggle for power users
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { showManualUserField = !showManualUserField },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    if (showManualUserField) "Hide manual User ID input" else "Advanced: Enter custom User ID",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Medium
                                )
                                Icon(
                                    if (showManualUserField) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            AnimatedVisibility(visible = showManualUserField) {
                                OutlinedTextField(
                                    value = if (decoyUserId >= 0) decoyUserId.toString() else "",
                                    onValueChange = { text ->
                                        val n = text.filter { it.isDigit() }.toIntOrNull()
                                        decoyUserId = n ?: -1
                                    },
                                    label = { Text("Manual User ID integer") },
                                    placeholder = { Text("e.g. 10") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            // Vault Switching Triggers Section (Calculator, Dialer, PIN Code)
                            Text(
                                "Vault Switching Triggers",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Configure what actions switch the phone into this decoy vault. Entering master codes while in the decoy vault switches back to main user.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // 1. Lock Screen PIN Trigger
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Pin, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Lock Screen Decoy PIN", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                            Text("Entering this Decoy PIN on Android lock screen switches vault", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = editingVaultState.decoyPin,
                                        onValueChange = { newPin ->
                                            editingVaultState = editingVaultState.copy(decoyPin = newPin, hideOnDecoy = newPin.isNotBlank())
                                        },
                                        label = { Text("Decoy Lock Screen PIN") },
                                        placeholder = { Text("e.g. 1234") },
                                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // 2. Phone Dialer Code Trigger
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Phone Dialer Secret Code", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                            Text("Dialing *#<CODE># in Phone app switches vault", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    DialCodeTextField(
                                        label = "Decoy Switch Dial Code",
                                        icon = Icons.Filled.Dialpad,
                                        value = codeDecoy,
                                        onValueChange = { codeDecoy = it }
                                    )
                                }
                            }

                            // 3. Calculator App Trigger
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = if (enableCalculatorDecoy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Filled.Calculate, contentDescription = null, tint = if (enableCalculatorDecoy) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Calculator Decoy Trigger", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                            Text("Typing formula or PIN in Calculator switches vault", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Switch(
                                            checked = enableCalculatorDecoy,
                                            onCheckedChange = { enableCalculatorDecoy = it }
                                        )
                                    }
                                    AnimatedVisibility(visible = enableCalculatorDecoy) {
                                        OutlinedTextField(
                                            value = calculatorTriggerExpression,
                                            onValueChange = { calculatorTriggerExpression = it },
                                            label = { Text("Calculator Trigger Expression") },
                                            placeholder = { Text("e.g. 1234= or 47-87+23=") },
                                            leadingIcon = { Icon(Icons.Filled.Calculate, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                            Text(
                                "Vault Stealth Options",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            PreferenceSwitchRow(
                                title = "Hide switching animation",
                                subtitle = "Silently switches vaults without showing 'Switching to Decoy...'.",
                                checked = suppressUserSwitchAnimation,
                                onCheckedChange = { suppressUserSwitchAnimation = it }
                            )

                            PreferenceSwitchRow(
                                title = "Hide switcher in Notifications & Quick Settings",
                                subtitle = "Removes user switcher icon from Quick Settings shade.",
                                checked = hideUserSwitcherInQuickSettings,
                                onCheckedChange = { hideUserSwitcherInQuickSettings = it }
                            )

                            PreferenceSwitchRow(
                                title = "Hide \"Users\" in Android Settings",
                                subtitle = "Hides 'Multiple users' section and avatar from Settings app.",
                                checked = hideUserSwitcherInSettings,
                                onCheckedChange = { hideUserSwitcherInSettings = it }
                            )
                        }

                        if (decoyLockScreenMode == "one_time_unlock") {
                            val usedCount = settings.decoyUnlockUsedCount
                            val unlimited = decoyUnlockLimit == 0
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(14.dp)
                                ) {
                                    Text(
                                        if (unlimited) "$usedCount used so far (unlimited)." else "$usedCount of $decoyUnlockLimit used.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (usedCount > 0) {
                                        OutlinedButton(onClick = { settings.rearmDecoyOneTimeUnlock() }, shape = CircleShape) { Text("Re-arm") }
                                    }
                                }
                            }
                            PreferenceSwitchRow(
                                title = "Unlimited uses",
                                subtitle = "Allow decoy unlock indefinitely without reverting.",
                                checked = unlimited,
                                onCheckedChange = { checked -> decoyUnlockLimit = if (checked) 0 else 1 }
                            )
                        }
                    }
                }

                // --- Category: Security & Auth ---
                // (Privilege + Anti-Forensics moved out of "Decoy & Stealth" — they're about the
                // app's overall security/privacy posture, not the decoy-switching mechanics.)
                if (target == "Security & Auth") {
                    SectionHeaderCard(
                        title = "Privilege",
                        subtitle = "What Nile can do on this device without root",
                        icon = Icons.Filled.AdminPanelSettings
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    when (privilegeTier) {
                                        PrivilegeTier.ROOT -> "Root"
                                        PrivilegeTier.SHIZUKU -> "Shizuku"
                                        PrivilegeTier.NONE -> "None"
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    when (privilegeTier) {
                                        PrivilegeTier.ROOT -> "Full stealth: system-wide app hiding, decoy PIN on the real lock screen, encrypted vault mounting."
                                        PrivilegeTier.SHIZUKU -> "User switching works. App hiding (pm hide) may fail on newer Android versions — it needs MANAGE_USERS, which shell doesn't have on Android 14+ (confirmed on API 35). No mount-based vault or real-lockscreen decoy PIN — directories/files are hidden via on-device encryption instead."
                                        PrivilegeTier.NONE -> "No app hiding or user switching. Directories/files are still hidden via on-device encryption."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (privilegeTier != PrivilegeTier.ROOT && PrivilegeManager.isShizukuAvailable() && !PrivilegeManager.isShizukuPermissionGranted()) {
                                    FilledTonalButton(
                                        onClick = { PrivilegeManager.requestShizukuPermission() },
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) { Text("Grant Shizuku access") }
                                }
                                if (privilegeTier != PrivilegeTier.ROOT && !manageStorageGranted) {
                                    FilledTonalButton(
                                        onClick = { PrivilegeManager.requestManageStoragePermission(context) },
                                        modifier = Modifier.padding(top = 4.dp)
                                    ) { Text("Grant All Files Access (needed to hide folders/files)") }
                                }
                            }
                        }

                        if (privilegeTier != PrivilegeTier.ROOT) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Vault storage location", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        softVaultDirUri?.let { "Custom folder: ${Uri.parse(it).lastPathSegment ?: it}" }
                                            ?: "Nile's private storage (default — recommended)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        FilledTonalButton(onClick = { showSoftVaultWarning = true }) { Text("Change") }
                                        if (softVaultDirUri != null) {
                                            TextButton(onClick = {
                                                settings.softVaultDirectoryUri = null
                                                softVaultDirUri = null
                                            }) { Text("Reset to default") }
                                        }
                                    }
                                }
                            }

                            if (showSoftVaultWarning) {
                                AlertDialog(
                                    onDismissRequest = { showSoftVaultWarning = false },
                                    title = { Text("Choose a folder for encrypted files", fontWeight = FontWeight.Bold) },
                                    text = {
                                        Text(
                                            "By default, hidden files are encrypted into Nile's own private storage, which no other app can see. " +
                                                "If you pick a different folder instead, the encrypted blob (not its contents — it's still unreadable " +
                                                "without your PIN) will sit inside a folder that other apps with access to it, ADB, or a device backup " +
                                                "could see and copy. Only choose a folder you trust."
                                        )
                                    },
                                    confirmButton = {
                                        FilledTonalButton(onClick = {
                                            showSoftVaultWarning = false
                                            pickSoftVaultDir.launch(null)
                                        }) { Text("Choose folder") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showSoftVaultWarning = false }) { Text("Cancel") }
                                    }
                                )
                            }
                        }
                    }

                    SectionHeaderCard(
                        title = "Anti-Forensics & Trace Scrubbing",
                        subtitle = "Scrub launch history, recents, MediaStore DBs, and thumbnails",
                        icon = Icons.Filled.CleaningServices,
                        iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            "The Nile actively wipes forensic traces in real-time so no evidence remains of hidden apps or files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        PreferenceSwitchRow(
                            title = "UsageStats & Recents Cloaking",
                            subtitle = "Hooks UsageStatsService and ActivityTaskManager to omit hidden apps from launch history.",
                            icon = Icons.Filled.History,
                            checked = true,
                            onCheckedChange = { }
                        )

                        PreferenceSwitchRow(
                            title = "Notification Cloaking",
                            subtitle = "Silently drops push notifications for hidden packages while locked or in decoy mode.",
                            icon = Icons.Filled.NotificationsOff,
                            checked = true,
                            onCheckedChange = { }
                        )

                        PreferenceSwitchRow(
                            title = "MediaStore & Thumbnail Cleaner",
                            subtitle = "Wipes MediaStore SQLite database rows, gallery caches, and .thumbnails on decoy trigger.",
                            icon = Icons.Filled.HideImage,
                            checked = true,
                            onCheckedChange = { }
                        )

                        var isScrubbing by remember { mutableStateOf(false) }

                        FilledTonalButton(
                            onClick = {
                                isScrubbing = true
                                Thread {
                                    com.thenile.vault.root.TraceCleaner.cleanAllTraces(
                                        packages = settings.targetPackages,
                                        directories = settings.targetDirectories,
                                        files = settings.targetFiles
                                    )
                                    (context as? android.app.Activity)?.runOnUiThread {
                                        isScrubbing = false
                                        Toast.makeText(context, "🧹 All forensic traces and thumbnail caches scrubbed!", Toast.LENGTH_SHORT).show()
                                    }
                                }.start()
                            },
                            enabled = !isScrubbing,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isScrubbing) "Scrubbing Traces..." else "Scrub All Activity Traces Now")
                        }
                    }
                }

                // --- Category: Triggers & Codes ---
                if (target == "Triggers & Codes") {
                    SectionHeaderCard(
                        title = "Secret Dial Codes",
                        subtitle = "Dial *#<CODE># in Phone dialer to trigger actions",
                        icon = Icons.Filled.Dialpad
                    ) {
                        Text(
                            "Enter the numbers to dial in your Phone app. Dialing *#<CODE># in your dialer will immediately trigger the corresponding action:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        DialCodeTextField(
                            label = "Unlock Code",
                            icon = Icons.Filled.LockOpen,
                            value = codeUnlock,
                            onValueChange = { codeUnlock = it }
                        )

                        DialCodeTextField(
                            label = "Lock Code",
                            icon = Icons.Filled.Lock,
                            value = codeLock,
                            onValueChange = { codeLock = it }
                        )

                        DialCodeTextField(
                            label = "Master Decoy Code",
                            icon = Icons.Filled.Shield,
                            value = codeDecoy,
                            onValueChange = { codeDecoy = it }
                        )

                        DialCodeTextField(
                            label = "Admin Code",
                            icon = Icons.Filled.AdminPanelSettings,
                            value = codeAdmin,
                            onValueChange = { codeAdmin = it }
                        )
                    }

                    SectionHeaderCard(
                        title = "App Launch Disguises",
                        subtitle = "Stealth launch disguise options for The Nile",
                        icon = Icons.AutoMirrored.Filled.Launch,
                        iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Fake Crash Disguise",
                            subtitle = "Shows fake crash dialog on app launch (long press 'Close app' to bypass).",
                            icon = Icons.Filled.BugReport,
                            checked = enableFakeCrash,
                            onCheckedChange = { enableFakeCrash = it }
                        )

                        PreferenceSwitchRow(
                            title = "Hide Nile App Icon",
                            subtitle = "Hides launcher icon; access via dialer, tile, or calculator.",
                            icon = Icons.Filled.VisibilityOff,
                            checked = hideAppIcon,
                            onCheckedChange = { hideAppIcon = it }
                        )

                        PreferenceSwitchRow(
                            title = "Quick Settings Tile",
                            subtitle = "Add 1-tap tile to notification shade.",
                            icon = Icons.Filled.DashboardCustomize,
                            checked = enableTile,
                            onCheckedChange = { enableTile = it }
                        )

                        PreferenceSwitchRow(
                            title = "Browser Deep Link (nile://admin)",
                            subtitle = "Open vault by typing nile://admin in any browser.",
                            icon = Icons.Filled.Link,
                            checked = enableDeepLink,
                            onCheckedChange = { enableDeepLink = it }
                        )

                        PreferenceSwitchRow(
                            title = "Volume Down Double-Tap",
                            subtitle = "Double tap Volume Down key to open vault.",
                            icon = Icons.Filled.TouchApp,
                            checked = enableVolumeKeys,
                            onCheckedChange = { enableVolumeKeys = it }
                        )

                        AnimatedVisibility(visible = enableVolumeKeys) {
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Enable Nile Accessibility Service")
                            }
                        }
                    }
                }

                // --- Category: Security & Auth ---
                if (target == "Security & Auth") {
                    SectionHeaderCard(
                        title = "Screen Privacy",
                        subtitle = "Controls whether Nile's own screens can be captured",
                        icon = Icons.Filled.Screenshot,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Block Screenshots & Recording",
                            subtitle = "Applies FLAG_SECURE to the PIN screen and this admin panel, so they can't be screenshotted, screen-recorded, or shown in the recents thumbnail. Turn it off if you need screen sharing or a mirroring tool to see these screens.",
                            icon = Icons.Filled.Screenshot,
                            checked = blockScreenshots,
                            onCheckedChange = { blockScreenshots = it }
                        )
                    }

                    SectionHeaderCard(
                        title = "Audit Log",
                        subtitle = "Encrypted record of unlocks, wrong attempts, and auto-hide triggers",
                        icon = Icons.Filled.History,
                        iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            "See what happened while you were away — every real unlock, wrong device-lock attempt, and automatic vault hide is timestamped and stored encrypted on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PreferenceSwitchRow(
                            title = "Enable Audit Log",
                            subtitle = "Off by default for deniability: while off, nothing is recorded and no log file exists, so there's no on-disk proof that vaults were hidden. Turn on only if the tripwire is worth leaving that trace.",
                            icon = Icons.Filled.History,
                            checked = auditLogEnabled,
                            onCheckedChange = { auditLogEnabled = it }
                        )
                        OutlinedButton(
                            onClick = { isAuditLogOpen = true },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View Audit Log")
                        }
                    }

                    if (isAuditLogOpen) {
                        AuditLogDialog(onDismiss = { isAuditLogOpen = false })
                    }

                    SectionHeaderCard(
                        title = "Dead Man's Switch",
                        subtitle = "Auto-hides selected vaults if the real vault goes unopened too long",
                        icon = Icons.Filled.Timer,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Enable Dead Man's Switch",
                            subtitle = "If you don't unlock the real vault within the window below, the vaults checked here get hidden automatically — same as tapping their Hide Vault action yourself.",
                            icon = Icons.Filled.Timer,
                            checked = deadManSwitchEnabled,
                            onCheckedChange = { deadManSwitchEnabled = it }
                        )
                        if (deadManSwitchEnabled) {
                        OutlinedTextField(
                            value = deadManSwitchHoursText,
                            onValueChange = { deadManSwitchHoursText = it.filter(Char::isDigit) },
                            label = { Text("Hours of inactivity before triggering") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        )
                        Text(
                            "Vaults to hide when triggered:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    deadManSwitchVaultIds = if (v.id in deadManSwitchVaultIds)
                                        deadManSwitchVaultIds - v.id else deadManSwitchVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in deadManSwitchVaultIds,
                                    onCheckedChange = { checked ->
                                        deadManSwitchVaultIds = if (checked) deadManSwitchVaultIds + v.id else deadManSwitchVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Scheduled Lockdown",
                        subtitle = "Auto-hides selected vaults during a daily time window (e.g. overnight)",
                        icon = Icons.Filled.Schedule,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Enable Scheduled Lockdown",
                            subtitle = "Hides the vaults checked below every day between the start and end times (checked every ~15 min, so the trigger can lag by that much).",
                            icon = Icons.Filled.Schedule,
                            checked = scheduledLockEnabled,
                            onCheckedChange = { scheduledLockEnabled = it }
                        )
                        if (scheduledLockEnabled) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = scheduledLockStartText,
                                onValueChange = { scheduledLockStartText = it },
                                label = { Text("Start (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            )
                            OutlinedTextField(
                                value = scheduledLockEndText,
                                onValueChange = { scheduledLockEndText = it },
                                label = { Text("End (HH:mm)") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                        Text(
                            "Vaults to hide during the window:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scheduledLockVaultIds = if (v.id in scheduledLockVaultIds)
                                        scheduledLockVaultIds - v.id else scheduledLockVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in scheduledLockVaultIds,
                                    onCheckedChange = { checked ->
                                        scheduledLockVaultIds = if (checked) scheduledLockVaultIds + v.id else scheduledLockVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Wrong PIN Lockdown",
                        subtitle = "Auto-hides selected vaults after too many wrong tries on the DEVICE lock screen",
                        icon = Icons.Filled.Block,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        val devicePolicyManager = remember { context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager }
                        val adminComponent = remember { android.content.ComponentName(context, com.thenile.vault.receivers.NileDeviceAdminReceiver::class.java) }
                        var isAdminActive by remember(privilegeTick) { mutableStateOf(devicePolicyManager.isAdminActive(adminComponent)) }
                        val requestAdmin = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                            isAdminActive = devicePolicyManager.isAdminActive(adminComponent)
                        }

                        PreferenceSwitchRow(
                            title = "Enable Wrong PIN Lockdown",
                            subtitle = "Counts wrong PIN/pattern/password entries on this device's own lock screen — not Nile's PIN screen. After the limit below, the vaults checked here get hidden automatically. A correct unlock resets the count.",
                            icon = Icons.Filled.Block,
                            checked = wrongPinSwitchEnabled,
                            onCheckedChange = { wrongPinSwitchEnabled = it }
                        )
                        if (wrongPinSwitchEnabled) {
                        if (!isAdminActive) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                        putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                            "Needed so Nile can detect wrong PIN/password attempts on this device's lock screen.")
                                    }
                                    requestAdmin.launch(intent)
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Device Admin (required)")
                            }
                        } else {
                            Text(
                                "Device admin active — watching the lock screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedTextField(
                            value = wrongPinSwitchLimitText,
                            onValueChange = { wrongPinSwitchLimitText = it.filter(Char::isDigit) },
                            label = { Text("Wrong tries before triggering") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        )
                        Text(
                            "Vaults to hide when triggered:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    wrongPinSwitchVaultIds = if (v.id in wrongPinSwitchVaultIds)
                                        wrongPinSwitchVaultIds - v.id else wrongPinSwitchVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in wrongPinSwitchVaultIds,
                                    onCheckedChange = { checked ->
                                        wrongPinSwitchVaultIds = if (checked) wrongPinSwitchVaultIds + v.id else wrongPinSwitchVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Screen-Off Lockdown",
                        subtitle = "Auto-hides selected vaults after the screen stays off a while",
                        icon = Icons.Filled.Bedtime,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Enable Screen-Off Lockdown",
                            subtitle = "Once the screen has been off for the number of minutes below, the vaults checked here get hidden — so an unlocked vault doesn't stay open after you set the phone down. Turning the screen back on before then cancels it.",
                            icon = Icons.Filled.Bedtime,
                            checked = screenOffSwitchEnabled,
                            onCheckedChange = { screenOffSwitchEnabled = it }
                        )
                        if (screenOffSwitchEnabled) {
                        OutlinedTextField(
                            value = screenOffTimeoutText,
                            onValueChange = { screenOffTimeoutText = it.filter(Char::isDigit) },
                            label = { Text("Minutes of screen-off before triggering") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        )
                        Text(
                            "Vaults to hide when triggered:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    screenOffVaultIds = if (v.id in screenOffVaultIds)
                                        screenOffVaultIds - v.id else screenOffVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in screenOffVaultIds,
                                    onCheckedChange = { checked ->
                                        screenOffVaultIds = if (checked) screenOffVaultIds + v.id else screenOffVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Geofence Lockdown",
                        subtitle = "Auto-hides selected vaults when you leave a saved safe zone",
                        icon = Icons.Filled.LocationOn,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        var hasLocationPermission by remember(privilegeTick) {
                            mutableStateOf(
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                        }
                        val requestLocation = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { result -> hasLocationPermission = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true }

                        PreferenceSwitchRow(
                            title = "Enable Geofence Lockdown",
                            subtitle = "Hides the vaults checked below the moment the phone leaves the safe zone you set — e.g. home or work. Uses Android's built-in proximity alerts (no Google Play Services).",
                            icon = Icons.Filled.LocationOn,
                            checked = geofenceSwitchEnabled,
                            onCheckedChange = { geofenceSwitchEnabled = it }
                        )
                        if (geofenceSwitchEnabled) {
                        if (!hasLocationPermission) {
                            OutlinedButton(
                                onClick = {
                                    requestLocation.launch(arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                    ))
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Location Permission")
                            }
                            Text(
                                "Allow all the time (background) for the geofence to work while Nile is closed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val loc = com.thenile.vault.root.GeofenceSwitch.lastKnownLocation(context)
                                    if (loc != null) {
                                        settings.geofenceLatitude = loc.latitude
                                        settings.geofenceLongitude = loc.longitude
                                        settings.geofenceHasLocation = true
                                        geofenceHasLocation = true
                                        Toast.makeText(context, "Safe zone set to current location", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "No location fix yet — open a maps app briefly, then retry", Toast.LENGTH_LONG).show()
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(if (geofenceHasLocation) "Update Safe Zone to Here" else "Set Safe Zone to Here")
                            }
                            Text(
                                if (geofenceHasLocation) "Safe zone is set." else "No safe zone set yet — the geofence won't arm until you set one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (geofenceHasLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = geofenceRadiusText,
                            onValueChange = { geofenceRadiusText = it.filter(Char::isDigit) },
                            label = { Text("Safe zone radius (metres)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        )
                        Text(
                            "Vaults to hide when you leave:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    geofenceVaultIds = if (v.id in geofenceVaultIds)
                                        geofenceVaultIds - v.id else geofenceVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in geofenceVaultIds,
                                    onCheckedChange = { checked ->
                                        geofenceVaultIds = if (checked) geofenceVaultIds + v.id else geofenceVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Device Tamper Lockdown",
                        subtitle = "Auto-hides selected vaults on airplane mode or a SIM swap/removal",
                        icon = Icons.Filled.SimCard,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        var hasPhonePermission by remember(privilegeTick) {
                            mutableStateOf(
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.READ_PHONE_STATE
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                        }
                        val requestPhonePermission = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted -> hasPhonePermission = granted }

                        PreferenceSwitchRow(
                            title = "Enable Device Tamper Lockdown",
                            subtitle = "Hides the vaults checked below the moment airplane mode is switched on, or the SIM card is swapped or pulled out — both common signs the phone has been taken.",
                            icon = Icons.Filled.SimCard,
                            checked = tamperSwitchEnabled,
                            onCheckedChange = { tamperSwitchEnabled = it }
                        )
                        if (tamperSwitchEnabled) {
                        if (!hasPhonePermission) {
                            OutlinedButton(
                                onClick = { requestPhonePermission.launch(android.Manifest.permission.READ_PHONE_STATE) },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.SimCard, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Grant Phone Permission (for SIM detection)")
                            }
                            Text(
                                "Without it, only the airplane-mode trigger works.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Phone permission granted — SIM changes are watched.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            "Vaults to hide when triggered:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    tamperSwitchVaultIds = if (v.id in tamperSwitchVaultIds)
                                        tamperSwitchVaultIds - v.id else tamperSwitchVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in tamperSwitchVaultIds,
                                    onCheckedChange = { checked ->
                                        tamperSwitchVaultIds = if (checked) tamperSwitchVaultIds + v.id else tamperSwitchVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "USB Plugged Lockdown",
                        subtitle = "Auto-hides selected vaults every time the phone is plugged in via USB",
                        icon = Icons.Filled.Usb,
                        iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                        iconContentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        PreferenceSwitchRow(
                            title = "Enable USB Plugged Lockdown",
                            subtitle = "Hides the vaults checked below as soon as a USB cable is plugged in (wireless/AC charging doesn't count).",
                            icon = Icons.Filled.Usb,
                            checked = usbSwitchEnabled,
                            onCheckedChange = { usbSwitchEnabled = it }
                        )
                        if (usbSwitchEnabled) {
                        Text(
                            "Vaults to hide when plugged in:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        vaults.forEach { v ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    usbSwitchVaultIds = if (v.id in usbSwitchVaultIds)
                                        usbSwitchVaultIds - v.id else usbSwitchVaultIds + v.id
                                }
                            ) {
                                Checkbox(
                                    checked = v.id in usbSwitchVaultIds,
                                    onCheckedChange = { checked ->
                                        usbSwitchVaultIds = if (checked) usbSwitchVaultIds + v.id else usbSwitchVaultIds - v.id
                                    }
                                )
                                Text(v.name)
                            }
                        }
                        }
                    }

                    SectionHeaderCard(
                        title = "Admin Authentication",
                        subtitle = "Choose how Vault Admin verifies your identity",
                        icon = Icons.Filled.LockPerson
                    ) {
                        SelectableOptionCard(
                            title = "Device Biometric / Screen Lock",
                            subtitle = "Use fingerprint, face unlock, or device lockscreen PIN.",
                            selected = adminLockMethod == "biometric",
                            onClick = { adminLockMethod = "biometric" }
                        )

                        SelectableOptionCard(
                            title = "Custom Admin Password",
                            subtitle = "Set an independent password (any length, letters allowed — not just a 4-digit PIN) dedicated exclusively to Vault Admin.",
                            selected = adminLockMethod == "custom_pin",
                            onClick = { adminLockMethod = "custom_pin" }
                        )

                        AnimatedVisibility(visible = adminLockMethod == "custom_pin") {
                            var showAdminPassword by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = adminCustomPin,
                                onValueChange = { adminCustomPin = it },
                                label = { Text("Custom Admin Password") },
                                visualTransformation = if (showAdminPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showAdminPassword = !showAdminPassword }) {
                                        Icon(
                                            if (showAdminPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (showAdminPassword) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // --- Category: Backup & Data ---
                if (target == "Backup & Data") {
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
                                    BackupManager.exportBackup(settings.vaults, backupPassword, stream)
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
                                    vaults = settings.vaults
                                    Toast.makeText(context, "Imported $count vaults successfully", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            backupPassword = ""
                        }
                    }

                    if (showExportPasswordDialog) {
                        AlertDialog(
                            onDismissRequest = { showExportPasswordDialog = false; backupPassword = ""; backupPasswordConfirm = ""; backupError = null },
                            title = { Text("Export Encrypted Backup", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Enter a password to encrypt your backup file.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedTextField(
                                        value = backupPassword,
                                        onValueChange = { backupPassword = it; backupError = null },
                                        label = { Text("Password") },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = backupPasswordConfirm,
                                        onValueChange = { backupPasswordConfirm = it; backupError = null },
                                        label = { Text("Confirm Password") },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (backupError != null) {
                                        Text(backupError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            },
                            confirmButton = {
                                FilledTonalButton(onClick = {
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
                            title = { Text("Import Encrypted Backup", fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Enter the password used when exporting this backup.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedTextField(
                                        value = backupPassword,
                                        onValueChange = { backupPassword = it },
                                        label = { Text("Password") },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                FilledTonalButton(onClick = {
                                    showImportPasswordDialog = false
                                    importLauncher.launch(arrayOf("*/*"))
                                }) { Text("Import") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showImportPasswordDialog = false; backupPassword = "" }) { Text("Cancel") }
                            }
                        )
                    }

                    SectionHeaderCard(
                        title = "Backup & Restore",
                        subtitle = "Export or restore encrypted .nile vault backups",
                        icon = Icons.Filled.CloudUpload
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { showExportPasswordDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export")
                            }
                            OutlinedButton(
                                onClick = { showImportPasswordDialog = true },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Import")
                            }
                        }
                    }
                }
                } // Column
                } // Crossfade

                // 6. Save Settings & Apply Button
                Button(
                    onClick = {
                        val updatedVaults = vaults.map { if (it.id == editingVaultState.id) editingVaultState else it }
                        vaults = updatedVaults
                        settings.vaults = updatedVaults
                        settings.codeUnlock = codeUnlock.trim()
                        settings.codeLock = codeLock.trim()
                        settings.codeDecoy = codeDecoy.trim()
                        settings.codeAdmin = codeAdmin.trim()
                        settings.decoyLockScreenMode = decoyLockScreenMode
                        settings.decoyUnlockLimit = decoyUnlockLimit
                        settings.decoyUserId = decoyUserId
                        settings.suppressUserSwitchAnimation = suppressUserSwitchAnimation
                        settings.hideUserSwitcherInQuickSettings = hideUserSwitcherInQuickSettings
                        settings.hideUserSwitcherInSettings = hideUserSwitcherInSettings
                        settings.adminLockMethod = adminLockMethod
                        settings.adminCustomPin = adminCustomPin.trim()
                        settings.hideAppIcon = hideAppIcon
                        settings.enableTile = enableTile
                        settings.enableDeepLink = enableDeepLink
                        settings.enableVolumeKeys = enableVolumeKeys
                        settings.enableCalculatorDecoy = enableCalculatorDecoy
                        settings.calculatorTriggerExpression = calculatorTriggerExpression.trim()
                        settings.enableFakeCrash = enableFakeCrash
                        settings.deadManSwitchEnabled = deadManSwitchEnabled
                        settings.deadManSwitchHours = deadManSwitchHoursText.toIntOrNull()?.coerceAtLeast(1) ?: 72
                        settings.deadManSwitchVaultIds = deadManSwitchVaultIds
                        com.thenile.vault.root.DeadManSwitch.reschedule(context)
                        settings.wrongPinSwitchEnabled = wrongPinSwitchEnabled
                        settings.wrongPinSwitchLimit = wrongPinSwitchLimitText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                        settings.wrongPinSwitchVaultIds = wrongPinSwitchVaultIds
                        settings.usbSwitchEnabled = usbSwitchEnabled
                        settings.usbSwitchVaultIds = usbSwitchVaultIds
                        settings.scheduledLockEnabled = scheduledLockEnabled
                        settings.scheduledLockStartMinute = hhMmToMinuteOrNull(scheduledLockStartText) ?: settings.scheduledLockStartMinute
                        settings.scheduledLockEndMinute = hhMmToMinuteOrNull(scheduledLockEndText) ?: settings.scheduledLockEndMinute
                        settings.scheduledLockVaultIds = scheduledLockVaultIds
                        com.thenile.vault.root.ScheduledLockSwitch.reschedule(context)
                        settings.tamperSwitchEnabled = tamperSwitchEnabled
                        settings.tamperSwitchVaultIds = tamperSwitchVaultIds
                        settings.blockScreenshots = blockScreenshots
                        applySecureFlag(context)
                        settings.auditLogEnabled = auditLogEnabled
                        if (!auditLogEnabled) com.thenile.vault.root.AuditLog.clear(context)
                        settings.screenOffSwitchEnabled = screenOffSwitchEnabled
                        settings.screenOffTimeoutMinutes = screenOffTimeoutText.toIntOrNull()?.coerceAtLeast(1) ?: 5
                        settings.screenOffVaultIds = screenOffVaultIds
                        settings.geofenceSwitchEnabled = geofenceSwitchEnabled
                        settings.geofenceRadiusMeters = geofenceRadiusText.toIntOrNull()?.coerceAtLeast(50) ?: 200
                        settings.geofenceVaultIds = geofenceVaultIds
                        com.thenile.vault.root.GeofenceSwitch.reschedule(context)
                        Toast.makeText(context, "Settings saved successfully", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Settings & Apply", fontWeight = FontWeight.Bold)
                }

                // 7. About & Developer Card
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_nile_river_transparent),
                            contentDescription = "The Nile Logo",
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("The Nile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text("v1.1 \u2022 Stealth Vault Engine", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                shape = CircleShape,
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
                            shape = RoundedCornerShape(16.dp),
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
                            shape = RoundedCornerShape(16.dp),
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

@Composable
fun AuditLogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(com.thenile.vault.root.AuditLog.read(context)) }
    val fmt = remember { java.text.SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Audit Log", fontWeight = FontWeight.Bold) },
        text = {
            if (entries.isEmpty()) {
                Text("No events recorded yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries) { entry ->
                        Column {
                            Text(
                                fmt.format(java.util.Date(entry.timestampMillis)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(entry.event, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                com.thenile.vault.root.AuditLog.clear(context)
                entries = emptyList()
            }) { Text("Clear") }
        }
    )
}

@Composable
fun AccountPickerDialog(
    initialSelection: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val context = LocalContext.current
    // Accounts visible to a third-party app are whatever the account owner/authenticator granted
    // visibility to (Android 8+) — GET_ACCOUNTS alone no longer guarantees seeing e.g. a Google
    // account. Root doesn't help list them either; only the AccountManager API applies here (the
    // actual removal further down needs it too, for the same reason).
    val accounts = remember { android.accounts.AccountManager.get(context).accounts.toList() }
    var selected by remember { mutableStateOf(initialSelection.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Accounts to Remove", fontWeight = FontWeight.Bold) },
        text = {
            if (accounts.isEmpty()) {
                Text("No accounts visible to Nile. Some accounts (e.g. Google) only become visible after you've granted Nile access in Settings > Accounts.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(accounts) { account ->
                        val isChecked = selected.contains(account.name)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selected = if (isChecked) selected - account.name else selected + account.name },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = isChecked, onCheckedChange = { checked -> selected = if (checked) selected + account.name else selected - account.name })
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(account.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(account.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.toList()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
        title = { Text("Select Apps to Hide", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    singleLine = true
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selected = if (isChecked) selected - pkg else selected + pkg
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + pkg else selected - pkg
                                    }
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onConfirm(selected.toList()) }) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
