package com.panhub.feature.browser

import com.panhub.core.model.SortSpec

/**
 * 浏览页要的显示偏好：记住上次怎么排、列表还是格子，以及把这次的选择存下来。
 *
 * 刻意不直接收 SettingsRepository：那个类还管着主题和仅 Wi-Fi，浏览页碰不到
 * 也不该依赖；而设置层用的枚举与界面用的类型是两套（core:platform 不认识
 * core:model，更不认识界面模块），中间那一步翻译放在装配处，不放在这里。
 * 窄接口的好处是单测不必搭一个真仓库，设置里多一个开关也不必动这个模块。
 */
interface SortSettingsSource {

    /** 上次用的排序；从没选过时返回 null，界面保持默认。 */
    fun rememberedSort(): SortSpec?

    /** 记住这次选的排序。 */
    suspend fun rememberSort(spec: SortSpec)

    /**
     * 上次用的摆放方式；从没切过时返回 null，界面保持列表。
     *
     * 返回 null 而不是 LIST，理由与 [rememberedSort] 一样：
     * 「用户明确选了列表」和「用户从来没切过」在界面上看起来一样，
     * 但后者不该被当成一个记住的偏好——以后改默认布局时它就跟不上了。
     */
    fun rememberedLayout(): FileLayout?

    /** 记住这次选的摆放方式。 */
    suspend fun rememberLayout(layout: FileLayout)
}
