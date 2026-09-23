package com.kxin.classtable.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 当前天气的单一来源。挂在单例上而不是日视图的 ViewModel 上:
 * 切到别的标签页再回来不会重新请求,界面重建也立刻有数据可画。
 *
 * 拉取失败保留上一次的结果 —— 天气是点缀,不弹错、不闪空白。
 */
@Singleton
class WeatherRepository @Inject constructor() {

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()

    private val mutex = Mutex()
    private var lastFetchAt = 0L

    /** 拉取当前天气。[force] 为 false 时,距上次成功拉取不足 [MIN_INTERVAL_MS] 直接复用缓存。 */
    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            val cached = _weather.value
            if (!force && cached != null && System.currentTimeMillis() - lastFetchAt < MIN_INTERVAL_MS) {
                return@withLock
            }
            WeatherClient.current().onSuccess {
                _weather.value = it
                lastFetchAt = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        /** 天气变化慢,半小时一次足够;比日视图自身 30 秒的节拍稀疏得多。 */
        const val MIN_INTERVAL_MS = 30L * 60 * 1000
    }
}
