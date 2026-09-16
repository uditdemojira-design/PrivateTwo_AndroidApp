package org.privatetwo.app.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.privatetwo.app.core.crypto.CryptoEngine

class PairingManagerTest {

    @Test
    fun testTwoDeviceKeyExchangeAndSasMatch() {
        val deviceAPair = CryptoEngine.generateKeyPair()
        val deviceAId = CryptoEngine.computeDeviceId(deviceAPair.public)

        val deviceBPair = CryptoEngine.generateKeyPair()
        val deviceBId = CryptoEngine.computeDeviceId(deviceBPair.public)

        assertNotEquals("Device IDs must be unique", deviceAId, deviceBId)

        val secretA = CryptoEngine.computeSharedSecret(deviceAPair.private, deviceBPair.public)
        val secretB = CryptoEngine.computeSharedSecret(deviceBPair.private, deviceAPair.public)
        assertEquals(secretA.toList(), secretB.toList())

        val keysA = CryptoEngine.deriveSessionKeys(
            sharedSecret = secretA,
            localPublicKey = deviceAPair.public,
            peerPublicKey = deviceBPair.public,
            isInitiator = true
        )

        val keysB = CryptoEngine.deriveSessionKeys(
            sharedSecret = secretB,
            localPublicKey = deviceBPair.public,
            peerPublicKey = deviceAPair.public,
            isInitiator = false
        )

        assertEquals("Both screens must show identical 8-digit SAS code", keysA.sasCode, keysB.sasCode)
        assertTrue(keysA.sasCode.length == 9 && keysA.sasCode[4] == '-')
    }

    @Test
    fun testThirdDeviceKeyExchangeProducesMismatchSas() {
        val deviceAPair = CryptoEngine.generateKeyPair()
        val deviceBPair = CryptoEngine.generateKeyPair()
        val deviceCPair = CryptoEngine.generateKeyPair()

        val secretLegitimate = CryptoEngine.computeSharedSecret(deviceAPair.private, deviceBPair.public)
        val keysLegitimate = CryptoEngine.deriveSessionKeys(
            sharedSecret = secretLegitimate,
            localPublicKey = deviceAPair.public,
            peerPublicKey = deviceBPair.public,
            isInitiator = true
        )

        val secretMitm = CryptoEngine.computeSharedSecret(deviceAPair.private, deviceCPair.public)
        val keysMitm = CryptoEngine.deriveSessionKeys(
            sharedSecret = secretMitm,
            localPublicKey = deviceAPair.public,
            peerPublicKey = deviceCPair.public,
            isInitiator = true
        )

        assertNotEquals(
            "SAS verification must detect 3rd device / MITM intervention",
            keysLegitimate.sasCode,
            keysMitm.sasCode
        )
    }

    @Test
    fun testPairingPinValidation() {
        fun isValidPin(pin: String): Boolean {
            val sanitized = pin.replace(" ", "").trim()
            return sanitized.length == 6 && sanitized.all { it.isDigit() }
        }

        assertTrue(isValidPin("123456"))
        assertTrue(isValidPin("123 456"))
        assertFalse(isValidPin("12345"))
        assertFalse(isValidPin("1234567"))
        assertFalse(isValidPin("12345A"))
        assertFalse(isValidPin(""))
    }
}
