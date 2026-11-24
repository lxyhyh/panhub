package com.panhub.feature.browser

import com.panhub.core.model.SortSpec

/**
 * 浏览页要的排序偏好，只有两件事：记住上次怎么排、把这次的选择存下来。
 *
 * 刻意不直接收 SettingsRepository：那个类还管着主题和仅 Wi-Fi，浏览页碰不到
 * 也不该依赖；而设置层用的排序枚举与列表用的 SortSpec 是两套类型
 * （core:platform 不认识 core:model），中间那一步翻译放在装配处，不放在这里。
 * 窄接口的好处是单测不必搭一个真仓库，设置里多一个开关也不必动这个模块。
 */
interface SortSettingsSource {

    /** 上次用的排序；从没选过时返回 null，界面保持默认。 */
    fun rememberedSort(): SortSpec?

    /** 记住这次选的排序。 */
    suspend fun rememberSort(spec: SortSpec)
}
