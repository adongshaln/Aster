package com.adong.adchat.ui.clawd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClawdCollisionTest {

    private fun solid(name: String, l: Double, t: Double, r: Double, b: Double) =
        ClawdCollision.Solid(name, ClawdCollision.Box(l, t, r, b))

    private val unit = ClawdCollision.Box(0.0, 0.0, 10.0, 10.0)

    @Test
    fun descendingPicksTopLedgeBeforeFloor() {
        val solids = listOf(
            solid("floor", 0.0, 20.0, 10.0, 30.0),
            solid("ledge", 0.0, 12.0, 10.0, 14.0),
        )
        val hit = ClawdCollision.firstHit(unit, 0.0, 25.0, solids)
        assertEquals("ledge", hit!!.solid.name)
        assertEquals(0.08, hit.time, 1e-6)
        assertEquals(0, hit.normalX)
        assertEquals(-1, hit.normalY)
    }

    @Test
    fun horizontalSideHit() {
        val hit = ClawdCollision.firstHit(unit, 5.0, 0.0, listOf(solid("wall", 15.0, 0.0, 20.0, 10.0)))
        assertEquals("wall", hit!!.solid.name)
        assertEquals(1.0, hit.time, 1e-6)
        assertEquals(-1, hit.normalX)
        assertEquals(0, hit.normalY)
    }

    @Test
    fun upwardHitsUnderside() {
        val mover = ClawdCollision.Box(0.0, 20.0, 10.0, 30.0)
        val hit = ClawdCollision.firstHit(mover, 0.0, -10.0, listOf(solid("ceiling", 0.0, 10.0, 10.0, 12.0)))
        assertEquals("ceiling", hit!!.solid.name)
        assertEquals(0.8, hit.time, 1e-6)
        assertEquals(0, hit.normalX)
        assertEquals(1, hit.normalY)
    }

    @Test
    fun highSpeedCrossingThinPlatform() {
        val solids = listOf(
            solid("below", 0.0, 50.0, 10.0, 52.0),
            solid("platform", 0.0, 12.0, 10.0, 14.0),
        )
        val hit = ClawdCollision.firstHit(unit, 0.0, 100.0, solids)
        assertEquals("platform", hit!!.solid.name)
        assertEquals(0.02, hit.time, 1e-6)
        assertEquals(-1, hit.normalY)
    }

    @Test
    fun nearestSolidWinsRegardlessOfListOrder() {
        val solids = listOf(
            solid("far", 30.0, 0.0, 40.0, 10.0),
            solid("near", 15.0, 0.0, 25.0, 10.0),
        )
        val hit = ClawdCollision.firstHit(unit, 25.0, 0.0, solids)
        assertEquals("near", hit!!.solid.name)
        assertEquals(0.2, hit.time, 1e-6)
    }

    @Test
    fun stationarySeparatedNoHit() {
        assertEquals(1.0, unit.translated(1.0, 0.0).left, 1e-9)
        assertNull(ClawdCollision.firstHit(unit, 0.0, 0.0, listOf(solid("below", 0.0, 20.0, 10.0, 30.0))))
    }

    @Test
    fun touchingMovingAwayNoHit() {
        val hit = ClawdCollision.firstHit(unit, 0.0, -5.0, listOf(solid("ground", 0.0, 10.0, 10.0, 20.0)))
        assertNull(hit)
    }

    @Test
    fun slidingParallelAlongTouchingTopNoHit() {
        val hit = ClawdCollision.firstHit(unit, 5.0, 0.0, listOf(solid("ground", 0.0, 10.0, 20.0, 20.0)))
        assertNull(hit)
    }

    @Test
    fun initialOverlapPushesOutNearestFace() {
        val mover = ClawdCollision.Box(0.0, 8.0, 10.0, 18.0)
        val hit = ClawdCollision.firstHit(mover, 0.0, 0.0, listOf(solid("ground", 0.0, 10.0, 10.0, 30.0)))
        assertEquals("ground", hit!!.solid.name)
        assertEquals(0.0, hit.time, 1e-9)
        assertEquals(0, hit.normalX)
        assertEquals(-1, hit.normalY)
        assertEquals(0.0, hit.pushX, 1e-9)
        assertEquals(-8.0, hit.pushY, 1e-9)
    }

    @Test
    fun touchingMovingInwardFromTopHitsAtTimeZero() {
        val hit = ClawdCollision.firstHit(unit, 0.0, 5.0, listOf(solid("floor", 0.0, 10.0, 10.0, 20.0)))
        assertEquals("floor", hit!!.solid.name)
        assertEquals(0.0, hit.time, 1e-9)
        assertEquals(0, hit.normalX)
        assertEquals(-1, hit.normalY)
    }

    @Test
    fun touchingMovingInwardFromLeftHitsAtTimeZero() {
        val hit = ClawdCollision.firstHit(unit, -5.0, 0.0, listOf(solid("wall", -10.0, 0.0, 0.0, 10.0)))
        assertEquals("wall", hit!!.solid.name)
        assertEquals(0.0, hit.time, 1e-9)
        assertEquals(1, hit.normalX)
        assertEquals(0, hit.normalY)
    }

    @Test
    fun containedBoxEscapesThroughNarrowestGapNotWidestIntersection() {
        val mover = ClawdCollision.Box(0.0, 0.0, 10.0, 10.0)
        val hit = ClawdCollision.firstHit(mover, 0.0, 0.0, listOf(solid("wide", -100.0, -2.0, 100.0, 8.0)))
        assertEquals("wide", hit!!.solid.name)
        assertEquals(0, hit.normalX)
        assertEquals(1, hit.normalY)
        assertEquals(8.0, hit.pushY, 1e-9)
        val resolved = mover.translated(hit.pushX, hit.pushY)
        assertEquals(8.0, resolved.top, 1e-9)
    }
}
