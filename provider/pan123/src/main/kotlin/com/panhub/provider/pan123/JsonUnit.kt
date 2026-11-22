package com.panhub.provider.pan123

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 用于「data 字段是 null 或任意对象」的接口。
 * 反序列化时永远返回 Unit，序列化会抛异常（这些接口不会被序列化）。
 */
object JsonUnit : KSerializer<Unit> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("JsonUnit")
    override fun deserialize(decoder: Decoder) {
        // 不消费内容，交给外层 envelope 处理
    }

    override fun serialize(encoder: Encoder, value: Unit) {
        throw UnsupportedOperationException("JsonUnit is deserialize-only")
    }
}
