package app.mapa.shared

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

/**
 * Connects to Firebase. With a google-services.json in the app module the real project is
 * used. Without one (the default until a project is created) the app talks to the local
 * Firebase Emulator Suite under the demo project id "demo-mapa": no account, no cost.
 */
object Backend {
    const val DEMO_PROJECT = "demo-mapa"
    private const val TAG = "MaPaBackend"

    @Volatile
    var usingEmulator = false
        private set
    private var initialized = false

    /** Where the Emulator Suite runs, as seen from this phone. */
    val emulatorHost: String get() = BuildConfig.EMULATOR_HOST

    @Synchronized
    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        val emulator = FirebaseApp.getApps(ctx).isEmpty()
        if (emulator) {
            val options = FirebaseOptions.Builder()
                .setProjectId(DEMO_PROJECT)
                .setApplicationId("1:000000000000:android:0000000000000000")
                // Never sent anywhere real: the emulators accept any key.
                .setApiKey("AIzaDemoKeyForLocalEmulatorOnly000000000")
                .build()
            FirebaseApp.initializeApp(ctx, options)
        }
        // Offline cache: writes made while the phone has no network are sent when it reconnects.
        // Freshness is judged from the phone's own computedAt, so a late write never looks current.
        FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        if (emulator) {
            val host = BuildConfig.EMULATOR_HOST
            FirebaseAuth.getInstance().useEmulator(host, 9099)
            FirebaseFirestore.getInstance().useEmulator(host, 8080)
            usingEmulator = true
            Log.i(TAG, "No google-services.json: using the Firebase Emulator Suite at $host (project $DEMO_PROJECT)")
        }
    }
}
