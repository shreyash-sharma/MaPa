@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

class WorkerParameters
abstract class ListenableWorker(context: Context, params: WorkerParameters) {
    val applicationContext: Context get() = TODO()
    abstract class Result {
        companion object {
            @JvmStatic fun success(): Result = TODO()
            @JvmStatic fun retry(): Result = TODO()
            @JvmStatic fun failure(): Result = TODO()
        }
    }
}
abstract class Worker(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
    abstract fun doWork(): Result
}
enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED }
enum class OutOfQuotaPolicy { RUN_AS_NON_EXPEDITED_WORK_REQUEST, DROP_WORK_REQUEST }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }
enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE, CANCEL_AND_REENQUEUE }
class Constraints {
    class Builder {
        fun setRequiredNetworkType(t: NetworkType): Builder = this
        fun setRequiresCharging(b: Boolean): Builder = this
        fun build(): Constraints = TODO()
    }
}
abstract class WorkRequest
class OneTimeWorkRequest : WorkRequest() {
    class Builder(c: Class<out ListenableWorker>) {
        fun setConstraints(c: Constraints): Builder = this
        fun setExpedited(p: OutOfQuotaPolicy): Builder = this
        fun setInitialDelay(d: Long, u: TimeUnit): Builder = this
        fun build(): OneTimeWorkRequest = TODO()
    }
}
class PeriodicWorkRequest : WorkRequest() {
    class Builder(c: Class<out ListenableWorker>, interval: Long, u: TimeUnit) {
        fun setConstraints(c: Constraints): Builder = this
        fun build(): PeriodicWorkRequest = TODO()
    }
}
inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder = OneTimeWorkRequest.Builder(W::class.java)
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(interval: Long, unit: TimeUnit): PeriodicWorkRequest.Builder =
    PeriodicWorkRequest.Builder(W::class.java, interval, unit)
interface Operation
abstract class WorkManager {
    abstract fun enqueueUniqueWork(name: String, p: ExistingWorkPolicy, r: OneTimeWorkRequest): Operation
    abstract fun enqueueUniquePeriodicWork(name: String, p: ExistingPeriodicWorkPolicy, r: PeriodicWorkRequest): Operation
    abstract fun cancelUniqueWork(name: String): Operation
    companion object { @JvmStatic fun getInstance(ctx: Context): WorkManager = TODO() }
}
