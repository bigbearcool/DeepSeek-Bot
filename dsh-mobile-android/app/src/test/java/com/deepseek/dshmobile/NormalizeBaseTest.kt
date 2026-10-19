package com.deepseek.dshmobile

import com.deepseek.dshmobile.data.BridgeApi
import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizeBaseTest {
    private val full = "https://dshmb.bigbear.cool:8443/api/mobile-bridge/v1"
    @Test fun appends() = assertEquals(full, BridgeApi.normalizeBase("https://dshmb.bigbear.cool:8443"))
    @Test fun trailingSlash() = assertEquals(full, BridgeApi.normalizeBase(" https://dshmb.bigbear.cool:8443/ "))
    @Test fun alreadyHas() = assertEquals(full, BridgeApi.normalizeBase(full))
    @Test fun alreadyHasSlash() = assertEquals(full, BridgeApi.normalizeBase("$full//"))
    @Test fun idempotent() = assertEquals(full, BridgeApi.normalizeBase(BridgeApi.normalizeBase(full)))
    @Test fun blank() = assertEquals("", BridgeApi.normalizeBase("  "))
}
