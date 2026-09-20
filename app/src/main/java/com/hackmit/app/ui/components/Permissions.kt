package com.hackmit.app.ui.components

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

class PermissionState(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberPermissionState(permission: String): PermissionState {
    val context = LocalContext.current
    var granted by remember(permission) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    return PermissionState(granted) { launcher.launch(permission) }
}

@Composable
fun rememberPermissionsState(permissions: Array<String>): PermissionState {
    val context = LocalContext.current
    fun allGranted() = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember(permissions.contentHashCode()) { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = it.values.all { value -> value } && allGranted() }
    return PermissionState(granted) { launcher.launch(permissions) }
}
