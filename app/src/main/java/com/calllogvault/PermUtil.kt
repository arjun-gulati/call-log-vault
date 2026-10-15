package com.calllogvault

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object PermUtil {
    val RUNTIME_PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_CONTACTS
    )

    fun has(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun hasCallLogRead(context: Context): Boolean = has(context, Manifest.permission.READ_CALL_LOG)

    fun hasAll(context: Context): Boolean = RUNTIME_PERMISSIONS.all { has(context, it) }

    fun missing(context: Context): Array<String> =
        RUNTIME_PERMISSIONS.filter { !has(context, it) }.toTypedArray()
}
