package io.github.eliasmeireles.obdblackbox.domain.video

import io.github.eliasmeireles.obdblackbox.domain.events.RecordedEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBoxPolicyTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private var now = 0L
    private val policy = BlackBoxPolicy(clock = { now })
    private fun minutes(value: Int) = value * 60_000L
    private fun segment(start: Int, end: Int? = start + 5, bytes: Long = 0, labels: Set<String> = emptySet()) =
        VideoSegment("$start", minutes(start), end?.let(::minutes), bytes, labels)
    private fun mark(at: Int, label: String = "marca_1") = RecordedEvent(label, minutes(at), false)

    @Test fun `rotates at five minutes using the fake clock`() {
        now = minutes(5) - 1
        assertFalse(policy.shouldRotate(segment(0, null)))
        now++
        assertTrue(policy.shouldRotate(segment(0, null)))
    }

    @Test fun `middle event protects its complete segment only`() {
        policy.protect(mark(3))
        assertTrue(policy.applyProtection(segment(0)).protected)
        assertFalse(policy.applyProtection(segment(6)).protected)
    }

    @Test fun `event at start protects the previous and current segments`() {
        policy.protect(mark(5))
        assertTrue(policy.applyProtection(segment(0)).protected)
        assertTrue(policy.applyProtection(segment(5)).protected)
        assertFalse(policy.applyProtection(segment(10)).protected)
    }

    @Test fun `event at end protects both sides and inclusive window boundaries`() {
        policy.protect(mark(5))
        assertTrue(policy.applyProtection(segment(0, 3)).protected)
        assertTrue(policy.applyProtection(segment(7, 12)).protected)
        assertFalse(policy.applyProtection(segment(8, 13)).protected)
    }

    @Test fun `near the end protects the active segment and a future segment`() {
        now = minutes(4)
        policy.protect(mark(4))
        assertTrue(policy.applyProtection(segment(0, null)).protected)
        policy.discardExpiredWindows()
        now = minutes(5)
        assertEquals(setOf("marca_1"), policy.applyProtection(segment(5, null)).labels)
        assertFalse(policy.applyProtection(segment(10, 15)).protected)
    }

    @Test fun `overlapping events preserve all labels without duplicates`() {
        policy.protect(mark(4))
        policy.protect(mark(6, "marca_2"))
        val first = policy.applyProtection(segment(0))
        assertEquals(setOf("marca_1", "marca_2"), first.labels)
        assertEquals(first, policy.applyProtection(first))
        assertEquals(first.labels, policy.applyProtection(segment(5)).labels)
    }

    @Test fun `completed protection persists after its window expires`() {
        policy.protect(mark(4))
        val protected = policy.applyProtection(segment(0))
        now = minutes(10)
        policy.discardExpiredWindows()
        assertTrue(policy.applyProtection(protected).protected)
        assertFalse(policy.applyProtection(segment(10, null)).protected)
    }

    @Test fun `manual mark and stall protect but key off and unknown events do not`() {
        assertTrue(BlackBoxPolicy.protects(mark(0)))
        assertTrue(BlackBoxPolicy.protects(RecordedEvent("desligou_sozinho_2", 0, true)))
        assertFalse(BlackBoxPolicy.protects(RecordedEvent("desligado_pela_chave_3", 0, true)))
        assertFalse(BlackBoxPolicy.protects(RecordedEvent("desligado_pela_chave", 0, false)))
        assertFalse(BlackBoxPolicy.protects(RecordedEvent("outro", 0, false)))
        assertFalse(policy.protect(RecordedEvent("desligado_pela_chave_3", 0, true)))
        assertFalse(policy.applyProtection(segment(0)).protected)
    }

    @Test fun `cleanup deletes old unprotected files and preserves protected and active files`() {
        now = minutes(130)
        val oldest = segment(0)
        val protected = segment(1, labels = setOf("marca_1"))
        val active = segment(2, null)
        val retained = segment(10)
        assertEquals(listOf(oldest), policy.cleanup(listOf(active, retained, protected, oldest)))
    }

    @Test fun `size limit deletes oldest first with fake sizes and counts the active file`() {
        val sizePolicy = BlackBoxPolicy(clock = { now }, maxBytes = 100)
        val a = segment(0, bytes = 40)
        val b = segment(5, bytes = 40)
        val current = segment(10, null, bytes = 30)
        val protected = segment(-5, bytes = 500, labels = setOf("marca_1"))
        assertEquals(listOf(a), sizePolicy.cleanup(listOf(b, protected, current, a)))
        assertEquals(listOf(a, b), sizePolicy.cleanup(listOf(a, b, current.copy(bytes = 80))))
    }

    @Test fun `either age or bytes triggers cleanup and exact limits are retained`() {
        now = minutes(120)
        val sizePolicy = BlackBoxPolicy(clock = { now }, maxBytes = 100)
        val exact = segment(0, bytes = 100)
        assertEquals(emptyList<VideoSegment>(), sizePolicy.cleanup(listOf(exact)))
        now++
        assertEquals(listOf(exact), sizePolicy.cleanup(listOf(exact)))
        now = 0
        assertEquals(listOf(exact.copy(bytes = 101)), sizePolicy.cleanup(listOf(exact.copy(bytes = 101))))
    }

    @Test fun `capture dimensions preserve aspect and use even pixels`() {
        assertEquals(CaptureSize(720, 1600), CaptureSize.fromDisplay(720, 1600))
        assertEquals(CaptureSize(1600, 720), CaptureSize.fromDisplay(1600, 720))
        assertEquals(CaptureSize(720, 1600), CaptureSize.fromDisplay(1080, 2400))
        val odd = CaptureSize.fromDisplay(719, 1599)
        assertEquals(0, odd.width % 2)
        assertEquals(0, odd.height % 2)
    }
}
