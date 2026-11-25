package com.panhub.app.settings

import com.panhub.core.model.SortOrder
import com.panhub.core.model.SortSpec
import com.panhub.core.platform.LayoutName
import com.panhub.core.platform.SettingsRepository
import com.panhub.core.platform.SortOrderName
import com.panhub.feature.browser.FileLayout
import com.panhub.feature.browser.SortSettingsSource

/**
 * 把设置仓库接到浏览页的显示偏好上。
 *
 * 这层适配器只能放在装配处：设置层用的枚举是 [SortOrderName] / [LayoutName]
 * （它不认识 core:model，更不认识界面模块），浏览页用的是 SortSpec / FileLayout。
 * 让两边任何一侧去认识对方的类型，都会把「设置」和「文件」两个概念绑在一起，
 * 以后加一种排序或一种摆法要改两处枚举。
 */
class SettingsSortSource(
    private val settings: SettingsRepository,
) : SortSettingsSource {

    /**
     * 从没选过排序时返回 null 而不是默认值。
     *
     * 「用户明确按名称排」和「用户从来没选过」在界面上看起来一样，但在
     * 这里必须分开：返回默认值会让浏览页以为自己有一个记住的偏好，
     * 以后改默认排序时老用户就再也跟不上新默认了。
     */
    override fun rememberedSort(): SortSpec? {
        val current = settings.settings.value
        if (!current.hasCustomSort) return null
        return SortSpec(order = orderOf(current.sortOrder), descending = current.sortDescending)
    }

    override suspend fun rememberSort(spec: SortSpec) {
        settings.setSort(nameOf(spec.order), spec.descending)
    }

    /**
     * 从没切过布局时返回 null。
     *
     * 与排序同理：默认列表不是「用户选过列表」，界面上都不该显示成已记住。
     */
    override fun rememberedLayout(): FileLayout? = when (settings.settings.value.layout) {
        LayoutName.LIST -> null
        LayoutName.GRID -> FileLayout.GRID
    }

    override suspend fun rememberLayout(layout: FileLayout) {
        settings.setLayout(
            when (layout) {
                FileLayout.LIST -> LayoutName.LIST
                FileLayout.GRID -> LayoutName.GRID
            },
        )
    }

    private fun orderOf(name: SortOrderName): SortOrder = when (name) {
        SortOrderName.NAME -> SortOrder.NAME
        SortOrderName.TIME -> SortOrder.TIME
        SortOrderName.SIZE -> SortOrder.SIZE
    }

    private fun nameOf(order: SortOrder): SortOrderName = when (order) {
        SortOrder.NAME -> SortOrderName.NAME
        SortOrder.TIME -> SortOrderName.TIME
        SortOrder.SIZE -> SortOrderName.SIZE
    }
}
