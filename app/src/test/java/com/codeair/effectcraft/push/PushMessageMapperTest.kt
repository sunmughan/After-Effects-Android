package com.codeair.effectcraft.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FCM messages must never be silently dropped: every received message maps to
 * a visible notification, with data-payload fields filling gaps and fixed
 * fallbacks covering a fully empty message.
 */
class PushMessageMapperTest {

    @Test
    fun notificationPayloadWinsOverData() {
        val mapped = PushMessageMapper.map(
            title = "Render done",
            body = "Your export finished.",
            data = mapOf("title" to "Data title", "body" to "Data body"),
            collapseKey = null,
        )
        assertEquals("Render done", mapped.title)
        assertEquals("Your export finished.", mapped.body)
    }

    @Test
    fun dataPayloadFillsMissingNotificationFields() {
        val mapped = PushMessageMapper.map(
            title = null,
            body = null,
            data = mapOf("title" to "Data title", "body" to "Data body"),
            collapseKey = null,
        )
        assertEquals("Data title", mapped.title)
        assertEquals("Data body", mapped.body)
    }

    @Test
    fun emptyMessageStillProducesVisibleFallbackNotification() {
        val mapped = PushMessageMapper.map(
            title = null,
            body = null,
            data = emptyMap(),
            collapseKey = null,
        )
        assertEquals(PushMessageMapper.FALLBACK_TITLE, mapped.title)
        assertEquals(PushMessageMapper.FALLBACK_BODY, mapped.body)
        assertTrue(mapped.title.isNotBlank())
        assertTrue(mapped.body.isNotBlank())
    }

    @Test
    fun blankFieldsFallBackInsteadOfPostingBlankText() {
        val mapped = PushMessageMapper.map(
            title = "   ",
            body = "",
            data = mapOf("body" to "Data body"),
            collapseKey = null,
        )
        assertEquals(PushMessageMapper.FALLBACK_TITLE, mapped.title)
        assertEquals("Data body", mapped.body)
    }

    @Test
    fun dataTagBeatsCollapseKeyForDedup() {
        val mapped = PushMessageMapper.map(
            title = "Hi",
            body = "Body",
            data = mapOf("tag" to "render-42"),
            collapseKey = "collapse-1",
        )
        assertEquals("render-42", mapped.tag)
    }

    @Test
    fun collapseKeyUsedWhenNoDataTag() {
        val mapped = PushMessageMapper.map(
            title = "Hi",
            body = "Body",
            data = emptyMap(),
            collapseKey = "collapse-1",
        )
        assertEquals("collapse-1", mapped.tag)
    }

    @Test
    fun notificationIdIsStableAndNonNegative() {
        val first = PushMessageMapper.map("Hi", "Body", mapOf("tag" to "t-1"), null)
        val second = PushMessageMapper.map("Other", "Text", mapOf("tag" to "t-1"), null)
        assertEquals(first.notificationId, second.notificationId)
        assertTrue(first.notificationId >= 0)
    }

    @Test
    fun channelIsTheEffectCraftUpdatesChannel() {
        assertEquals("effectcraft_updates", PushMessageMapper.CHANNEL_ID)
    }
}
