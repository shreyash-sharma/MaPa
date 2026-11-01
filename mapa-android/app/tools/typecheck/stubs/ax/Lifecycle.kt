@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle
abstract class Lifecycle { enum class Event { ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY } }
