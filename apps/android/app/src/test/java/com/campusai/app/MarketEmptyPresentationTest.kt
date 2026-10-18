package com.campusai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketEmptyPresentationTest {
    @Test
    fun firstRefreshDoesNotClaimTheRemoteCatalogIsEmpty() {
        val copy = marketEmptyPresentation(refreshing = true, hasSynced = false, hasError = false)

        assertEquals("正在确认心愿墙", copy.title)
        assertEquals("正在确认", copy.status)
    }

    @Test
    fun firstFailureExplainsThatTheEmptyScreenIsNotAConfirmedEmptyCatalog() {
        val copy = marketEmptyPresentation(refreshing = false, hasSynced = false, hasError = true)

        assertEquals("暂时无法确认心愿墙", copy.title)
        assertEquals("读取失败，请重试。", copy.detail)
    }

    @Test
    fun successfulEmptyResultUsesTheRealEmptyState() {
        val copy = marketEmptyPresentation(refreshing = false, hasSynced = true, hasError = false)

        assertEquals("心愿墙还很安静", copy.title)
        assertEquals("暂无心愿；公开发布后需审核。", copy.detail)
    }

    @Test
    fun refreshAndFailureAfterAConfirmedEmptyResultKeepTheirProvenance() {
        val refreshing = marketEmptyPresentation(refreshing = true, hasSynced = true, hasError = false)
        val failed = marketEmptyPresentation(refreshing = false, hasSynced = true, hasError = true)

        assertEquals("上次同步时心愿墙是空的", refreshing.title)
        assertEquals("正在更新", refreshing.status)
        assertEquals("上次同步时心愿墙是空的", failed.title)
        assertEquals("刷新未完成", failed.status)
    }
}
