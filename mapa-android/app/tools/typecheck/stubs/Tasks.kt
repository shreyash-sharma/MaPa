@file:Suppress("unused", "UNUSED_PARAMETER")
package com.google.android.gms.tasks

import java.util.concurrent.TimeUnit

fun interface OnSuccessListener<T> { fun onSuccess(result: T) }
abstract class Task<T> {
    abstract fun addOnSuccessListener(l: OnSuccessListener<in T>): Task<T>
    abstract val result: T?
}
object Tasks {
    @JvmStatic fun <T> await(task: Task<T>, timeout: Long, unit: TimeUnit): T = TODO()
    @JvmStatic fun <T> await(task: Task<T>): T = TODO()
}
