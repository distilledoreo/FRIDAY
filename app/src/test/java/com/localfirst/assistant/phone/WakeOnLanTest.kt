package com.localfirst.assistant.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeOnLanTest {
    @Test
    fun parsesCommonMacStyles() {
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        assertEquals(expected.toList(), WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF")!!.toList())
        assertEquals(expected.toList(), WakeOnLan.parseMac("aa-bb-cc-dd-ee-ff")!!.toList())
        assertEquals(expected.toList(), WakeOnLan.parseMac("aabbccddeeff")!!.toList())
    }

    @Test
    fun rejectsBadMacs() {
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLan.parseMac("ZZ:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.parseMac("AA:BB:CC:DD:EE:FF:00"))
    }

    @Test
    fun magicPacketIsHeaderPlusSixteenCopies() {
        val mac = WakeOnLan.parseMac("01:02:03:04:05:06")!!
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertEquals(List(6) { 0xFF.toByte() }, packet.take(6))
        for (repeat in 0 until 16) {
            assertEquals(mac.toList(), packet.drop(6 + repeat * 6).take(6))
        }
    }
}
