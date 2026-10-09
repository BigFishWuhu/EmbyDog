package com.embydog.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embydog.app.core.update.GithubUpdateRepository
import com.embydog.app.core.update.UpdateCandidate
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(val checking: Boolean = false, val downloading: Boolean = false,
    val candidate: UpdateCandidate? = null, val downloaded: Long = 0, val total: Long = 0,
    val file: File? = null, val message: String? = null, val error: String? = null)

@HiltViewModel
class UpdateViewModel @Inject constructor(private val repository: GithubUpdateRepository) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    fun check() {
        if (job?.isActive == true) return
        mutable.value = UpdateUiState(checking = true)
        job = viewModelScope.launch {
            try {
                val candidate = repository.check()
                mutable.value = UpdateUiState(candidate = candidate, message = if (candidate == null) "当前已是最新正式版本" else null)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(checking = false, error = message(error)) } }
        }
    }
    fun download() {
        if (job?.isActive == true) return
        val candidate = mutable.value.candidate ?: return
        mutable.update { it.copy(downloading = true, error = null, file = null, downloaded = 0, total = candidate.apk.size) }
        job = viewModelScope.launch {
            try {
                val file = repository.download(candidate) { bytes, total -> mutable.update { it.copy(downloaded = bytes, total = total) } }
                mutable.update { it.copy(downloading = false, file = file, message = "下载及校验完成，可以安装更新") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { mutable.update { it.copy(downloading = false, error = message(error)) } }
        }
    }
    fun cancel() { job?.cancel(); mutable.update { it.copy(checking = false, downloading = false, message = "已取消") } }
    fun installationError() { mutable.update { it.copy(error = "无法打开安装界面，请检查系统安装权限") } }
    private fun message(error: Exception) = when (error) {
        is java.io.IOException -> "无法连接 GitHub，请检查网络后重试"
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "更新检查失败"
        else -> "更新失败，请稍后重试"
    }
}
