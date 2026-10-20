package com.deepseek.dshmobile

import com.deepseek.dshmobile.util.PairCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairCodeTest {
    @Test fun rawHex() {
        assertEquals("a1b2c3", PairCode.normalize("A1B2C3"))
    }

    @Test fun dshmbScheme() {
        assertEquals("dead01", PairCode.normalize("dshmb:dead01"))
        assertEquals("dead01", PairCode.normalize("dshmb:DEAD01"))
    }

    @Test fun rejectsBad() {
        assertNull(PairCode.normalize("12345"))
        assertNull(PairCode.normalize("dshmb:xyz"))
        assertNull(PairCode.normalize(""))
    }
}

class PairCodeV2Test {
    @Test fun newFormat() {
        val p = PairCode.parse("dshmb:ABC123@https://dshmb.bigbear.cool:8443")!!
        assertEquals("abc123", p.code)
        assertEquals("https://dshmb.bigbear.cool:8443", p.baseUrl)
        assertEquals("abc123", PairCode.normalize("dshmb:abc123@http://10.0.0.2:3081/api/mobile-bridge/v1"))
    }
    @Test fun oldFormats() {
        assertNull(PairCode.parse("dshmb:abc123")!!.baseUrl)
        assertNull(PairCode.parse("abc123")!!.baseUrl)
    }
    @Test fun rejectsNonHttp() {
        assertNull(PairCode.parse("dshmb:abc123@ftp://x"))
        assertNull(PairCode.parse("dshmb:abc123@javascript:alert(1)"))
        assertNull(PairCode.parse("dshmb:abc123@https://"))
        assertNull(PairCode.parse("dshmb:zzzzzz@https://a.b"))
    }
}
