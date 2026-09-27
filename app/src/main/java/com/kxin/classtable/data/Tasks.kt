package com.kxin.classtable.data

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 无额外依赖地把 Play services Task 转成挂起函数(App Check / Remote Config 共用)。 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isSuccessful -> cont.resume(task.result)
            else -> cont.resumeWithException(IllegalStateException("task failed"))
        }
    }
}
