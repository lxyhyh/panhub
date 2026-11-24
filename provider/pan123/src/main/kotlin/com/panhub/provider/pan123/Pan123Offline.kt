package com.panhub.provider.pan123

import com.panhub.core.common.PanError
import com.panhub.core.common.PanResult
import com.panhub.core.common.map
import com.panhub.core.model.NodeId
import com.panhub.core.model.OfflineFile
import com.panhub.core.model.OfflineKind
import com.panhub.core.model.OfflineLink
import com.panhub.core.model.OfflineResource
import com.panhub.core.model.OfflineTaskView
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 123 云盘的离线下载。
 *
 * 分两步：先 [resolve] 让服务端解析链接并回一份文件清单，用户勾完之后再 [create] 提交。
 * 接口路径来自 APK 内 interface_config.json，请求体与响应字段照抄网页 bundle
 * （main.bba88759.js），见 docs/recon/pan123.md。
 */
internal class Pan123Offline(
    private val api: Pan123Api,
    private val json: Json = api.json,
) {
    suspend fun resolve(token: String?, link: OfflineLink): PanResult<OfflineResource> {
        val body = buildJsonObject {
            when (link.kind) {
                OfflineKind.LINK -> put("urls", buildJsonArray { add(JsonPrimitive(link.value)) })
                OfflineKind.BT -> put("info_hash", link.value)
            }
        }
        return when (
            val result = api.post(
                path = Pan123Api.PATH_OFFLINE_RESOLVE,
                token = token,
                body = body.toString(),
                decode = { raw ->
                    json.decodeFromString(
                        ApiEnvelope.serializer(OfflineResolveData.serializer()),
                        raw,
                    )
                },
            )
        ) {
            is PanResult.Failure -> result
            is PanResult.Success -> toResource(result.value, link.kind)
        }
    }

    suspend fun create(
        token: String?,
        resource: OfflineResource,
        fileIds: List<String>,
        parentId: NodeId?,
    ): PanResult<Unit> {
        val body = buildJsonObject {
            put("upload_dir", parentId?.value?.toLongOrNull() ?: ROOT_DIR)
            put(
                "resource_list",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("resource_id", resource.id)
                            put(
                                "select_file_id",
                                buildJsonArray { fileIds.forEach { add(JsonPrimitive(it)) } },
                            )
                        },
                    )
                },
            )
        }
        return api.post(
            path = Pan123Api.PATH_OFFLINE_SUBMIT,
            token = token,
            body = body.toString(),
            // 提交的 data 里有什么并不重要，但 JsonUnit 不消费内容会让解析器在 data 处报错，
            // 所以这里老老实实当成一个 JsonElement 收下。
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(JsonElement.serializer()), raw)
            },
        ).map { }
    }

    /**
     * 任务列表。
 *
     * 请求体 {current_page, page_size, status_arr:[0,1,2,3]} 与响应字段
     * （task_id/name/status/size/downloaded/progress/speed）来自 OpenList
     * drivers/123 的 offlineTask；这些键名在 APK 的 libapp.so 里也有
     * （current_page/page_size/status_arr/task_ids 各 1 处），两边互证。
     */
    suspend fun tasks(token: String?): PanResult<List<OfflineTaskView>> {
        val body = buildJsonObject {
            put("current_page", 1)
            put("page_size", 100)
            put("status_arr", buildJsonArray { listOf(0, 1, 2, 3).forEach { add(JsonPrimitive(it)) } })
        }
        return when (
            val result = api.post(
                path = Pan123Api.PATH_OFFLINE_LIST,
                token = token,
                body = body.toString(),
                decode = { raw ->
                    json.decodeFromString(
                        ApiEnvelope.serializer(OfflineTaskListData.serializer()),
                        raw,
                    )
                },
            )
        ) {
            is PanResult.Failure -> result
            is PanResult.Success -> PanResult.Success(
                result.value.list.orEmpty().mapNotNull { it.toView() },
            )
        }
    }

    /** 删除任务：服务端按 task_ids 批量收。 */
    suspend fun delete(token: String?, taskIds: List<String>): PanResult<Unit> {
        if (taskIds.isEmpty()) return PanResult.Success(Unit)
        val body = buildJsonObject {
            put("task_ids", buildJsonArray { taskIds.forEach { add(JsonPrimitive(it.toLongOrNull() ?: 0L)) } })
        }
        return api.post(
            path = Pan123Api.PATH_OFFLINE_DELETE,
            token = token,
            body = body.toString(),
            decode = { raw ->
                json.decodeFromString(ApiEnvelope.serializer(JsonElement.serializer()), raw)
            },
        ).map { }
    }

    private fun OfflineTaskDto.toView(): OfflineTaskView? {
        val id = taskId?.textOrNull() ?: return null
        return OfflineTaskView(
            id = id,
            name = name.orEmpty(),
            status = status ?: 0,
            progress = progress?.toFloat() ?: 0f,
            downloaded = downloaded ?: 0L,
            size = size ?: 0L,
            speed = speed ?: 0L,
        )
    }

    /**
     * 解析结果的判读。
     *
     * 外层 code 是 0 也可能因为链接失效、需要会员等原因在资源这一层报错，
     * 只看外层会把失败当成成功，界面就会显示一个空清单。
     */
    private fun toResource(data: OfflineResolveData, kind: OfflineKind): PanResult<OfflineResource> {
        val item = data.list?.firstOrNull()
            ?: return PanResult.Failure(PanError.unknown("这个链接里没有可保存的内容"))
        val errCode = item.errCode?.textOrNull()
        if (errCode != null && errCode != "0") {
            return PanResult.Failure(PanError.unknown(item.errMsg ?: "资源解析失败（" + errCode + "）"))
        }
        val resourceId = item.id?.textOrNull().orEmpty()
        if (resourceId.isEmpty() || resourceId == "0") {
            return PanResult.Failure(PanError.unknown(item.errMsg ?: "资源解析失败"))
        }
        val files = item.files.orEmpty().mapNotNull { file ->
            val id = file.id?.textOrNull()
            val name = file.name
            if (id.isNullOrEmpty() || name.isNullOrEmpty()) return@mapNotNull null
            OfflineFile(id = id, name = name, size = file.size ?: 0L)
        }
        return PanResult.Success(
            OfflineResource(
                id = resourceId,
                name = item.name?.takeIf { it.isNotBlank() } ?: "离线任务",
                kind = kind,
                files = files,
            ),
        )
    }

    companion object {
        /** 根目录在 123 的接口里就是 0；提交时的 upload_dir 是数字字段。 */
        internal const val ROOT_DIR = 0L

        /** JSON 里的数字在这个接口里都当字符串用：id 有时是数字有时是字符串。 */
        internal fun JsonElement.textOrNull(): String? =
            (this as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
    }
}
