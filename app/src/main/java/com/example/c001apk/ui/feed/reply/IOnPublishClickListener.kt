package com.example.c001apk.ui.feed.reply

interface IOnPublishClickListener {

    /** pic：已上传图片的文件名，逗号分隔；无图片时为空字符串 */
    fun onPublish(message: String, replyAndForward: String, pic: String)

}
