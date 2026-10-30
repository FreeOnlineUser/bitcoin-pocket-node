package com.pocketnode.util

import com.pocketnode.util.BinaryExtractor.BitcoinVersion
import org.junit.Assert.assertEquals
import org.junit.Test

class BitcoinVersionTest {

    @Test
    fun retiredSelectionsMoveToTheDefault() {
        for (name in listOf("CORE", "CORE_28_1", "KNOTS", "KNOTS_BIP110", "nonsense")) {
            assertEquals(name, BitcoinVersion.DEFAULT, BitcoinVersion.fromName(name))
        }
        assertEquals(BitcoinVersion.CORE_30, BitcoinVersion.DEFAULT)
    }

    @Test
    fun maintainedSelectionsStay() {
        for (v in BitcoinVersion.entries) assertEquals(v, BitcoinVersion.fromName(v.name))
    }
}
