@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result.contract

abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
