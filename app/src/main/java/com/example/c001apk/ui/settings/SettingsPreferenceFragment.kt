package com.example.c001apk.ui.settings

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.c001apk.BuildConfig
import com.example.c001apk.R
import com.example.c001apk.ui.blacklist.BlackListActivity
import com.example.c001apk.ui.main.INavViewContainer
import com.example.c001apk.ui.main.MainActivity
import com.example.c001apk.ui.others.AboutActivity
import com.example.c001apk.ui.settings.params.ParamsActivity
import com.example.c001apk.util.AccountBackup
import com.example.c001apk.util.ActivityCollector
import com.example.c001apk.util.CacheDataManager
import com.example.c001apk.util.IntentUtil
import com.example.c001apk.util.PrefManager
import com.example.c001apk.util.TokenDeviceUtils.getDeviceCode
import com.example.c001apk.util.TokenDeviceUtils.randHexString
import com.example.c001apk.util.doOnMainThreadIdle
import com.example.c001apk.util.setBottomPaddingSpace
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import rikka.core.util.ResourceUtils
import rikka.material.preference.MaterialSwitchPreference
import rikka.preference.SimpleMenuPreference

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?
    ): RecyclerView {
        val recyclerView =
            super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        recyclerView.apply {
            //overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        (activity as? INavViewContainer)?.hideNavigationView()
                    } else if (dy < 0) {
                        (activity as? INavViewContainer)?.showNavigationView()
                    }
                }
            })

            doOnMainThreadIdle {
                recyclerView.setBottomPaddingSpace()
            }

        }
        return recyclerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(resources.getDrawable(R.drawable.divider, requireContext().theme))
    }

    class SettingsPreferenceDataStore : PreferenceDataStore() {
        override fun getString(key: String?, defValue: String?): String {
            return when (key) {
                "darkTheme" -> PrefManager.darkTheme.toString()
                "themeColor" -> PrefManager.themeColor
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putString(key: String?, value: String?) {
            when (key) {
                "darkTheme" -> PrefManager.darkTheme = value?.toInt() ?: 0
                "themeColor" -> PrefManager.themeColor = value ?: "MATERIAL_DEFAULT"
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme
                "followSystemAccent" -> PrefManager.followSystemAccent
                "showEmoji" -> PrefManager.showEmoji
                "customToken" -> PrefManager.customToken
                "isRecordHistory" -> PrefManager.isRecordHistory
                "isIconMiniCard" -> PrefManager.isIconMiniCard
                "isOpenLinkOutside" -> PrefManager.isOpenLinkOutside
                "isColorFilter" -> PrefManager.isColorFilter
                "isCheckUpdate" -> PrefManager.isCheckUpdate
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }

        override fun putBoolean(key: String?, value: Boolean) {
            when (key) {
                "blackDarkTheme" -> PrefManager.blackDarkTheme = value
                "followSystemAccent" -> PrefManager.followSystemAccent = value
                "showEmoji" -> PrefManager.showEmoji = value
                "customToken" -> PrefManager.customToken = value
                "isRecordHistory" -> PrefManager.isRecordHistory = value
                "isIconMiniCard" -> PrefManager.isIconMiniCard = value
                "isOpenLinkOutside" -> PrefManager.isOpenLinkOutside = value
                "isColorFilter" -> PrefManager.isColorFilter = value
                "isCheckUpdate" -> PrefManager.isCheckUpdate = value
                else -> throw IllegalArgumentException("Invalid key: $key")
            }
        }
    }

    @SuppressLint("SetTextI18n", "InflateParams")
    // ---- 账号备份/恢复 ----
    private class CreateBackupDoc : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: android.content.Context, input: String) =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, input)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }

    private class PickBackupDoc : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: android.content.Context, input: Unit) =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
                .apply {
                    // 提示选择器默认打开 下载/c001apk
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        putExtra(
                            DocumentsContract.EXTRA_INITIAL_URI,
                            Uri.parse(
                                "content://com.android.externalstorage.documents/document/" +
                                    "primary%3ADownload%2F${AccountBackup.DIR_NAME}"
                            )
                        )
                    }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }

    /** Android 9 及以下：系统“另存为”，写入后用于展示的内容 */
    private var pendingBackupText: String? = null

    private val createBackupLauncher = registerForActivityResult(CreateBackupDoc()) { uri ->
        val text = pendingBackupText
        pendingBackupText = null
        if (uri != null && text != null) {
            if (AccountBackup.writeTo(requireContext(), uri, text))
                showBackupDone("已保存到你选择的位置（建议放到 ${AccountBackup.DISPLAY_DIR}）")
            else
                Toast.makeText(requireContext(), "备份失败", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickBackupLauncher = registerForActivityResult(PickBackupDoc()) { uri ->
        if (uri != null) onBackupFilePicked(uri)
    }

    private fun usageNotes(): String {
        val pkg = requireContext().packageName
        return "文件位置：${AccountBackup.DISPLAY_DIR}${AccountBackup.FILE_NAME}\n" +
            "（内部存储 ${AccountBackup.FULL_DIR}）\n\n" +
            "【恢复】设置 → 其他 → 恢复账号数据，选择该文件即可。重装或换机时，" +
            "先把文件放回「${AccountBackup.DISPLAY_DIR}」再恢复。\n\n" +
            "【给没有恢复功能的其它客户端使用】\n" +
            "· 备份是 JSON 文本文件，其中 cookie 字段形如 uid=…; username=…; token=…，" +
            "可直接用于支持 Cookie 登录的客户端。\n" +
            "· 账号还保存在本应用私有目录 /data/data/$pkg/shared_prefs/settings.xml" +
            "（需要 root 才能访问），可整体复制到另一个同样使用该文件名的客户端。\n" +
            "· 设备标识（xAppDevice 等）建议一并使用，否则可能被判为陌生设备。\n\n" +
            "⚠ 备份文件包含登录凭证，请勿发给他人或上传到公开位置。"
    }

    private fun showBackupDone(title: String) {
        MaterialAlertDialogBuilder(requireContext()).apply {
            setTitle("备份完成")
            setMessage("$title\n\n${usageNotes()}")
            setPositiveButton(android.R.string.ok, null)
            show()
        }
    }

    private fun doBackup() {
        val ctx = requireContext()
        if (!AccountBackup.hasAccount()) {
            Toast.makeText(ctx, "当前未登录，没有可备份的账号数据", Toast.LENGTH_SHORT).show()
            return
        }
        val text = AccountBackup.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (AccountBackup.saveToDownloads(ctx, text))
                showBackupDone("已保存到 ${AccountBackup.DISPLAY_DIR}${AccountBackup.FILE_NAME}")
            else
                Toast.makeText(ctx, "备份失败", Toast.LENGTH_SHORT).show()
        } else {
            pendingBackupText = text
            createBackupLauncher.launch(AccountBackup.FILE_NAME)
        }
    }

    private fun onBackupFilePicked(uri: Uri) {
        val ctx = requireContext()
        val backup = AccountBackup.readFrom(ctx, uri)?.let { AccountBackup.parse(it) }
        if (backup == null) {
            Toast.makeText(ctx, "不是有效的账号备份文件", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(ctx).apply {
            setTitle("确定恢复该账号吗？")
            setMessage(
                "用户名：${runCatching { java.net.URLDecoder.decode(backup.username, "UTF-8") }.getOrDefault(backup.username)}\n" +
                    "UID：${backup.uid}\n\n将覆盖当前登录的账号与设备标识。"
            )
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                AccountBackup.restore(backup)
                Toast.makeText(ctx, "账号数据已恢复", Toast.LENGTH_SHORT).show()
                ActivityCollector.recreateActivity(MainActivity::class.java.name)
            }
            show()
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = SettingsPreferenceDataStore()
        setPreferencesFromResource(R.xml.settings, rootKey)

        findPreference<SimpleMenuPreference>("darkTheme")?.setOnPreferenceChangeListener { _, newValue ->
            val newMode = (newValue as String).toInt()
            if (PrefManager.darkTheme != newMode) {
                AppCompatDelegate.setDefaultNightMode(newMode)
            }
            true
        }

        findPreference<MaterialSwitchPreference>("blackDarkTheme")?.setOnPreferenceChangeListener { _, _ ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration))
                activity?.recreate()
            true
        }

        findPreference<MaterialSwitchPreference>("followSystemAccent")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<SimpleMenuPreference>("themeColor")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<MaterialSwitchPreference>("showEmoji")?.setOnPreferenceChangeListener { _, _ ->
            activity?.recreate()
            true
        }

        findPreference<Preference>("about")?.summary =
            "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"
        findPreference<Preference>("about")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<AboutActivity>(requireContext()) {}
            true
        }


        findPreference<Preference>("params")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<ParamsActivity>(requireContext()) {}
            true
        }

        findPreference<Preference>("szlmId")?.setOnPreferenceClickListener {
            val view = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_x_app_token, null, false)
            val editText: EditText = view.findViewById(R.id.editText)
            editText.highlightColor = ColorUtils.setAlphaComponent(
                MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorPrimaryDark,
                    0
                ), 128
            )
            editText.setText(PrefManager.SZLMID)
            MaterialAlertDialogBuilder(requireContext()).apply {
                setView(view)
                setTitle(requireContext().getString(R.string.szlmId))
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    PrefManager.SZLMID = editText.text.toString()
                    PrefManager.xAppDevice = getDeviceCode(false)
                }
                setNeutralButton(R.string.random_value) { _, _ ->
                    PrefManager.SZLMID = randHexString(16)
                    PrefManager.xAppDevice = getDeviceCode(false)
                }
            }.create().apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                editText.requestFocus()
            }.show()
            true
        }

        findPreference<Preference>("userBlackList")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<BlackListActivity>(requireContext()) {
                putExtra("type", "user")
            }
            true
        }

        findPreference<Preference>("topicBlackList")?.setOnPreferenceClickListener {
            IntentUtil.startActivity<BlackListActivity>(requireContext()) {
                putExtra("type", "topic")
            }
            true
        }

        findPreference<Preference>("fontScale")?.setOnPreferenceClickListener {
            val view = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_font_scale, null, false)
            val slider: Slider = view.findViewById(R.id.slider)
            val fontScale: TextView = view.findViewById(R.id.fontScale)
            slider.apply {
                valueFrom = 0.80f
                valueTo = 1.30f
                value = PrefManager.FONTSCALE.toFloat()
                setLabelFormatter { value ->
                    String.format("%.2f", value)
                }
            }
            slider.addOnChangeListener { _, value, _ ->
                fontScale.text = "字体大小: ${String.format("%.2f", value)}"
                fontScale.textSize = 16f * value
            }
            fontScale.text = "字体大小: ${PrefManager.FONTSCALE}"
            fontScale.textSize = 16f * PrefManager.FONTSCALE.toFloat()
            MaterialAlertDialogBuilder(requireContext()).apply {
                setView(view)
                setTitle(R.string.font_scale)
                setNegativeButton(android.R.string.cancel, null)
                setNeutralButton("重置") { _, _ ->
                    PrefManager.FONTSCALE = "1.00"
                    ActivityCollector.recreateActivity(MainActivity::class.java.name)
                }
                setPositiveButton(android.R.string.ok) { _, _ ->
                    PrefManager.FONTSCALE = String.format("%.2f", slider.value)
                    ActivityCollector.recreateActivity(MainActivity::class.java.name)
                }
                show()
            }
            true
        }


        findPreference<Preference>("backupAccount")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle("备份账号数据")
                setMessage(usageNotes())
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton("备份") { _, _ -> doBackup() }
                show()
            }
            true
        }

        findPreference<Preference>("restoreAccount")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle("恢复账号数据")
                setMessage(
                    "请选择备份文件 ${AccountBackup.FILE_NAME}（默认在 ${AccountBackup.DISPLAY_DIR}）。\n\n" +
                        "恢复后会覆盖当前账号与设备标识。"
                )
                setNegativeButton(android.R.string.cancel, null)
                setPositiveButton("选择文件") { _, _ -> pickBackupLauncher.launch(Unit) }
                show()
            }
            true
        }

        findPreference<Preference>("clearCache")?.apply {
            summary = CacheDataManager.getTotalCacheSize(requireContext())
            setOnPreferenceClickListener {
                val currentSize = CacheDataManager.getTotalCacheSize(requireContext())
                summary = currentSize
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle("确定清除缓存吗？")
                    setMessage("当前缓存$currentSize")
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        CacheDataManager.clearAllCache(requireContext())
                        summary = "刚刚清理"
                    }
                    show()
                }
                true
            }
        }

        findPreference<Preference>("imageQuality")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle("图片画质")
                val items = arrayOf("网络自适应", "原图", "普清", "wsrv.nl图片代理")
                val index = when (PrefManager.imageQuality) {
                    "auto" -> 0
                    "origin" -> 1
                    "proxy" -> 3
                    else -> 2
                }
                setSingleChoiceItems(
                    items,
                    index
                ) { dialog: DialogInterface, position: Int ->
                    when (position) {
                        0 -> PrefManager.imageQuality = "auto"

                        1 -> PrefManager.imageQuality = "origin"

                        2 -> PrefManager.imageQuality = "thumbnail"

                        3 -> PrefManager.imageQuality = "proxy"
                    }
                    dialog.dismiss()
                }
                show()
            }
            true
        }

        findPreference<MaterialSwitchPreference>("isColorFilter")?.setOnPreferenceChangeListener { _, _ ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration))
                activity?.recreate()
            true
        }

    }

}