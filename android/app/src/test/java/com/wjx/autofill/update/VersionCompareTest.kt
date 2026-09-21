package com.wjx.autofill.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较：应用内更新用它判断 GitHub Release 的 tag 是否比当前版本新。
 * 规则：只有 x.y.z（可带 v 前缀）三段纯数字才可比；不可比一律返回 -1（当作「不是更新」）。
 */
class VersionCompareTest {

    @Test
    fun patchUpgradeIsNewer() {
        assertEquals(1, VersionCompare.compare("1.0.1", "1.0.0"))
        assertTrue(VersionCompare.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun olderAndEqualVersions() {
        assertEquals(-1, VersionCompare.compare("1.0.0", "1.0.1"))
        assertEquals(0, VersionCompare.compare("1.0.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionCompare.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun handlesVPrefix() {
        assertEquals(0, VersionCompare.compare("v1.2.3", "1.2.3"))
        assertEquals(1, VersionCompare.compare("v2.0.0", "v1.9.9"))
        assertEquals(1, VersionCompare.compare("v1.0.1", "1.0.0"))
        assertTrue(VersionCompare.isNewer("v1.0.1", "1.0.0"))
    }

    @Test
    fun comparesNumericallyNotLexicographically() {
        assertEquals(1, VersionCompare.compare("1.10.0", "1.9.0"))
        assertEquals(1, VersionCompare.compare("1.0.10", "1.0.9"))
    }

    @Test
    fun incomparableReturnsMinusOne() {
        assertEquals(-1, VersionCompare.compare("1.0", "1.0.0"))
        assertEquals(-1, VersionCompare.compare("1.0.0", "1.0"))
        assertEquals(-1, VersionCompare.compare("abc", "1.0.0"))
        assertEquals(-1, VersionCompare.compare("1.0.0", ""))
        assertEquals(-1, VersionCompare.compare("1.0.0.0", "1.0.0"))
        assertEquals(-1, VersionCompare.compare("1.0.x", "1.0.0"))
        assertFalse(VersionCompare.isNewer("abc", "1.0.0"))
    }

    @Test
    fun parseAcceptsTrimmedThreeSegmentVersions() {
        assertEquals(listOf(1, 2, 3), VersionCompare.parse("1.2.3"))
        assertEquals(listOf(1, 2, 3), VersionCompare.parse("v1.2.3"))
        assertEquals(listOf(1, 2, 3), VersionCompare.parse(" 1.2.3 "))
        assertNull(VersionCompare.parse("1.2"))
        assertNull(VersionCompare.parse("1.2.3.4"))
        assertNull(VersionCompare.parse(""))
    }
}
