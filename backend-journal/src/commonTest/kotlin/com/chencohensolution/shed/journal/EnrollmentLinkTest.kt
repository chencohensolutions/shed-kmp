package com.chencohensolution.shed.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnrollmentLinkTest {
    private val fp = "1dc3f04a7cf41efef4a4b12109a1e19da8b5a4b773c24293caa61adf68096f84"

    @Test
    fun readsTheServersLinkWithItsAddresses() {
        val link = findEnrollmentLink(
            "Scan this: journal://enroll?address=https%3A%2F%2F10.0.0.5%3A56398&code=3Q0W-2D5G&fingerprint=${fp.uppercase()}" +
                "&key=af3d&server=https%3A%2F%2Fhome.example%3A56398%2Fquery.",
        )!!
        assertEquals("3Q0W-2D5G", link.code)
        assertEquals(fp, link.fingerprint)
        assertEquals(listOf("https://home.example:56398", "https://10.0.0.5:56398"), link.addresses)
        assertNull(link.key, "not a DHT key")
    }

    @Test
    fun keepsTheServersDhtKey() {
        val key = "79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664"
        val link = parseEnrollmentLink("journal://enroll?code=X&fingerprint=$fp&key=${key.uppercase()}&server=https%3A%2F%2Fh%3A1")!!
        assertEquals(key, link.key)
    }

    @Test
    fun refusesALinkWithoutTheServersKey() {
        assertNull(parseEnrollmentLink("journal://enroll?code=X&server=https%3A%2F%2Fh%3A1"))
        assertNull(parseEnrollmentLink("journal://enroll?code=X&fingerprint=abc&server=https%3A%2F%2Fh%3A1"))
    }

    @Test
    fun refusesOtherLinksAndPlainHttp() {
        assertNull(findEnrollmentLink("https://example.com"))
        assertNull(parseEnrollmentLink("journal://enroll?code=X&fingerprint=$fp&server=http%3A%2F%2Fh%3A1"))
    }

    @Test
    fun bracketsAnIpv6Address() {
        assertEquals("https://[fe80::1]:56398", originOf("https://[fe80::1]:56398/query"))
    }
}
