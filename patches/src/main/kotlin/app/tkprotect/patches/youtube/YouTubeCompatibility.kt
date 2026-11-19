package app.tkprotect.patches.youtube

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

internal val COMPATIBILITY_YOUTUBE = Compatibility(
    name = "YouTube",
    packageName = "com.google.android.youtube",
    apkFileType = ApkFileType.APK,
    appIconColor = 0xFF0033,
    targets = listOf(
        AppTarget(version = "21.30.216", versionCode = 1561187872, minSdk = 29),
        AppTarget(version = "21.20.405", versionCode = 1561173354, minSdk = 28),
    ),
)
