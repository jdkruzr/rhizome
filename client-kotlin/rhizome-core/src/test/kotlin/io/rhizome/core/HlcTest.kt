package io.rhizome.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {

    /** A mutable injectable wall clock. */
    private class Wall(var now: Long) {
        val fn: () -> Long = { now }
    }

    @Test
    fun localTracksWallWhenAdvancing() {
        val wall = Wall(1000)
        val hlc = Hlc(wallClock = wall.fn)
        assertEquals(1000, hlc.localEvent())
        wall.now = 2000
        assertEquals(2000, hlc.localEvent(), "tracks wall when it advances past last")
    }

    @Test
    fun localTicksWhenSameMillisecond() {
        val wall = Wall(1000)
        val hlc = Hlc(wallClock = wall.fn)
        assertEquals(1000, hlc.localEvent())
        assertEquals(1001, hlc.localEvent(), "same ms ⇒ +1 logical tick")
        assertEquals(1002, hlc.localEvent())
    }

    @Test
    fun localIsMonotonicWhenClockGoesBackward() {
        val wall = Wall(5000)
        val hlc = Hlc(wallClock = wall.fn)
        assertEquals(5000, hlc.localEvent())
        wall.now = 3000 // clock jumps backward
        assertEquals(5001, hlc.localEvent(), "never goes backward; ticks past last")
    }

    @Test
    fun receiveJumpsPastRemote() {
        val wall = Wall(1000)
        val hlc = Hlc(wallClock = wall.fn)
        hlc.localEvent() // last = 1000
        assertEquals(5001, hlc.receiveEvent(5000), "absorbs a remote op from the future, strictly past it")
        assertEquals(5002, hlc.localEvent(), "subsequent local op sorts after the absorbed remote")
    }

    @Test
    fun receiveTracksWallWhenItLeadsBoth() {
        val wall = Wall(9000)
        val hlc = Hlc(last = 1000, wallClock = wall.fn)
        assertEquals(9000, hlc.receiveEvent(2000), "wall ahead of both last and remote wins")
    }

    @Test
    fun seededLastIsHonored() {
        val wall = Wall(1000)
        val hlc = Hlc(last = 8000, wallClock = wall.fn) // e.g. seeded from max existing op_ts
        assertEquals(8001, hlc.localEvent(), "monotonic across the seed even though wall is behind")
    }

    @Test
    fun causalInversionIsPrevented() {
        // Device A has a fast clock; device B's clock is slow. B refines A's edit AFTER seeing it.
        val aWall = Wall(10_000)
        val bWall = Wall(3_000) // slow
        val a = Hlc(wallClock = aWall.fn)
        val b = Hlc(wallClock = bWall.fn)

        val tA = a.localEvent()          // A authors at its (fast) wall time
        b.receiveEvent(tA)               // B sees A's op
        val tB = b.localEvent()          // B refines it — causally later

        assertTrue(tB > tA, "B's causally-later edit must sort after A's despite B's slow clock ($tB > $tA)")
    }

    @Test
    fun legacyRawWallTsInteroperates() {
        // A legacy op carries a raw wall_ts (plain ms, no counter). Absorbing it works identically
        // to absorbing any HLC value — no special path, no flag.
        val wall = Wall(1_700_000_000_000) // ~ms since epoch
        val hlc = Hlc(wallClock = wall.fn)
        val legacyWallTs = 1_700_000_000_005L
        assertEquals(1_700_000_000_006L, hlc.receiveEvent(legacyWallTs), "legacy ms value is just another op_ts")
    }
}
