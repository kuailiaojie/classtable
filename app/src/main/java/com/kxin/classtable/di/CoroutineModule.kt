package com.kxin.classtable.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 应用级协程作用域。
 *
 * 以前每个仓库各自 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` —— 同一进程里散落七八个
 * 作用域,既看不出「谁在后台跑」,取消/释放也无从统一。现在由 Hilt 提供一个单例作用域,活到进程结束。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
