package com.talksync.util

// one permission checklist so camera and mic screens dont all make up their own rules.

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.talksync.model.ConversationInputMode

object PermissionUtil {
    val microphoneOnlyPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
    )

    val cameraOnlyPermissions = arrayOf(
        Manifest.permission.CAMERA,
    )

    val conversationPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
    )

    fun permissionsForMode(mode: ConversationInputMode): Array<String> {
        return when (mode) {
            ConversationInputMode.TRANSCRIPTION -> conversationPermissions
            ConversationInputMode.ASL_TRANSLATION -> cameraOnlyPermissions
        }
    }

    fun hasPermissionsForMode(
        context: Context,
        mode: ConversationInputMode,
    ): Boolean {
        return permissionsForMode(mode).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasConversationPermissions(context: Context): Boolean {
        return hasPermissionsForMode(context, ConversationInputMode.TRANSCRIPTION)
    }

    fun hasMicrophonePermission(context: Context): Boolean {
        return microphoneOnlyPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun shouldShowPermissionRationale(
        activity: Activity,
        mode: ConversationInputMode,
    ): Boolean {
        return permissionsForMode(mode).any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
    }

    fun shouldShowConversationPermissionRationale(activity: Activity): Boolean {
        return shouldShowPermissionRationale(activity, ConversationInputMode.TRANSCRIPTION)
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
