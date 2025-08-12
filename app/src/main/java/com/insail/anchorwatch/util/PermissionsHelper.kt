package com.insail.anchorwatch.util

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionsHelper {
    fun hasAll(ctx: Context, perms: List<String>): Boolean = perms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requestIfNeeded(activity: Activity, perms: List<String>, reqCode: Int = 1001) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(activity, missing.toTypedArray(), reqCode)
    }
}