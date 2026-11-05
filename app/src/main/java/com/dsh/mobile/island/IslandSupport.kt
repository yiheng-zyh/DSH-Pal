package com.dsh.mobile.island

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings

/**
 * Detects what island / focus-notification capability this device actually has,
 * using the three official queries from Xiaomi's 超级岛 guide:
 *
 *  1. `persist.sys.feature.island` (via reflection on SystemProperties) tells
 *     us whether the ROM has the island feature at all.
 *  2. `Settings.System.notification_focus_protocol` returns the focus
 *     notification protocol version: 1 = OS1, 2 = OS2, 3 = OS3 Super Island.
 *  3. `content://miui.statusbar.notification.public` / `canShowFocus` tells us
 *     whether *this app* is allowed to post focus notifications.
 *
 * Every probe is best-effort. On a non-Xiaomi device all three fail and the app
 * knowingly degrades to an ordinary progress notification — it never pretends
 * the island exists.
 */
object IslandSupport {

    /** What the device can render. */
    enum class Level {
        /** Not a HyperOS device, or the feature is absent: plain notification only. */
        None,

        /** OS1 focus notification. */
        FocusV1,

        /** OS2 focus notification. */
        FocusV2,

        /** OS3 Super Island. */
        SuperIsland,
    }

    /** Full capability snapshot shown in the app's settings screen. */
    data class Capability(
        val level: Level,
        val protocolVersion: Int,
        val hasFocusPermission: Boolean,
        val isXiaomiDevice: Boolean,
    ) {
        val canShowIsland: Boolean get() = level == Level.SuperIsland && hasFocusPermission
        val canShowFocus: Boolean get() = level != Level.None && hasFocusPermission
    }

    /**
     * Probe the device.
     *
     * Query 3 performs an IPC to SystemUI, so call this off the main thread
     * (the app does it once at startup on a background dispatcher).
     */
    fun probe(context: Context): Capability {
        val isXiaomi = isXiaomiDevice()
        val protocol = readFocusProtocol(context)
        val hasFocus = hasFocusPermission(context)

        val level = when {
            protocol >= 3 -> Level.SuperIsland
            protocol == 2 -> Level.FocusV2
            protocol == 1 -> Level.FocusV1
            // A Xiaomi ROM that reports the island system property but not the
            // protocol counter still gets the island payload sent: worst case it
            // renders as a plain notification.
            isXiaomi && readIslandProperty() -> Level.SuperIsland
            else -> Level.None
        }

        return Capability(
            level = level,
            protocolVersion = protocol,
            hasFocusPermission = hasFocus,
            isXiaomiDevice = isXiaomi,
        )
    }

    /** `persist.sys.feature.island` via SystemProperties reflection. */
    fun readIslandProperty(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
        (getBoolean.invoke(null, "persist.sys.feature.island", false) as? Boolean) ?: false
    } catch (_: Throwable) {
        false
    }

    /**
     * `Settings.System.notification_focus_protocol`:
     * 0 = unsupported, 1 = OS1, 2 = OS2, 3 = OS3 Super Island.
     */
    fun readFocusProtocol(context: Context): Int = try {
        Settings.System.getInt(
            context.contentResolver,
            "notification_focus_protocol",
            0,
        )
    } catch (_: Throwable) {
        0
    }

    /**
     * Ask SystemUI whether this package may post focus notifications.
     * Returns false on any non-Xiaomi ROM (the provider is absent).
     */
    fun hasFocusPermission(context: Context): Boolean = try {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = context.contentResolver.call(uri, "canShowFocus", null, extras)
        result?.getBoolean("canShowFocus", false) ?: false
    } catch (_: Throwable) {
        false
    }

    /** Xiaomi / Redmi / POCO all report a `Xiaomi` brand. */
    fun isXiaomiDevice(): Boolean = Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true) ||
        Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * Deep link into the system's focus-notification permission page, so the
     * app can offer a one-tap fix instead of telling the user to hunt for it.
     */
    fun focusPermissionSettingsIntent(context: Context): android.content.Intent? {
        val pkg = context.packageName
        val candidates = listOf(
            "miui.intent.action.OP_FOCUS_NOTIFICATION",
            "miui.intent.action.FOCUS_NOTIFICATION_SETTINGS",
        )
        for (action in candidates) {
            val intent = android.content.Intent(action).apply { putExtra("package", pkg) }
            if (intent.resolveActivity(context.packageManager) != null) return intent
        }
        // Fall back to this app's own notification settings.
        return android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        }
    }
}
