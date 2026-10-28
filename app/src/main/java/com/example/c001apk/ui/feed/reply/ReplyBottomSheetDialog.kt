package com.example.c001apk.ui.feed.reply

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.c001apk.util.ImageUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.viewpager.widget.ViewPager
import com.absinthe.libraries.utils.utils.UiUtils
import com.example.c001apk.R
import com.example.c001apk.util.Emoji
import com.example.c001apk.util.EmojiUtil
import com.example.c001apk.util.SpannableStringBuilderUtil
import com.example.c001apk.view.ExtendEditText
import com.example.c001apk.view.circleindicator.CircleIndicator
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

class ReplyBottomSheetDialog(mContext: Context, mView: View) : BottomSheetDialog(mContext),
    IOnEmojiClickListener {

    private var iOnPublishClickListener: IOnPublishClickListener? = null

    fun setIOnPublishClickListener(iOnPublishClickListener: IOnPublishClickListener) {
        this.iOnPublishClickListener = iOnPublishClickListener
    }

    private val context: Context = mContext
    private var isPaste = false
    private var cursorBefore = -1
    private val view = mView
    var type = ""
    var rid = ""
    var ruid = ""
    var uname = ""
    private var replyTextMap: MutableMap<String, String> = HashMap()
    private var replyAndForward = "0"
    var editText: ExtendEditText = view.findViewById(R.id.editText)

    // ---- 图片 ----
    private val maxImages = 9
    private val images = ArrayList<Uri>()
    private var imageAdapter: ImageAdapter? = null
    private var imageListView: RecyclerView? = null
    private var uploadJob: Job? = null
    private var uploadScope: CoroutineScope? = null
    private var isUploading = false
    private var lastKey: String? = null
    private var refreshPublish: (() -> Unit)? = null

    private inner class ImageAdapter : RecyclerView.Adapter<ImageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val remove: ImageView = v.findViewById(R.id.remove)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            VH(
                android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_reply_image, parent, false)
            )

        override fun getItemCount() = images.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.thumb).load(images[position]).centerCrop().into(holder.thumb)
            holder.remove.setOnClickListener {
                if (isUploading) return@setOnClickListener
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in images.indices) {
                    images.removeAt(pos)
                    notifyItemRemoved(pos)
                    syncImageList()
                }
            }
        }
    }

    private fun syncImageList() {
        imageListView?.isVisible = images.isNotEmpty()
        refreshPublish?.invoke()
    }

    /** 发布成功后由宿主调用 */
    fun clearImages() {
        images.clear()
        imageAdapter?.notifyDataSetChanged()
        syncImageList()
    }

    private fun findActivity(c: Context): ComponentActivity? {
        var x: Context? = c
        while (x is ContextWrapper) {
            if (x is ComponentActivity) return x
            x = x.baseContext
        }
        return null
    }

    private fun pickImages() {
        if (isUploading) return
        val left = maxImages - images.size
        if (left <= 0) {
            Toast.makeText(context, "最多添加 $maxImages 张图片", Toast.LENGTH_SHORT).show()
            return
        }
        val activity = findActivity(context) ?: return
        val ref = arrayOfNulls<ActivityResultLauncher<String>>(1)
        ref[0] = activity.activityResultRegistry.register(
            "reply_pick_${System.nanoTime()}",
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            ref[0]?.unregister()
            if (uris.isNotEmpty()) {
                val add = uris.filter { it !in images }.take(left)
                val start = images.size
                images.addAll(add)
                imageAdapter?.notifyItemRangeInserted(start, add.size)
                if (uris.size > left)
                    Toast.makeText(context, "最多添加 $maxImages 张图片", Toast.LENGTH_SHORT).show()
                syncImageList()
            }
        }
        ref[0]?.launch("image/*")
    }

    private fun startPublish(publishView: TextView) {
        if (isUploading) return
        var message = editText.text.toString()
        if (message.replace("\n", "").isEmpty() && images.isNotEmpty()) message = "[图片]"
        val forward = replyAndForward
        if (images.isEmpty()) {
            iOnPublishClickListener?.onPublish(message, forward, "")
            return
        }
        isUploading = true
        refreshPublish?.invoke()
        uploadScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { uploadScope = it }
        uploadJob = scope.launch {
            try {
                val names = ImageUploader.upload(
                    context,
                    images.toList(),
                    if (type == "reply") "comment" else "feed"
                ) { done: Int, total: Int ->
                    scope.launch { publishView.text = "上传中 $done/$total" }
                }
                isUploading = false
                publishView.text = context.getString(R.string.publish)
                refreshPublish?.invoke()
                iOnPublishClickListener?.onPublish(message, forward, names.joinToString(","))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                isUploading = false
                publishView.text = context.getString(R.string.publish)
                refreshPublish?.invoke()
                Toast.makeText(context, e.message ?: "图片上传失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 面板关闭时中止未完成的上传
        uploadScope?.cancel()
        if (isUploading) {
            isUploading = false
            refreshPublish?.invoke()
        }
    }

    fun setData() {
        val key = "$type|$rid|$ruid"
        if (lastKey != null && lastKey != key) clearImages()
        lastKey = key
        editText.hint = "回复: $uname"
        if (replyTextMap[rid + ruid].isNullOrEmpty())
            editText.text = null
        else {
            editText.text =
                SpannableStringBuilderUtil.setEmoji(
                    context,
                    replyTextMap.getOrDefault(rid + ruid, ""),
                    (editText.textSize * 1.3).toInt()
                )
            editText.setSelection(editText.text.toString().length)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        editText.highlightColor = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorPrimaryDark,
                0
            ), 128
        )
        val title: TextView = view.findViewById(R.id.title)
        val publish: TextView = view.findViewById(R.id.publish)
        val emotion: ImageView = view.findViewById(R.id.emotion)
        val emojiPanel: ViewPager = view.findViewById(R.id.emojiPanel)
        val indicator: CircleIndicator = view.findViewById(R.id.indicator)
        val forwardLayout: LinearLayout = view.findViewById(R.id.forwardLayout)
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        if (type == "publish") {
            title.text = "发动态"
            forwardLayout.isVisible = false
        } else if (type == "reply") {
            title.text = "回复"
        }
        val itemBeans = Emoji.initEmoji()
        val scrollAdapter = HorizontalScrollAdapter(context, itemBeans)
        scrollAdapter.setIOnEmojiClickListener(this)
        emojiPanel.adapter = scrollAdapter
        indicator.setViewPager(emojiPanel)

        imageListView = view.findViewById<RecyclerView>(R.id.imageList).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            imageAdapter = ImageAdapter().also { adapter = it }
            isVisible = images.isNotEmpty()
        }
        view.findViewById<ImageView>(R.id.imageBtn).setOnClickListener { pickImages() }

        fun checkAndPublish() {
            val hasContent =
                editText.text.toString().replace("\n", "").isNotEmpty() || images.isNotEmpty()
            val enabled = hasContent && !isUploading
            publish.isClickable = enabled
            publish.setTextColor(
                if (enabled) MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorPrimary,
                    0
                ) else context.getColor(android.R.color.darker_gray)
            )
        }
        refreshPublish = ::checkAndPublish
        publish.setOnClickListener { startPublish(publish) }

        checkAndPublish()
        editText.isFocusable = true
        editText.isFocusableInTouchMode = true
        editText.requestFocus()
        val imm =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, 0)

        window?.let {
            it.attributes?.windowAnimations = com.absinthe.libraries.utils.R.style.DialogAnimation
            WindowCompat.setDecorFitsSystemWindows(it, false)
            UiUtils.setSystemBarStyle(it)
            WindowInsetsControllerCompat(it, it.decorView)
                .isAppearanceLightNavigationBars = !UiUtils.isDarkMode()
        }

        findViewById<View>(com.google.android.material.R.id.container)?.fitsSystemWindows = false
        findViewById<View>(com.google.android.material.R.id.coordinator)?.fitsSystemWindows = false

        emotion.setOnClickListener {
            if (emojiPanel.visibility != View.VISIBLE) {
                emojiPanel.isVisible = true
                indicator.isVisible = true
                val keyboard = ContextCompat.getDrawable(context, R.drawable.ic_arrow_down)
                keyboard?.let {
                    val drawableKeyboard = DrawableCompat.wrap(it)
                    DrawableCompat.setTint(
                        drawableKeyboard,
                        ContextCompat.getColor(context, android.R.color.darker_gray)
                    )
                    emotion.setImageDrawable(drawableKeyboard)
                }
            } else {
                emojiPanel.isVisible = false
                indicator.isVisible = false
                val face = ContextCompat.getDrawable(context, R.drawable.ic_face)
                face?.let {
                    val drawableFace = DrawableCompat.wrap(it)
                    DrawableCompat.setTint(
                        drawableFace,
                        ContextCompat.getColor(context, android.R.color.darker_gray)
                    )
                    emotion.setImageDrawable(drawableFace)
                }
            }
        }

        checkBox.setOnCheckedChangeListener { _, isChecked ->
            replyAndForward = if (isChecked) "1"
            else "0"
        }

        editText.setOnPasteCallback(object : ExtendEditText.OnPasteCallback {
            override fun onPaste(text: String?, isPaste: Boolean) {
                this@ReplyBottomSheetDialog.isPaste = isPaste
                if (isPaste) {
                    cursorBefore = editText.selectionStart
                } else {
                    if (text == "") {//delete
                        editText.editableText.delete(
                            editText.selectionStart,
                            editText.selectionEnd
                        )
                    } else {
                        val builder = SpannableStringBuilderUtil.setEmoji(
                            context,
                            text ?: "",
                            (editText.textSize * 1.3).toInt(),
                        )
                        editText.editableText.replace(
                            editText.selectionStart,
                            editText.selectionEnd,
                            builder
                        )
                    }
                }
            }
        })

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}

            override fun onTextChanged(p0: CharSequence?, start: Int, before: Int, count: Int) {
                if (isPaste) {
                    isPaste = false
                    val cursorNow = editText.selectionStart
                    val pasteText =
                        editText.text.toString().substring(cursorBefore, cursorNow)
                    val builder = SpannableStringBuilderUtil.setEmoji(
                        context,
                        pasteText,
                        (editText.textSize * 1.3).toInt()
                    )
                    editText.editableText.replace(
                        cursorBefore,
                        cursorNow,
                        builder
                    )
                }
            }

            override fun afterTextChanged(p0: Editable?) {
                if (type == "reply")
                    replyTextMap[rid + ruid] = editText.text.toString()
                checkAndPublish()
            }
        })


    }

    override fun onShowEmoji(name: String) {
        val selectionStart: Int = editText.selectionStart
        val selectionEnd: Int = editText.selectionEnd
        if (name == "[c001apk]") { //delete
            if (selectionStart > 0) {
                val body: String = editText.text.toString()
                if (!TextUtils.isEmpty(body)) {
                    if (selectionStart == selectionEnd) {
                        val tempStr = body.substring(0, selectionStart)
                        val lastString = tempStr.substring(selectionStart - 1)
                        if ("]" == lastString) {
                            val i = tempStr.lastIndexOf("[")
                            if (i != -1) {
                                val cs = tempStr.substring(i, selectionStart)
                                if (EmojiUtil.getEmoji(cs) != -1) {
                                    editText.editableText.delete(i, selectionStart)
                                    return
                                } else {
                                    editText.editableText.delete(
                                        tempStr.length - 1,
                                        selectionStart
                                    )
                                }
                            } else {
                                editText.editableText.delete(tempStr.length - 1, selectionStart)
                            }
                        } else {
                            editText.editableText.delete(tempStr.length - 1, selectionStart)
                        }
                    } else { //括选
                        editText.editableText.delete(selectionStart, selectionEnd)
                    }
                }
            } else if (selectionStart == 0 && selectionEnd != 0) {
                editText.editableText.delete(selectionStart, selectionEnd)
            }
        } else {//insert
            editText.editableText.replace(
                selectionStart,
                selectionEnd,
                SpannableStringBuilderUtil.setEmoji(
                    context,
                    name,
                    (editText.textSize * 1.3).toInt()
                )
            )
        }
    }

}
