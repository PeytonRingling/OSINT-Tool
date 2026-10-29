package vip.ringling.recon.data.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import vip.ringling.recon.BuildConfig
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val build: Int) : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: UpdateRepository,
    private val installer: ApkInstaller,
    private val keys: ApiKeys,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val installedBuild: Int get() = BuildConfig.VERSION_CODE
    private var job: Job? = null
    private val apkFile get() = File(context.cacheDir, "updates/Recon.apk")

    init {
        scope.launch {
            InstallEvents.results.collect { r ->
                _state.value = if (r.success) UpdateState.Idle else UpdateState.Failed(r.message)
                if (!r.success) apkFile.delete()
            }
        }
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            _state.value = try {
                val info = repo.latest(keys.get(ApiKeyId.GITHUB))
                if (UpdateRepository.isNewer(info.build, installedBuild)) UpdateState.Available(info) else UpdateState.UpToDate(installedBuild)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "Couldn't check for updates")
            }
        }
    }

    /** Check without surfacing errors — used once at launch when a token is configured. */
    fun checkQuietly() {
        // Token is optional: a public repo needs none, and a private one just fails quietly here.
        if (_state.value !is UpdateState.Idle) return
        scope.launch {
            _state.value = try {
                val info = repo.latest(keys.get(ApiKeyId.GITHUB))
                if (UpdateRepository.isNewer(info.build, installedBuild)) UpdateState.Available(info) else UpdateState.UpToDate(installedBuild)
            } catch (e: Exception) {
                UpdateState.Idle
            }
        }
    }

    fun downloadAndInstall() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.NeedsPermission -> s.info
            else -> return
        }
        if (!installer.canInstall()) {
            _state.value = UpdateState.NeedsPermission(info)
            installer.openInstallPermissionSettings()
            return
        }
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val file = repo.download(info, keys.get(ApiKeyId.GITHUB), apkFile) { p -> _state.value = UpdateState.Downloading(info, p) }
                SigningCheck.requireSameSigner(context, file)
                _state.value = UpdateState.Installing(info)
                installer.install(file)
            } catch (e: Exception) {
                apkFile.delete()
                _state.value = UpdateState.Failed(e.message ?: "Update failed")
            }
        }
    }

    fun dismissError() { if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle }
}
