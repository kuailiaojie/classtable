package com.kxin.classtable.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kxin.classtable.data.AuthRepository
import com.kxin.classtable.data.AuthSession
import com.kxin.classtable.data.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 登录/注册后同步的结果:非空时账号页应展示结果并返回设置页。 */
data class SyncOutcome(val ok: Boolean, val message: String)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {
    val user: StateFlow<AuthSession?> = authRepository.currentUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _syncOutcome = MutableStateFlow<SyncOutcome?>(null)
    val syncOutcome: StateFlow<SyncOutcome?> = _syncOutcome.asStateFlow()

    fun signIn(email: String, password: String) =
        authAndSync("登录成功") { authRepository.signIn(email, password) }

    fun signUp(email: String, password: String) =
        authAndSync("注册成功") { authRepository.signUp(email, password) }

    /**
     * 认证 → 立即同步(拉取远端课程与配置并合并)→ 同步结束后才返回设置页。
     * 同步失败同样返回设置页(账号页弹 Toast 提示,可在账号页用手动同步重试)。
     */
    private fun authAndSync(okText: String, auth: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _busy.value = true
            val r = auth()
            if (r.isSuccess) {
                _message.value = "$okText,正在同步…"
                val sync = syncRepository.syncNow()
                val outcome = if (sync.isSuccess) {
                    SyncOutcome(true, "同步完成")
                } else {
                    SyncOutcome(false, "同步失败:${sync.exceptionOrNull()?.message ?: "请检查网络"}")
                }
                _message.value = outcome.message
                _syncOutcome.value = outcome
            } else {
                _message.value = r.exceptionOrNull()?.message ?: "登录失败"
            }
            _busy.value = false
        }
    }

    /** 手动同步(账号页,需已登录):同步课程与配置,结果留在本页展示。 */
    fun manualSync() {
        viewModelScope.launch {
            _busy.value = true
            _message.value = "正在同步…"
            val r = syncRepository.syncNow()
            _message.value = r.fold({ "同步完成" }, { "同步失败:${it.message ?: "请检查网络"}" })
            _busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _message.value = null
        }
    }

    fun resetPassword() {
        val email = user.value?.email ?: return
        viewModelScope.launch {
            _busy.value = true
            val r = authRepository.sendPasswordReset(email)
            _message.value = r.fold(
                { "重置邮件已发送到 $email,请查收" },
                { it.message ?: "发送失败" },
            )
            _busy.value = false
        }
    }

    fun consumeSyncOutcome() {
        _syncOutcome.value = null
    }
}
