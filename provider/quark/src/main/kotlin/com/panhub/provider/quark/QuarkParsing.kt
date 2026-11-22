package com.panhub.provider.quark

/**
 * 夸克网盘的纯解析逻辑（不碰网络，便于单测）。
 */
internal object QuarkParsing {

    /**
     * 反转义 HTML 实体。
     *
     * 夸克的 file_name 里有 &amp; &#39; 这类实体，直接展示会串味；
     * 而且如果不反转义，同一个文件在不同接口返回的"名字"会对不上（比较、去重都会错）。
     */
    fun unescape(raw: String): String {
        if (raw.indexOf('&') < 0) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val end = raw.indexOf(';', i + 1)
            // 太远的 ';' 不算实体，避免把正常文本当实体吃掉。
            if (end < 0 || end - i > 10) {
                out.append(c)
                i++
                continue
            }
            val entity = raw.substring(i + 1, end)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = end + 1
            }
        }
        return out.toString()
    }

    private fun decodeEntity(entity: String): String? {
        when (entity) {
            "amp" -> return "&"
            "lt" -> return "<"
            "gt" -> return ">"
            "quot" -> return "\""
            "apos" -> return "'"
            "nbsp" -> return " "
        }
        if (entity.startsWith("#")) {
            val code = if (entity.startsWith("#x") || entity.startsWith("#X")) {
                entity.substring(2).toIntOrNull(16)
            } else {
                entity.substring(1).toIntOrNull()
            }
            if (code != null && code > 0 && code <= 0x10FFFF) {
                return String(Character.toChars(code))
            }
        }
        return null
    }

    /** 文件条目在 UI 里的名字：反转义后若为空则退回 fid，避免出现空白行。 */
    fun displayName(dto: QuarkFileDto): String {
        val name = dto.fileName?.let { unescape(it) }?.trim()
        if (!name.isNullOrEmpty()) return name
        return dto.fid ?: "(未命名)"
    }
}
