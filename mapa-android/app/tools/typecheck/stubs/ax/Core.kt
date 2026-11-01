@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.core.content
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
object ContextCompat {
    const val RECEIVER_NOT_EXPORTED = 4
    @JvmStatic fun registerReceiver(c: Context, r: BroadcastReceiver?, f: IntentFilter, flags: Int): Intent? = TODO()
    @JvmStatic fun checkSelfPermission(c: Context, p: String): Int = TODO()
}
