// ContainerManageViewModel.kt -- This file is part of tiny_container.
//
// Copyright (C) 2026 Caten Hu
//
// Tiny Container is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published
// by the Free Software Foundation, either version 3 of the License,
// or any later version.
//
// Tiny Container is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see http://www.gnu.org/licenses/.

package com.fct.tc4.ui.page

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fct.tc4.R
import com.fct.tc4.ui.misc.ConfigManager
import com.fct.tc4.ui.misc.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.pow
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.RandomAccessFile
import HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.resume

// ====== 页面级别状态 ======
sealed class ContainerManagePageState {
    data object Loading : ContainerManagePageState()
    data object Idle : ContainerManagePageState()
    data class Error(val message: String) : ContainerManagePageState()
}

// ====== 单个容器状态 ======
sealed class ContainerItem {
    abstract val code: String

    data class Loaded(
        override val code: String,
        val name: String = "",
        val description: String = "",
        val image: String = "",
        val spaceBytes: Long? = null
    ) : ContainerItem()

    data class Error(
        override val code: String,
        val message: String
    ) : ContainerItem()
}

// ====== 删除流程 ======

/** 可恢复的删除状态 */
sealed class DeleteState {
    data object InProgress : DeleteState()
    data object Completed : DeleteState()
    data class Failed(val message: String) : DeleteState()
}

// ====== 安装流程 ======

enum class InstallStep { DELETING_OLD, EXTRACTING_ROOTFS, CLEANING_CACHE }

sealed class InstallState {
    data object Idle : InstallState()
    data object ImportWarn : InstallState()
    data object CopyingToCache : InstallState()
    /** 二改版新增：从网络下载 rootfs（APK 瘦身） */
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : InstallState()
    data object ExtractingConfig : InstallState()
    data class AwaitingConfirm(
        val rawConfig: Map<String, Any>,
        val containerSizeBytes: Long
    ) : InstallState()
    data class Installing(
        val code: String,
        val currentStep: InstallStep,
        val startTimeMillis: Long,
        val containerSizeBytes: Long
    ) : InstallState()
    data class Completed(
        val launchAfterInstall: Boolean,
        val code: String
    ) : InstallState()
    data class Failed(val message: String) : InstallState()
}

// ====== 导出流程 ======

sealed class ExportState {
    data class PendingPick(val code: String, val displayName: String) : ExportState()
    data class InProgress(val startTimeMillis: Long, val containerSizeBytes: Long) : ExportState()
    data object Completed : ExportState()
    data class Failed(val message: String) : ExportState()
}

class ContainerManageViewModel(application: Application) : AndroidViewModel(application) {

    // ====== 页面状态 ======
    private val _pageState = MutableStateFlow<ContainerManagePageState>(ContainerManagePageState.Loading)
    val pageState: StateFlow<ContainerManagePageState> = _pageState.asStateFlow()

    private val _containers = MutableStateFlow<List<ContainerItem>>(emptyList())
    val containers: StateFlow<List<ContainerItem>> = _containers.asStateFlow()

    private val _selectedPosition = MutableStateFlow(0)
    val selectedPosition: StateFlow<Int> = _selectedPosition.asStateFlow()

    fun selectPosition(position: Int) {
        _selectedPosition.value = position
    }

    // ====== 删除流程 ======
    private val _pendingDeleteConfirm = MutableStateFlow<Pair<String, String>?>(null)
    val pendingDeleteConfirm: StateFlow<Pair<String, String>?> = _pendingDeleteConfirm.asStateFlow()

    private val _deleteState = MutableStateFlow<DeleteState?>(null)
    val deleteState: StateFlow<DeleteState?> = _deleteState.asStateFlow()

    // ====== 安装流程 ======
    private val _installState = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _installState.asStateFlow()

    /** 确认安装对话框中用户正在编辑的 code（用于旋转恢复） */
    private val _installDialogCode = MutableStateFlow("")
    val installDialogCode: StateFlow<String> = _installDialogCode.asStateFlow()

    /** 确认安装对话框中"安装后启动"开关状态（用于旋转恢复） */
    private val _installDialogLaunch = MutableStateFlow(false)
    val installDialogLaunch: StateFlow<Boolean> = _installDialogLaunch.asStateFlow()

    fun updateInstallDialogCode(code: String) {
        _installDialogCode.value = code
    }

    fun updateInstallDialogLaunch(launch: Boolean) {
        _installDialogLaunch.value = launch
    }

    fun resetInstallDialogState() {
        _installDialogCode.value = ""
        _installDialogLaunch.value = false
    }

    init {
        loadContainers()
    }

    fun loadContainers() {
        viewModelScope.launch(Dispatchers.IO) {
            _pageState.value = ContainerManagePageState.Loading
            try {
                val codes = Global.installedContainers.toList()
                if (codes.isEmpty()) {
                    _containers.value = emptyList()
                    _pageState.value = ContainerManagePageState.Idle
                    return@launch
                }
                val list = codes.map { code ->
                    try {
                        val config = ConfigManager.load(code)
                        if (config != null) {
                            ContainerItem.Loaded(
                                code = code,
                                name = config["name"] as? String ?: "",
                                description = config["description"] as? String ?: "",
                                image = config["preview"] as? String ?: ""
                            )
                        } else {
                            ContainerItem.Loaded(code = code)
                        }
                    } catch (e: Exception) {
                        ContainerItem.Error(code = code, message = getApplication<Application>().getString(R.string.tc4_container_loading_failed, code))
                    }
                }
                _containers.value = list
                _pageState.value = ContainerManagePageState.Idle

                val dataDir = getApplication<Application>().dataDir
                list.forEach { item ->
                    if (item is ContainerItem.Loaded) {
                        val dir = File(dataDir, item.code)
                        updateSpace(item.code, calculateContainerSize(dir))
                    }
                }
            } catch (e: Exception) {
                _pageState.value = ContainerManagePageState.Error(
                    getApplication<Application>().getString(R.string.tc4_container_list_load_failed, e.message ?: ""))
            }
        }
    }

    private fun calculateContainerSize(dir: File): Long {
        var bytes = 0L
        if (dir.exists()) {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    return if (attrs.isSymbolicLink) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) bytes += attrs.size()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, exc: java.io.IOException?) = FileVisitResult.CONTINUE // 忽略报错，继续执行
            })
        }
        return bytes
    }

    fun fakeProgress(startTimeMillis: Long, containerSizeBytes: Long): Float {
        val elapsedMin = (System.currentTimeMillis() - startTimeMillis) / 60000f
        val containerSizeGB = containerSizeBytes / 1_000_000_000f
        return 1f - 10f.pow(-elapsedMin / 2f / containerSizeGB)
    }

    fun updateSpace(code: String, bytes: Long) {
        _containers.value = _containers.value.map {
            if (it.code == code && it is ContainerItem.Loaded) it.copy(spaceBytes = bytes) else it
        }
    }

    // ======================== 编辑容器信息 ========================

    fun updateContainerConfig(code: String, name: String, description: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val config = ConfigManager.load(code) ?: return@launch
            val updated = config.toMutableMap().apply {
                put("name", name)
                put("description", description)
            }
            ConfigManager.save(code, updated)
            _containers.value = _containers.value.map {
                if (it.code == code && it is ContainerItem.Loaded) {
                    it.copy(name = name, description = description)
                } else it
            }
        }
    }

    // ======================== 删除流程 ========================

    fun requestDelete(code: String, displayName: String) {
        _pendingDeleteConfirm.value = code to displayName
    }

    fun confirmDelete(code: String) {
        _pendingDeleteConfirm.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _deleteState.value = DeleteState.InProgress
            try {
                val dir = File(getApplication<Application>().dataDir, code)
                if (dir.exists()) dir.deleteRecursively()
                if (code == Global.autoLaunch) {
                    Global.autoLaunch = ""
                }
                Global.installedContainers -= code
                _containers.value = _containers.value.filter { it.code != code }
                _deleteState.value = DeleteState.Completed
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Failed(
                    getApplication<Application>().getString(R.string.tc4_container_delete_failed, e.message ?: ""))
            }
        }
    }

    fun cancelDelete() {
        _pendingDeleteConfirm.value = null
    }

    fun resetDeleteState() {
        _deleteState.value = null
    }

    // ======================== 导出流程 ========================

    private val _exportState = MutableStateFlow<ExportState?>(null)
    val exportState: StateFlow<ExportState?> = _exportState.asStateFlow()

    fun requestExport(code: String, displayName: String) {
        _exportState.value = ExportState.PendingPick(code, displayName)
    }

    fun exportContainer(code: String, uri: Uri) {
        val sizeBytes = _containers.value
            .find { it.code == code }
            ?.let { (it as? ContainerItem.Loaded)?.spaceBytes }
            ?: 1_000_000_000L
        _exportState.value = ExportState.InProgress(
            startTimeMillis = System.currentTimeMillis(),
            containerSizeBytes = sizeBytes
        )
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val config = ConfigManager.load(code)
                    ?: throw Exception(app.getString(R.string.tc4_container_config_missing))
                val exportCmd = config["export_command"] as? String
                    ?: throw Exception(app.getString(R.string.tc4_export_missing_cmd))

                execShell {
                    Global.setupEnvironment()
                    Global.sendCommand("export CONTAINER_DIR=${app.dataDir.absolutePath}/$code")
                    Global.sendCommand("mkdir -p ${app.filesDir}/public")
                    Global.sendCommand(exportCmd)
                    Global.sendCommand("exit")
                }


                val outputFile = File(app.filesDir, "public/rootfs.tar.zst")
                if (!outputFile.exists()) throw Exception(app.getString(R.string.tc4_export_no_output))

                app.contentResolver.openOutputStream(uri)?.use { out ->
                    outputFile.inputStream().use { input -> input.copyTo(out, bufferSize = 8192) }
                } ?: throw Exception(app.getString(R.string.tc4_export_cannot_write))

                outputFile.delete()
                _exportState.value = ExportState.Completed
            } catch (e: Exception) {
                _exportState.value = ExportState.Failed(
                    app.getString(R.string.tc4_export_failed, e.message ?: ""))
            }
        }
    }

    fun resetExportState() {
        _exportState.value = null
    }

    // ======================== 安装流程 ========================

    /** 用户通过菜单/按钮发起导入（弹确认对话框） */
    fun startImport() {
        _installState.value = InstallState.ImportWarn
    }

    /** 用户通过 SAF 选完文件后调用 */
    fun onFileSelected(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _installState.value = InstallState.CopyingToCache
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.filesDir, "rootfs.tar.zst")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                } ?: throw IllegalStateException(app.getString(R.string.tc4_validate_clipboard_read))

                processCachedRootfs()
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_failed, e.message ?: ""))
            }
        }
    }

    /** 从 assets 内置 rootfs.tar.zst 开始导入（用户手动点"安装内置容器"触发） */
    fun startBuiltInImport() {
        viewModelScope.launch(Dispatchers.IO) {
            _installState.value = InstallState.CopyingToCache
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.filesDir, "rootfs.tar.zst")
                app.assets.open("rootfs.tar.zst").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }

                processCachedRootfs()
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_builtin_failed, e.message ?: ""))
            }
        }
    }

    /**
     * 二改版新增：从网络下载 rootfs.tar.zst（APK 瘦身）。
     * 支持断点续传（Range），下载完成后走与内置导入相同的 processCachedRootfs() 流程。
     * 界面复用现有导入进度对话框，不新增界面。
     */
    fun startDownloadImport(url: String = Global.ROOTFS_DOWNLOAD_URL) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val cacheFile = File(app.filesDir, "rootfs.tar.zst")
            try {
                var downloaded = if (cacheFile.exists()) cacheFile.length() else 0L
                var total = -1L

                // 先 HEAD 拿总大小（拿不到也不致命）
                try {
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 15000
                        readTimeout = 15000
                        connect()
                        total = contentLengthLong.takeIf { it > 0 } ?: -1L
                        disconnect()
                    }
                } catch (_: Exception) { }

                // 若本地已有完整文件，直接走导入流程
                if (total > 0 && downloaded >= total) {
                    processCachedRootfs()
                    return@launch
                }

                _installState.value = InstallState.Downloading(downloaded, total)
                var lastReport = System.currentTimeMillis()

                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30000
                    readTimeout = 30000
                    // 断点续传
                    if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
                    connect()

                    val code = responseCode
                    if (code != HttpURLConnection.HTTP_OK &&
                        code != HttpURLConnection.HTTP_PARTIAL) {
                        throw IllegalStateException("HTTP $code")
                    }
                    // 服务器不支持 Range 时从头下
                    if (code == HttpURLConnection.HTTP_OK && downloaded > 0) {
                        downloaded = 0
                        cacheFile.delete()
                    }
                    if (total <= 0) {
                        total = contentLengthLong.takeIf { it > 0 }?.let { it + downloaded } ?: -1L
                    }

                    inputStream.use { input ->
                        RandomAccessFile(cacheFile, "rw").use { raf ->
                            raf.seek(downloaded)
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 500) {
                                    lastReport = now
                                    _installState.value = InstallState.Downloading(downloaded, total)
                                }
                            }
                        }
                    }
                }

                _installState.value = InstallState.Downloading(downloaded, total)
                processCachedRootfs()
            } catch (e: Exception) {
                // 保留已下载的部分，下次可续传；只在用户取消/彻底失败时由 cleanCacheFiles 清理
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_download_failed, e.message ?: ""))
            }
        }
    }

    /** 初次启动自动安装内置容器，跳过所有用户确认步骤 */
    fun autoInstallBuiltInContainer() {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            try {
                val cacheFile = File(app.filesDir, "rootfs.tar.zst")

                // 从 assets 复制到缓存
                _installState.value = InstallState.CopyingToCache
                app.assets.open("rootfs.tar.zst").use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }

                // 提取并解析配置
                val config = extractAndParseConfig() ?: return@launch
                val code = config["code"] as? String ?: ""
                if (code.isBlank()) {
                    cleanCacheFiles()
                    _installState.value = InstallState.Failed(
                        app.getString(R.string.tc4_import_missing_code_builtin))
                    return@launch
                }

                // 直接安装
                performInstall(code, config)

                Global.autoLaunch = code
                Global.isFirstLaunchDone = true

                _installState.value = InstallState.Completed(
                    launchAfterInstall = true,
                    code = code
                )
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_auto_failed, e.message ?: ""))
            }
        }
    }

    /** 用户确认安装 */
    fun confirmInstall(code: String, launchAfterInstall: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val currentState = _installState.value
            val rawConfig = (currentState as? InstallState.AwaitingConfirm)?.rawConfig ?: run {
                _installState.value = InstallState.Failed(app.getString(R.string.tc4_install_state_error))
                return@launch
            }
            try {
                performInstall(code, rawConfig)
                _installState.value = InstallState.Completed(
                    launchAfterInstall = launchAfterInstall,
                    code = code
                )
            } catch (e: Exception) {
                cleanCacheFiles()
                _installState.value = InstallState.Failed(
                    app.getString(R.string.tc4_import_failed, e.message ?: ""))
            }
        }
    }

    /** 从已复制到 cache 的 rootfs.tar.zst 中提取配置，进入等待确认状态 */
    private suspend fun processCachedRootfs() {
        val config = extractAndParseConfig() ?: return
        val cacheFile = File(getApplication<Application>().filesDir, "rootfs.tar.zst")
        val code = config["code"] as? String ?: ""
        if (code.isBlank()) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(
                getApplication<Application>().getString(R.string.tc4_import_missing_code))
            return
        }
        _installState.value = InstallState.AwaitingConfirm(
            rawConfig = config,
            containerSizeBytes = cacheFile.length()
        )
    }

    /** 从缓存 rootfs.tar.zst 中提取 .tiny.yaml 并解析，失败时已设置 Failed 状态并返回 null */
    private suspend fun extractAndParseConfig(): Map<String, Any>? {
        val app = getApplication<Application>()

        _installState.value = InstallState.ExtractingConfig
        execShell {
            Global.setupEnvironment()
            Global.sendCommand($$"tar -xf $CACHE_DIR/rootfs.tar.zst -C $CACHE_DIR .tiny.yaml")
            Global.sendCommand("exit")
        }

        val configFile = File(app.filesDir, ".tiny.yaml")
        if (!configFile.exists()) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(app.getString(R.string.tc4_import_no_config))
            return null
        }
        val config = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load<Map<String, Any>>(configFile.readText())
        } catch (e: Exception) {
            cleanCacheFiles()
            _installState.value = InstallState.Failed(
                app.getString(R.string.tc4_import_config_parse_failed, e.message ?: ""))
            return null
        }
        return config
    }

    /** 执行实际的容器安装操作（解压 rootfs、修复权限、保存配置），调用方负责状态管理 */
    private suspend fun performInstall(code: String, rawConfig: Map<String, Any>) {
        val app = getApplication<Application>()
        val cacheFile = File(app.filesDir, "rootfs.tar.zst")

        _installState.value = InstallState.Installing(
            code = code,
            currentStep = InstallStep.DELETING_OLD,
            startTimeMillis = System.currentTimeMillis(),
            containerSizeBytes = cacheFile.length()
        )

        // DELETING_OLD
        val dir = File(app.dataDir, code)
        if (dir.exists()) dir.deleteRecursively()
        updateCurrentStep(InstallStep.EXTRACTING_ROOTFS)

        // EXTRACTING_ROOTFS
        dir.mkdirs()
        execShell {
            val install = $$"""
                export CONTAINER_DIR=$${app.dataDir}/$$code
                mkdir -p $CONTAINER_DIR
                $BIN_DIR/proot --link2symlink $BIN_DIR/tar -xf $CACHE_DIR/rootfs.tar.zst -C $CONTAINER_DIR --delay-directory-restore --preserve-permissions
            """.trimIndent()
            val androidUidGidThings = $$"""
                $BIN_DIR/sed -i '/^aid_/d' $CONTAINER_DIR/etc/passwd
                $BIN_DIR/sed -i '/^aid_/d' $CONTAINER_DIR/etc/shadow
                $BIN_DIR/sed -i '/^aid_/d' $CONTAINER_DIR/etc/group
                $BIN_DIR/sed -i '/^aid_/d' $CONTAINER_DIR/etc/gshadow
                # 来自 proot-distro 的神秘代码
                # https://github.com/termux/proot-distro/blob/eb45040a5c751ca94058bec2f0aef6673707c2cb/proot-distro.sh#L613
                chmod u+rw "$CONTAINER_DIR/etc/passwd" "$CONTAINER_DIR/etc/shadow" "$CONTAINER_DIR/etc/group" "$CONTAINER_DIR/etc/gshadow"
                echo "aid_$(id -un):x:$(id -u):$(id -g):Tiny:/:/sbin/nologin" >> "$CONTAINER_DIR/etc/passwd"
                echo "aid_$(id -un):*:18446:0:99999:7:::" >> "$CONTAINER_DIR/etc/shadow"
                id -Gn | tr ' ' '\n' > $CACHE_DIR/tmp1
                id -G | tr ' ' '\n' > $CACHE_DIR/tmp2
                $BIN_DIR/busybox paste $CACHE_DIR/tmp1 $CACHE_DIR/tmp2 > $CACHE_DIR/tmp3
                local group_name group_id
                cat $CACHE_DIR/tmp3 | while read -r group_name group_id; do
                    echo "aid_${group_name}:x:${group_id}:root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/group"
                    if [ -f "$CONTAINER_DIR/etc/gshadow" ]; then
                        echo "aid_${group_name}:*::root,aid_$(id -un)" >> "$CONTAINER_DIR/etc/gshadow"
                    fi
                done
            """.trimIndent()
            val clean = $$"""
                $BIN_DIR/rm $CACHE_DIR/rootfs.tar.zst $CACHE_DIR/tmp1 $CACHE_DIR/tmp2 $CACHE_DIR/tmp3
            """.trimIndent()
            Global.setupEnvironment()
            Global.sendCommand("""
                $install
                $androidUidGidThings
                $clean
            """.trimIndent())
            Global.sendCommand("exit")
        }
        updateCurrentStep(InstallStep.CLEANING_CACHE)
        cleanCacheFiles()

        Global.installedContainers += code
        val mergedConfig = rawConfig.toMutableMap()
        mergedConfig["code"] = code
        ConfigManager.save(code, mergedConfig)

        loadContainers()
    }

    fun cancelInstall() {
        cleanCacheFiles()
        _installState.value = InstallState.Idle
    }

    fun cleanCacheFiles() {
        File(getApplication<Application>().filesDir, "rootfs.tar.zst").delete()
        File(getApplication<Application>().filesDir, ".tiny.yaml").delete()
    }

    fun resetInstallState() {
        _installState.value = InstallState.Idle
    }

    private fun updateCurrentStep(step: InstallStep) {
        val current = _installState.value
        if (current is InstallState.Installing) {
            _installState.value = current.copy(currentStep = step)
        }
    }

    /** 在 terminal session 中执行命令，等待 session 结束后返回 exitCode */
    private suspend fun execShell(block: () -> Unit): Int = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            Global.terminalSession?.finishIfRunning()
            Global.newSession(onFinished = { exitCode ->
                cont.resume(exitCode)
            })
            block()
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L     -> "%.2f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L         -> "%.2f KB".format(bytes / 1_000.0)
    else                    -> "$bytes B"
}
