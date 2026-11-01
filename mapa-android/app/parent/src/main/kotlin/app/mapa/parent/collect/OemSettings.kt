package app.mapa.parent.collect

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Best-effort deep links into OEM "auto-start" / background managers. These screens are
 * undocumented and move between OS versions, so every entry is tried and we fall back to
 * the standard App info page. The test protocol lists the manual menu paths too.
 */
object OemSettings {
    private val CANDIDATES = listOf(
        // Xiaomi / Redmi / POCO (MIUI, HyperOS)
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Realme / Oppo (ColorOS, Realme UI)
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // Vivo / iQOO (Funtouch OS, OriginOS)
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        // Samsung (One UI device care / battery)
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
    )

    fun open(ctx: Context) {
        for (c in CANDIDATES) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // exported=false on this build; try the next one
            }
        }
        openAppInfo(ctx)
    }

    fun openAppInfo(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
