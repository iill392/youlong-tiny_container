// Global.kt -- This file is part of tiny_container.
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

package com.fct.tc4.ui.misc

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.offsec.nhterm.backend.TerminalSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import androidx.core.content.edit
import coil.Coil
import coil.ImageLoader
import coil.decode.SvgDecoder
import java.io.File

object Global {
    /**
     * 二改版新增（APK 瘦身）：rootfs 镜像下载地址。
     * APK 不再内置 1GB 镜像，首次启动时从这里下载。
     * 镜像发布在 GitHub Releases，也可换成自建镜像站（如 https://yousj.cc/vm/）。
     */
    const val ROOTFS_DOWNLOAD_URL =
        "https://github.com/iill392/youlong-tiny_container/releases/latest/download/rootfs.tar.zst"

    /**
     * auto_launch = "xfce"
     * installed_containers = ["xfce", "lxqt", ...]
     *
     * */
    lateinit var sharedPrefs: SharedPreferences

    var terminalFontSize: Int
        get() = sharedPrefs.getInt("terminal_font_size", 42)
        set(value) = sharedPrefs.edit { putInt("terminal_font_size", value) }

    var shouldResetBootstrap: Boolean
        get() = sharedPrefs.getBoolean("reset_bootstrap", true)
        set(value) = sharedPrefs.edit { putBoolean("reset_bootstrap", value) }

    var autoLaunch: String
        get() = sharedPrefs.getString("auto_launch", "") ?: ""
        set(value) = sharedPrefs.edit { putString("auto_launch", value) }

    var autoLaunchGui: Boolean
        get() = sharedPrefs.getBoolean("auto_launch_gui", true)
        set(value) = sharedPrefs.edit { putBoolean("auto_launch_gui", value) }

    var installedContainers: Set<String>
        get() = sharedPrefs.getStringSet("installed_containers", emptySet()) ?: emptySet()
        set(value) = sharedPrefs.edit { putStringSet("installed_containers", value) }

    var useLegacyProot: Boolean
        get() = sharedPrefs.getBoolean("use_legacy_proot", true)
        set(value) = sharedPrefs.edit { putBoolean("use_legacy_proot", value) }

    var lastVersionCode: Int
        get() = sharedPrefs.getInt("last_version_code", 0)
        set(value) = sharedPrefs.edit { putInt("last_version_code", value) }

    var isFirstLaunchDone: Boolean
        get() = sharedPrefs.getBoolean("first_launch_done", false)
        set(value) = sharedPrefs.edit { putBoolean("first_launch_done", value) }

    /** 用户是否已同意法律声明（同意后不再重复弹出弹窗） */
    var legalNoticeAccepted: Boolean
        get() = sharedPrefs.getBoolean("legal_notice_accepted", false)
        set(value) = sharedPrefs.edit { putBoolean("legal_notice_accepted", value) }

    lateinit var appContext: Context

    var terminalSession: TerminalSession? = null
        private set


    var onSessionSignal9: (() -> Unit)? = null

    private val _screenUpdateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val screenUpdateEvent: SharedFlow<Unit> = _screenUpdateEvent.asSharedFlow()

    private val defaultSessionCallback = object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(changedSession: TerminalSession?) {
            _screenUpdateEvent.tryEmit(Unit)
        }

        override fun onColorsChanged(session: TerminalSession?) {
            _screenUpdateEvent.tryEmit(Unit)
        }

        override fun onTitleChanged(changedSession: TerminalSession?) {}

        override fun onSessionFinished(finishedSession: TerminalSession?) {
            _screenUpdateEvent.tryEmit(Unit)
        }

        override fun onClipboardText(session: TerminalSession?, text: String?) {
            if (text != null) {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
            }
        }

        override fun onBell(session: TerminalSession?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }.vibrate(VibrationEffect.createOneShot(300, 255))
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext

        // 非主进程（:xserver）不需要 shell/symlink/bootstrap
        if (Application.getProcessName() != appContext.packageName) return


        val imageLoader = ImageLoader.Builder(appContext)
            .components {
                add(SvgDecoder.Factory())
            }
            .build()
        Coil.setImageLoader(imageLoader)

        TerminalSession.setResources(appContext.resources)

        sharedPrefs = appContext.getSharedPreferences("tc4", Context.MODE_PRIVATE)

        val currentVersionCode = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionCode
        } catch (_: Exception) { 0 }
        if (lastVersionCode != currentVersionCode) {
            shouldResetBootstrap = true
            lastVersionCode = currentVersionCode
        }

        ConfigManager.init(appContext)

        terminalSession?.finishIfRunning()
        newSession()
        setNativeLibraryPath()
        cleanTmpFiles()
        setupBootstrapIfRequired()

    }

    fun newSession(
        shellPath: String = "/system/bin/sh",
        args: Array<String>? = null,
        env: Array<String>? = null,
        onFinished: ((exitCode: Int) -> Unit)? = null
    ): TerminalSession {
        val callback = if (onFinished != null) {
            object : TerminalSession.SessionChangedCallback by defaultSessionCallback {
                override fun onSessionFinished(finishedSession: TerminalSession?) {
                    defaultSessionCallback.onSessionFinished(finishedSession)
                    onFinished(finishedSession?.exitStatus ?: -1)
                }
            }
        } else {
            defaultSessionCallback
        }
        val session = TerminalSession(
            shellPath,
            appContext.dataDir.absolutePath,
            args, env,
            callback
        )
        session.updateSize(80, 24)
        terminalSession = session
        return session
    }

    fun setupEnvironment(){
        listOf("tmp", "run", "proot_tmp").forEach { dir ->
            File(appContext.filesDir, dir).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
        }
        sendCommand($$"""
            export BIN_DIR=$${appContext.filesDir.absolutePath}/bootstrap/bin
            export PUBLIC_DIR=$${appContext.filesDir.absolutePath}/public
            export CACHE_DIR=$${appContext.filesDir.absolutePath}
            export PATH=$BIN_DIR:$PATH
            export LD_LIBRARY_PATH=$${appContext.filesDir.absolutePath}/bootstrap/lib:$LD_LIBRARY_PATH
            export PROOT_LOADER=$${appContext.filesDir.absolutePath}/applib/libproot-loader-aarch64-5.1.107-68.so
            export PROOT_LOADER_32=$${appContext.filesDir.absolutePath}/applib/libproot-loader32-aarch64-5.1.107-68.so
            export PROOT_TMP_DIR=$CACHE_DIR/proot_tmp
            mkdir -p $CACHE_DIR/tmp
            mkdir -p $CACHE_DIR/run
            mkdir -p $PROOT_TMP_DIR
            mkdir -p $PUBLIC_DIR
        """.trimIndent())
    }

    fun sendCommand(command: String) {
        val session = terminalSession ?: return
        val bytes = (command + "\n").toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    fun setNativeLibraryPath() {
        sendCommand("""
            rm -rf ${appContext.filesDir.absolutePath}/applib
            ln -sf ${appContext.applicationInfo.nativeLibraryDir} ${appContext.filesDir.absolutePath}/applib
        """.trimIndent())
    }

    fun cleanTmpFiles() {
        sendCommand("rm -rf ${appContext.filesDir.absolutePath}/proot_tmp")
    }

    fun setupBootstrapIfRequired() {
        if (shouldResetBootstrap) {
            val setupExecutables = """
                mkdir -p ${appContext.filesDir.absolutePath}/bootstrap/bin
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/busybox
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/sh
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/cat
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/xz
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/gzip
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/sed
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/rm
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__busybox__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/unzip
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__proot-classic__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/proot
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__proot-latest__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/proot-latest
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__proot-classic__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/proot-classic
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__proot-classic-debug__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/proot-classic-debug
                ln -sf ${appContext.filesDir.absolutePath}/applib/libproot-loader32-aarch64-5.1.107-68.so ${appContext.filesDir.absolutePath}/bootstrap/bin/loader32
                ln -sf ${appContext.filesDir.absolutePath}/applib/libproot-loader-aarch64-5.1.107-68.so ${appContext.filesDir.absolutePath}/bootstrap/bin/loader
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__tar__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/tar
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__zstd__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/zstd
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__bin__virgl_test_server_android__.so ${appContext.filesDir.absolutePath}/bootstrap/bin/virgl_test_server
            """.trimIndent()
            val setupLibraries = """
                mkdir -p ${appContext.filesDir.absolutePath}/bootstrap/lib
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libacl.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libacl.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libandroid-selinux.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libandroid-selinux.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libattr.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libattr.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libbusybox.so.1.37.0__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libbusybox.so.1.37.0
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libiconv.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libiconv.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libpcre2-8.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libpcre2-8.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libtalloc.so.2.4.4__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libtalloc.so.2
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libzstd.so.1.5.7__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libzstd.so.1
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__libz.so.1.3.2__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libz.so.1
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__lib__liblzma.so.5.8.3__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/liblzma.so.5
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__opt__virglrenderer-android__lib__libvirglrenderer.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libvirglrenderer.so
                ln -sf ${appContext.filesDir.absolutePath}/applib/lib__opt__virglrenderer-android__lib__libepoxy.so__.so ${appContext.filesDir.absolutePath}/bootstrap/lib/libepoxy.so
            """.trimIndent()

            sendCommand("""
                $setupExecutables
                $setupLibraries
            """.trimIndent())
            shouldResetBootstrap = false
        }
    }

    /** 检查 assets 中是否内置了 rootfs.tar.zst */
    fun hasBuiltInRootfs(): Boolean {
        return try {
            appContext.assets.open("rootfs.tar.zst").use { }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 获取某个容器的完整配置（快捷方式）。
     * 返回 null 表示该容器未安装或配置文件不存在。
     */
    fun getContainerConfig(tag: String): Map<String, Any>? =
        ConfigManager.load(tag)
}