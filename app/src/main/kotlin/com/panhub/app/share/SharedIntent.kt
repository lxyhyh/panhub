package com.panhub.app.share

import android.content.Intent
import com.panhub.feature.shareimport.SharedLink
import com.panhub.feature.shareimport.SharedText

/**
 * 从别的应用「分享到 PanHub」时递进来的东西。
 *
 * 只认 ACTION_SEND + text/plain：网盘链接在几乎所有 App 里都是纯文本分享，
 * 而 ACTION_SEND 带图片/文件的那种，分享过来我们也没有本事直接存到网盘，
 * 假装支持反而会让用户点进去发现什么都不能做。
 */
object SharedIntent {

    /**
     * 这次分享是不是一段可以用来找链接的文字。
     *
     * 单独抽出来是为了能测：真正的 [Intent] 在纯 JVM 单测里是空壳，
     * 能判断的只有 action 与 mimeType 这两件纯字符串的事。
     */
    fun isTextShare(action: String?, mimeType: String?): Boolean {
        if (action != Intent.ACTION_SEND) return false
        return mimeType.orEmpty().startsWith("text/")
    }

    /**
     * 读不出链接就返回 null。
     *
     * 这里不做「是不是网盘链接」的判断：认不认得出是 provider 的事，
     * 别家网盘的链接照样应该被打开，然后由 provider 给出「暂不支持」的提示。
     */
    fun read(intent: Intent?): SharedLink? {
        if (intent == null) return null
        if (!isTextShare(intent.action, intent.type)) return null
        @Suppress("DEPRECATION")
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return SharedText.extract(text)
    }
}
