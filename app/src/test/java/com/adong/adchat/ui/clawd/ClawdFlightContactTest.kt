package com.adong.adchat.ui.clawd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test

class ClawdFlightContactTest {
    private fun pile(): ClawdPileState {
        val json = javaClass.getResourceAsStream("/clawd/clawd_data.json")!!.readBytes().decodeToString()
        val store = object : ClawdPileState.PositionStore {
            override fun load() = mapOf(
                "cowboy" to ClawdPileState.SavedPos(-120, emptyList()),
                "shades" to ClawdPileState.SavedPos(-20, emptyList()),
                "wizard" to ClawdPileState.SavedPos(100, emptyList()),
            )
            override fun save(positions: Map<String, ClawdPileState.SavedPos>) = Unit
        }
        val job = SupervisorJob().apply { cancel() }
        return ClawdPileState(ClawdLoader.loadPileData(json), store, CoroutineScope(job + Dispatchers.Unconfined))
            .also { it.ensureLaid(400) }
    }

    @Test
    fun landingHandoffUsesHeadContactInsteadOfGroundOrPreviousX() {
        val pile = pile()
        pile.pickUp("wizard")
        var count = 0
        var target: String? = null
        var landedX = -1
        pile.throwAt("wizard", 72.0, -40.0, 12.0, 40.0) { _, x, head ->
            assertFalse(pile.isFlying("wizard"))
            count++
            target = head
            landedX = x
        }
        val flight = pile.flightOf("wizard")!!
        flight.bounces = 6
        pile.stepFlights(3f, 56f)
        assertEquals(1, count)
        assertEquals("cowboy", target)
        assertEquals(-14.0, flight.y + pile.actorHeightPx - 56.0, 1e-6)
        assertEquals(flight.x.toInt(), landedX)
        assertTrue(landedX > 72)
        pile.stepFlights(1f, 56f)
        assertEquals(1, count)
    }

    @Test
    fun sidewaysImpactReflectsWithoutChoosingHead() {
        val pile = pile()
        pile.pickUp("wizard")
        var landed = false
        pile.throwAt("wizard", 40.0, 16.0, 40.0, 0.0) { _, _, _ -> landed = true }
        val flight = pile.flightOf("wizard")!!
        pile.stepFlights(1f, 56f)
        assertFalse(landed)
        assertTrue(flight.vx < 0.0)
        assertTrue(flight.x + pile.actorWidthPx <= 80.0 + 1e-6)
    }

    @Test
    fun groundLandingDoesNotInventNearbyHead() {
        val pile = pile()
        pile.pickUp("wizard")
        var landed = false
        var target: String? = "unset"
        pile.throwAt("wizard", 240.0, 0.0, 0.0, 10.0) { _, _, head ->
            landed = true
            target = head
        }
        pile.flightOf("wizard")!!.bounces = 6
        pile.stepFlights(3f, 56f)
        assertTrue(landed)
        assertNull(target)
    }

    @Test
    fun seatedBigRemainsAnElevatedCollisionBody() {
        val pile = pile()
        pile.bigSat("cowboy")
        pile.setBigGrounded(true, 72.0)
        val big = pile.collisionBodies().single { it.name == "big" }.box
        assertEquals(-14.0, big.bottom, 1e-6)
        assertEquals(-41.0, big.top, 1e-6)
        assertEquals(48.0, big.right - big.left, 1e-6)
        assertFalse(pile.collisionBodies(includeBig = false).any { it.name == "big" })
        pile.pickUp("cowboy")
        assertTrue(pile.consumeBigSeatDirty())
    }
}
