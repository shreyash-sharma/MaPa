@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle.compose
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
@Composable fun LifecycleEventEffect(event: Lifecycle.Event, onEvent: () -> Unit) {}
