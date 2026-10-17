// ContainerManageFragment.kt -- This file is part of tiny_container.
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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.viewpager2.widget.ViewPager2
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fct.tc4.R
import com.fct.tc4.databinding.Tc4FragmentContainerManageBinding
import com.fct.tc4.databinding.Tc4ViewContainerCardBinding
import coil.load
import com.fct.tc4.ui.main.MainViewModel
import com.fct.tc4.ui.misc.ConfirmDialogFragment
import com.fct.tc4.ui.misc.ConfirmInstallDialogFragment
import androidx.fragment.app.DialogFragment
import com.fct.tc4.ui.misc.ContainerEditDialogFragment
import com.fct.tc4.ui.misc.FakeProgressDialogFragment
import com.fct.tc4.ui.misc.Global
import com.fct.tc4.ui.page.formatBytes
import com.fct.tc4.ui.misc.ProgressDialogFragment
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class ContainerManageFragment : Fragment() {
    private var _binding: Tc4FragmentContainerManageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ContainerManageViewModel by activityViewModels()
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: ContainerCardAdapter

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onFileSelected(it) }
    }

    private var exportCodeForPicker: String? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                exportCodeForPicker?.let { viewModel.exportContainer(it, uri) }
            }
        }
        exportCodeForPicker = null
    }

    /** 由 Activity 菜单栏调用，启动 SAF 文件选择器 */
    fun onImportAction() {
        viewModel.startImport()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.tc4_container_title)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = Tc4FragmentContainerManageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ContainerCardAdapter(
            onDelete = { code, displayName -> viewModel.requestDelete(code, displayName) },
            onExport = { code, displayName -> viewModel.requestExport(code, displayName) },
            onEdit = { code, name, description ->
                ContainerEditDialogFragment.newInstance(code, name, description)
                    .show(childFragmentManager, "edit_container_$code")
            },
            onLaunch = { code -> mainViewModel.navigateTo(MainViewModel.Screen.ContainerMain(code)) }
        )
        binding.viewpager.adapter = adapter

        // 页面滑动时保存位置到 ViewModel（旋转不丢失）
        binding.viewpager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.selectPosition(position)
            }
        })

        binding.errorDeleteBtn.setOnClickListener {
            viewModel.loadContainers()
        }

        // ====== 页面状态 ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pageState.collect { state ->
                    when (state) {
                        is ContainerManagePageState.Loading -> {
                            binding.loadingGroup.visibility = View.VISIBLE
                            binding.errorGroup.visibility = View.GONE
                            binding.emptyHint.visibility = View.GONE
                            binding.viewpager.visibility = View.INVISIBLE
                            binding.indicator.visibility = View.INVISIBLE
                        }
                    is ContainerManagePageState.Idle -> {
                        binding.loadingGroup.visibility = View.GONE
                        binding.errorGroup.visibility = View.GONE
                        binding.viewpager.visibility = View.VISIBLE
                        binding.indicator.visibility = View.VISIBLE
                        binding.emptyHint.visibility = if (viewModel.containers.value.isEmpty()) View.VISIBLE else View.GONE
                    }
                        is ContainerManagePageState.Error -> {
                            binding.loadingGroup.visibility = View.GONE
                            binding.errorGroup.visibility = View.VISIBLE
                            binding.emptyHint.visibility = View.GONE
                            binding.errorMessage.text = state.message
                            binding.viewpager.visibility = View.INVISIBLE
                            binding.indicator.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        }

        // ====== 容器列表 ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.containers.collect { list ->
                    val isIdle = viewModel.pageState.value is ContainerManagePageState.Idle
                    binding.emptyHint.visibility = if (list.isEmpty() && isIdle) View.VISIBLE else View.GONE
                    adapter.submitList(list) {
                        binding.indicator.setupWithViewPager(binding.viewpager)
                        val pos = viewModel.selectedPosition.value
                        if (pos in 0 until adapter.itemCount) {
                            binding.viewpager.setCurrentItem(pos, false)
                        }
                    }
                }
            }
        }

        // ====== 删除确认（StateFlow，旋转存活） ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pendingDeleteConfirm.collect { pending ->
                    if (pending != null) {
                        val (code, displayName) = pending
                        val tag = "confirm_delete"
                        childFragmentManager.setFragmentResultListener(tag, viewLifecycleOwner) { _, bundle ->
                            if (bundle.getBoolean("confirmed")) {
                                viewModel.confirmDelete(code)
                            } else {
                                viewModel.cancelDelete()
                            }
                        }
                        if (childFragmentManager.findFragmentByTag(tag) == null) {
                            ConfirmDialogFragment.show(
                                childFragmentManager, tag,
                                title = getString(R.string.tc4_dialog_delete_title),
                                message = getString(R.string.tc4_dialog_delete_message, displayName),
                                positiveText = getString(R.string.tc4_btn_delete),
                                negativeText = getString(R.string.tc4_btn_cancel)
                            )
                        }
                    }
                }
            }
        }

        // ====== 删除状态 ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deleteState.collect { state ->
                    when (state) {
                        null -> {
                            (childFragmentManager.findFragmentByTag("delete_progress") as? ProgressDialogFragment)?.dismiss()
                        }
                        is DeleteState.InProgress -> {
                            val tag = "delete_progress"
                            val d = childFragmentManager.findFragmentByTag(tag) as? ProgressDialogFragment
                            if (d == null) {
                                ProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .title(getString(R.string.tc4_dialog_deleting_title))
                                    .show(tag)
                            }
                        }
                        is DeleteState.Completed -> {
                            (childFragmentManager.findFragmentByTag("delete_progress") as? ProgressDialogFragment)?.dismiss()
                            Snackbar.make(binding.root, R.string.tc4_container_delete_success, Snackbar.LENGTH_SHORT).show()
                            viewModel.resetDeleteState()
                        }
                        is DeleteState.Failed -> {
                            (childFragmentManager.findFragmentByTag("delete_progress") as? ProgressDialogFragment)?.dismiss()
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                            viewModel.resetDeleteState()
                        }
                    }
                }
            }
        }

        // ====== 编辑容器信息结果 ======
        childFragmentManager.setFragmentResultListener(
            ContainerEditDialogFragment.REQUEST_SAVE, viewLifecycleOwner
        ) { _, bundle ->
            val code = bundle.getString(ContainerEditDialogFragment.KEY_CODE) ?: return@setFragmentResultListener
            val name = bundle.getString(ContainerEditDialogFragment.KEY_NAME, "")
            val desc = bundle.getString(ContainerEditDialogFragment.KEY_DESC, "")
            viewModel.updateContainerConfig(code, name, desc)
        }

        // ====== 安装状态 ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.installState.collect { state ->
                    // 非 ImportWarn 时关闭可能残留的确认对话框
                    if (state !is InstallState.ImportWarn) {
                        (childFragmentManager.findFragmentByTag("confirm_import_warn") as? DialogFragment)?.dismiss()
                    }
                    when (state) {
                        is InstallState.Idle -> {
                            (childFragmentManager.findFragmentByTag("install_copy_progress") as? ProgressDialogFragment)?.dismiss()
                            (childFragmentManager.findFragmentByTag("install_progress") as? FakeProgressDialogFragment)?.dismiss()
                        }
                        is InstallState.ImportWarn -> {
                            val key = "import_warn"
                            childFragmentManager.setFragmentResultListener(key, viewLifecycleOwner) { _, bundle ->
                                if (bundle.getBoolean("confirmed")) {
                                    filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                                } else if (bundle.getBoolean(ConfirmDialogFragment.KEY_NEUTRAL, false)) {
                                    // 二改版：无内置镜像时中性按钮改为下载，有内置时走内置安装
                                    if (Global.hasBuiltInRootfs()) {
                                        viewModel.startBuiltInImport()
                                    } else {
                                        viewModel.startDownloadImport()
                                    }
                                } else {
                                    viewModel.resetInstallState()
                                }
                            }
                            if (childFragmentManager.findFragmentByTag("confirm_$key") == null) {
                                ConfirmDialogFragment.show(
                                    childFragmentManager, key,
                                    title = getString(R.string.tc4_import_title),
                                    message = getString(R.string.tc4_import_warning),
                                    positiveText = getString(R.string.tc4_btn_continue),
                                    negativeText = getString(R.string.tc4_btn_cancel),
                                    // 二改版：无内置镜像时显示下载按钮（复用同一对话框，不新增界面）
                                    neutralText = if (Global.hasBuiltInRootfs()) getString(R.string.tc4_import_builtin_btn) else getString(R.string.tc4_import_download_btn)
                                )
                            }
                        }
                        is InstallState.Downloading -> {
                            // 二改版新增：下载进度复用现有进度对话框
                            val tag = "install_copy_progress"
                            val d = childFragmentManager.findFragmentByTag(tag) as? ProgressDialogFragment
                            val pct = if (state.totalBytes > 0)
                                (state.downloadedBytes * 100 / state.totalBytes).toInt() else -1
                            val title = if (pct >= 0)
                                "${getString(R.string.tc4_import_downloading_title)} $pct%"
                            else
                                getString(R.string.tc4_import_downloading_title)
                            if (d == null) {
                                ProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .title(title)
                                    .show(tag)
                            } else {
                                d.updateTitle(title)
                            }
                        }
                        is InstallState.CopyingToCache -> {
                            val tag = "install_copy_progress"
                            val d = childFragmentManager.findFragmentByTag(tag) as? ProgressDialogFragment
                            if (d == null) {
                                ProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .title(getString(R.string.tc4_import_copying_title))
                                    .show(tag)
                            }
                        }
                        is InstallState.ExtractingConfig -> {
                            val tag = "install_copy_progress"
                            val extractingText = getString(R.string.tc4_import_extracting_title)
                            var dialog = childFragmentManager.findFragmentByTag(tag) as? ProgressDialogFragment
                            if (dialog == null) {
                                dialog = ProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .title(extractingText)
                                    .show(tag)
                            } else {
                                dialog.updateTitle(extractingText)
                            }
                        }
                        is InstallState.AwaitingConfirm -> {
                            (childFragmentManager.findFragmentByTag("install_copy_progress") as? ProgressDialogFragment)?.dismiss()
                            val requestKey = "confirm_install"
                            val rawConfig = state.rawConfig
                            val initialCode = rawConfig["code"] as? String ?: ""
                            childFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
                                if (bundle.getBoolean(ConfirmInstallDialogFragment.KEY_CONFIRMED)) {
                                    viewModel.confirmInstall(
                                        bundle.getString(ConfirmInstallDialogFragment.KEY_CODE) ?: initialCode,
                                        bundle.getBoolean(ConfirmInstallDialogFragment.KEY_LAUNCH)
                                    )
                                } else {
                                    viewModel.cancelInstall()
                                }
                            }
                            if (childFragmentManager.findFragmentByTag("confirm_install") == null) {
                                ConfirmInstallDialogFragment.show(
                                    childFragmentManager, requestKey,
                                    initialCode = initialCode,
                                    name = rawConfig["name"] as? String ?: "",
                                    description = rawConfig["description"] as? String ?: ""
                                )
                            }
                        }
                        is InstallState.Installing -> {
                            // 安装时不再显示网页，统一使用假进度对话框
                            val stepText = when (state.currentStep) {
                                InstallStep.DELETING_OLD -> getString(R.string.tc4_import_deleting_old_title)
                                InstallStep.EXTRACTING_ROOTFS -> getString(R.string.tc4_import_installing_title)
                                InstallStep.CLEANING_CACHE -> getString(R.string.tc4_import_cleaning_title)
                            }
                            val tag = "install_progress"
                            var dialog = childFragmentManager.findFragmentByTag(tag) as? FakeProgressDialogFragment
                            if (dialog == null) {
                                dialog = FakeProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .startTime(state.startTimeMillis)
                                    .containerSizeBytes(state.containerSizeBytes)
                                    .initialTitle(stepText)
                                    .show(tag)
                            } else {
                                dialog.updateTitle(stepText)
                            }
                        }
                        is InstallState.Completed -> {
                            (childFragmentManager.findFragmentByTag("install_progress") as? FakeProgressDialogFragment)?.dismiss()
                            if (state.launchAfterInstall) {
                                mainViewModel.navigateTo(MainViewModel.Screen.ContainerMain(code = state.code))
                            } else {
                                Snackbar.make(binding.root, getString(R.string.tc4_import_completed, state.code), Snackbar.LENGTH_SHORT).show()
                            }
                            viewModel.resetInstallState()
                        }
                        is InstallState.Failed -> {
                            (childFragmentManager.findFragmentByTag("install_copy_progress") as? ProgressDialogFragment)?.dismiss()
                            (childFragmentManager.findFragmentByTag("install_progress") as? FakeProgressDialogFragment)?.dismiss()
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_INDEFINITE)
                                .setAction(R.string.tc4_btn_ok) { viewModel.resetInstallState() }
                                .show()
                        }
                    }
                }
            }
        }

        // ====== 导出状态 ======
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.exportState.collect { state ->
                    // 非 PendingPick 时关闭可能残留的确认对话框
                    if (state !is ExportState.PendingPick) {
                        (childFragmentManager.findFragmentByTag("confirm_export_warn") as? DialogFragment)?.dismiss()
                    }
                    when (state) {
                        is ExportState.PendingPick -> {
                            val msg = getString(R.string.tc4_export_warning)
                            childFragmentManager.setFragmentResultListener("export_warn", viewLifecycleOwner) { _, bundle ->
                                if (bundle.getBoolean("confirmed")) {
                                    exportCodeForPicker = state.code
                                    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_TITLE,
                                            "${state.code}-$timestamp.tar.zst")
                                    }
                                    exportLauncher.launch(intent)
                                } else {
                                    viewModel.resetExportState()
                                }
                            }
                            ConfirmDialogFragment.show(
                                childFragmentManager, "export_warn",
                                title = getString(R.string.tc4_export_title),
                                message = msg,
                                positiveText = getString(R.string.tc4_btn_continue),
                                negativeText = getString(R.string.tc4_btn_cancel)
                            )
                        }
                        is ExportState.InProgress -> {
                            val tag = "export_progress"
                            val d = childFragmentManager.findFragmentByTag(tag) as? FakeProgressDialogFragment
                            if (d == null) {
                                FakeProgressDialogFragment
                                    .newBuilder(childFragmentManager)
                                    .startTime(state.startTimeMillis)
                                    .containerSizeBytes(state.containerSizeBytes)
                                    .initialTitle(getString(R.string.tc4_export_progress_title))
                                    .show(tag)
                            }
                        }
                        is ExportState.Completed -> {
                            (childFragmentManager.findFragmentByTag("export_progress") as? FakeProgressDialogFragment)?.dismiss()
                            Snackbar.make(binding.root, R.string.tc4_export_success, Snackbar.LENGTH_SHORT).show()
                            viewModel.resetExportState()
                        }
                        is ExportState.Failed -> {
                            (childFragmentManager.findFragmentByTag("export_progress") as? FakeProgressDialogFragment)?.dismiss()
                            Snackbar.make(binding.root, state.message, Snackbar.LENGTH_LONG).show()
                            viewModel.resetExportState()
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ========== ViewHolder ==========

private class ContainerCardViewHolder(
    private val binding: Tc4ViewContainerCardBinding,
    private val onDelete: (code: String, displayName: String) -> Unit,
    private val onExport: (code: String, displayName: String) -> Unit,
    private val onEdit: (code: String, name: String, description: String) -> Unit,
    private val onLaunch: (code: String) -> Unit
) : RecyclerView.ViewHolder(binding.root) {

    private var currentItem: ContainerItem? = null

    fun bind(item: ContainerItem) {
        currentItem = item

        when (item) {
            is ContainerItem.Error -> {
                binding.normalContent.visibility = View.GONE
                binding.cardErrorGroup.visibility = View.VISIBLE
                binding.cardErrorMessage.text = item.message
                binding.cardErrorDeleteBtn.setOnClickListener {
                    currentItem?.let { onDelete(it.code, it.code) }
                }
            }
            is ContainerItem.Loaded -> {
                binding.normalContent.visibility = View.VISIBLE
                binding.cardErrorGroup.visibility = View.GONE

                val displayName = item.name.ifEmpty { item.code }
                binding.name.text = displayName
                binding.description.text = item.description.ifEmpty { item.code }
                binding.space.text = if (item.spaceBytes == null)
                    "${item.code} - ${itemView.context.getString(R.string.tc4_container_calculating_space)}"
                else "${item.code} - ${formatBytes(item.spaceBytes)}"

                // 加载预览图
                if (item.image.isNotBlank()) {
                    val dataDir = itemView.context.applicationContext.let { ctx ->
                        (ctx as android.app.Application).dataDir
                    }
                    val imgFile = java.io.File(dataDir, item.code).resolve(item.image.removePrefix("/"))
                    binding.image.load(imgFile) {
                        crossfade(true)
                        error(android.R.color.transparent)
                    }
                    binding.image.visibility = View.VISIBLE
                } else {
                    binding.image.visibility = View.GONE
                }

                binding.launch.setOnClickListener {
                    currentItem?.let { onLaunch(it.code) }
                }
                binding.delete.setOnClickListener {
                    currentItem?.let { onDelete(it.code, displayName) }
                }
                binding.export.setOnClickListener {
                    currentItem?.let { onExport(it.code, displayName) }
                }
                binding.edit.setOnClickListener {
                    currentItem?.let { item ->
                        if (item is ContainerItem.Loaded) {
                            onEdit(item.code, item.name, item.description)
                        }
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun bind(item: ContainerItem, payloads: List<Any>) {
        val changes = payloads[0] as? List<String> ?: run { bind(item); return }
        val loaded = item as? ContainerItem.Loaded ?: run { bind(item); return }
        currentItem = item

        val displayName = loaded.name.ifEmpty { loaded.code }
        if ("name" in changes) {
            binding.name.text = displayName
        }
        if ("description" in changes) {
            binding.description.text = loaded.description.ifEmpty { loaded.code }
        }
        if ("space" in changes) {
            binding.space.text = if (loaded.spaceBytes == null)
                "${item.code} - ${itemView.context.getString(R.string.tc4_container_calculating_space)}"
            else "${loaded.code} - ${formatBytes(loaded.spaceBytes)}"
        }
        // space 单独更新时不重新加载图片，避免闪烁
    }
}

// ========== ListAdapter + DiffUtil ==========

private class ContainerCardAdapter(
    private val onDelete: (code: String, displayName: String) -> Unit,
    private val onExport: (code: String, displayName: String) -> Unit,
    private val onEdit: (code: String, name: String, description: String) -> Unit,
    private val onLaunch: (code: String) -> Unit
) : ListAdapter<ContainerItem, ContainerCardViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContainerCardViewHolder {
        val cardBinding = Tc4ViewContainerCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ContainerCardViewHolder(cardBinding, onDelete, onExport, onEdit, onLaunch)
    }

    override fun onBindViewHolder(holder: ContainerCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: ContainerCardViewHolder, position: Int, payloads: List<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            holder.bind(getItem(position), payloads)
        }
    }
}

private object DiffCallback : DiffUtil.ItemCallback<ContainerItem>() {
    override fun areItemsTheSame(old: ContainerItem, new: ContainerItem): Boolean =
        old.code == new.code

    override fun areContentsTheSame(old: ContainerItem, new: ContainerItem): Boolean =
        old == new

    override fun getChangePayload(old: ContainerItem, new: ContainerItem): Any? {
        if (old !is ContainerItem.Loaded || new !is ContainerItem.Loaded) return null
        if (old === new) return null
        val changes = mutableListOf<String>()
        if (old.name != new.name) changes.add("name")
        if (old.description != new.description) changes.add("description")
        if (old.image != new.image) changes.add("image")
        if (old.spaceBytes != new.spaceBytes) changes.add("space")
        return if (changes.isEmpty()) null else changes
    }
}
