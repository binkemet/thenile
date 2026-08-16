package com.thenile.vault.ui

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thenile.vault.state.VaultState
import com.thenile.vault.state.VaultStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val secretCode = intent.getStringExtra("SECRET_CODE") ?: ""
        // dm-crypt helper lives in nativeLibraryDir (extracted, executable) and runs as root.
        com.thenile.vault.root.StorageMountManager.dmcryptBin = "${applicationInfo.nativeLibraryDir}/libdmcrypt.so"

        setContent {
            val context = LocalContext.current
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            val colorScheme = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.9f)
                ) {
                    val stateManager = VaultStateManager.getInstance(context)
                    val settings = com.thenile.vault.state.SettingsManager.getInstance(context)
                    val immediate = secretCode == settings.codeLock
                    val decoy = isDecoyCode(secretCode)
                    var working by remember { mutableStateOf(immediate) }
                    val scope = rememberCoroutineScope()

                    // Decoy: cover the unmount/mount/trace-clean work with the system-style "starting"
                    // loading screen (the Material You spinner you see on first unlock after a reboot),
                    // hold a believable minimum, then land on the launcher — no toast (a "Decoy active"
                    // popup would give the game away). Everything else keeps the toast.
                    fun runCode() {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            // Lock down the decoy cover as much as a non-owner app can: hide the bars,
                            // eat Back, swallow touches (the spinner is opaque + full-screen). True
                            // Home/Recents blocking needs device-owner lock-task; startLockTask is a
                            // best-effort that engages only where the platform allows it.
                            if (decoy) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                hideSystemBars(true)              // hide status/nav bars — looks locked
                                runCatching { startLockTask() } // best-effort pin (needs device-owner to truly block Home)
                            }
                            val start = android.os.SystemClock.elapsedRealtime()
                            val msg = handleSuccess(secretCode)
                            if (decoy) {
                                val remaining = 4000L - (android.os.SystemClock.elapsedRealtime() - start)
                                if (remaining > 0) delay(remaining)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    runCatching { stopLockTask() }  // release before leaving to the launcher
                                    hideSystemBars(false)
                                    goHome()
                                }
                            } else {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    android.widget.Toast.makeText(this@PromptActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }
                        }
                    }

                    LaunchedEffect(secretCode) { if (immediate) runCode() }

                    // During the decoy cover, eat the Back gesture so it can't be dismissed.
                    BackHandler(enabled = working && decoy) { }

                    if (working) {
                        if (decoy) {
                            DecoyBootScreen()
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else {
                        PinScreen(
                            enrolled = stateManager.isPinEnrolled(),
                            verify = { stateManager.verifyPin(it) },
                            onEnroll = { stateManager.enrollPin(it) },
                        ) { pin ->
                            lastPin = pin
                            working = true
                            runCode()
                        }
                    }
                }
            }
        }
    }

    /** Runs the state action and returns a short message telling the user what actually happened. */
    private fun handleSuccess(code: String): String {
        val stateManager = VaultStateManager.getInstance(this)
        val settings = com.thenile.vault.state.SettingsManager.getInstance(this)
        val targets = settings.targetPackages
        val dirs = settings.targetDirectories
        val dummyDirs = settings.targetDummyDirectories

        if (isDecoyCode(code)) {
            val ok = com.thenile.vault.root.DecoyAction.run(this, code)
            return if (ok) "Decoy active" else "Decoy state set, but mount failed (see logs)"
        }

        return when (code) {
            settings.codeLock -> {
                stateManager.updateState(VaultState.LOCKED)
                com.thenile.vault.root.StorageMountManager.unmountAndLock(targets, dirs, dummyDirs)
                targets.forEach { pkg ->
                    com.thenile.vault.root.TraceCleaner.cleanTraces(pkg)
                }
                "Locked"
            }
            settings.codeUnlock -> {
                val salt = stateManager.keySalt()
                val ok = com.thenile.vault.root.StorageMountManager.mountRealContainer(targets, dirs, dummyDirs, lastPin, salt)
                // Only claim UNLOCKED if the container actually mounted, so the hook doesn't
                // reveal apps whose data never came online.
                if (ok) {
                    stateManager.updateState(VaultState.UNLOCKED)
                    "Unlocked"
                } else {
                    "Unlock FAILED — container not mounted (see logs)"
                }
            }
            settings.codeAdmin -> {
                val intent = android.content.Intent(this, AdminActivity::class.java)
                startActivity(intent)
                "Admin"
            }
            else -> "Unknown code"
        }
    }

    /** Hide/show the status + nav bars for the decoy cover so it reads as a locked system screen. */
    private fun hideSystemBars(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun isDecoyCode(code: String): Boolean {
        val settings = com.thenile.vault.state.SettingsManager.getInstance(this)
        return code == settings.codeDecoy ||
            settings.profiles.any { it.decoyPin.isNotBlank() && it.decoyPin == code }
    }

    /** Drop to the launcher like a fresh boot — the decoy path uses this instead of a toast. */
    private fun goHome() {
        startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    // Set once the PIN is verified, so the unlock path can derive the LUKS key from it.
    private var lastPin: String = ""
}

/** System-style "starting…" loading screen shown while the decoy state is set up: a themed circular
 *  spinner on the Material You background, standing in for the post-reboot first-unlock loader.
 *  ponytail: plain spinner; swap to M3 expressive LoadingIndicator (morphing shape) once material3
 *  exposes it as stable API in the pinned Compose BOM. */
@Composable
fun DecoyBootScreen() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun PinScreen(
    enrolled: Boolean,
    verify: (String) -> Boolean,
    onEnroll: (String) -> Unit,
    onComplete: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    // Enrollment is confirm-once: a mistyped first PIN would brick the real container forever,
    // so a new PIN must be entered twice. firstEntry holds the pending first entry.
    var firstEntry by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // Shake animation state
    val offsetX = remember { Animatable(0f) }

    val title = when {
        enrolled -> "Enter PIN"
        firstEntry == null -> "Create PIN"
        else -> "Confirm PIN"
    }

    val handleDigit: (String) -> Unit = { digit ->
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (pin.length < 4) {
            pin += digit
            if (pin.length == 4) {
                val entered = pin
                when {
                    enrolled && verify(entered) -> onComplete(entered)
                    !enrolled && firstEntry == null -> { firstEntry = entered; pin = "" } // advance to Confirm
                    !enrolled && entered == firstEntry -> { onEnroll(entered); onComplete(entered) }
                    else -> {
                        // Wrong PIN (enrolled) or confirm mismatch (enrolling): reset and shake.
                        if (!enrolled) firstEntry = null
                        coroutineScope.launch {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            offsetX.animateTo(20f, tween(50))
                            offsetX.animateTo(-20f, tween(50))
                            offsetX.animateTo(20f, tween(50))
                            offsetX.animateTo(-20f, tween(50))
                            offsetX.animateTo(0f, tween(50))
                            pin = ""
                        }
                    }
                }
            }
        }
    }

    val handleDelete: () -> Unit = {
        if (pin.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            pin = pin.dropLast(1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.offset(x = offsetX.value.dp)
        ) {
            for (i in 0 until 4) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        PinPad(onDigit = handleDigit, onDelete = handleDelete)
    }
}

@Composable
fun PinPad(onDigit: (String) -> Unit, onDelete: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "DEL")
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in rows) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                for (key in row) {
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else {
                        PinKey(
                            text = key,
                            onClick = {
                                if (key == "DEL") onDelete() else onDigit(key)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PinKey(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
