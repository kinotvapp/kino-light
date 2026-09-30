package com.arkiv.player.data.plugin

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cross-language proof (spec 2026-09-29-plugin-sealed-secrets-design.md §8): a seal made by the
 * Node kit's `seal.mjs` -- the tool a plugin author actually runs -- opens here with Kotlin's own
 * [SealedSecrets], using the RFC 7748 test key pair both sides know
 * ([TestSealing.TEST_PUBLIC]/[TestSealing.TEST_PRIVATE]).
 *
 * The fixture was produced with:
 *   KINO_SEAL_PUBLIC_KEY=<TestSealing.TEST_PUBLIC hex> node plugins/sdk/seal.mjs \
 *     --repo owner/repo --name apiKey <<< 'fixture-value'
 * and committed as-is; nothing here regenerates it, so a real regression in either side's crypto
 * (not just a matched pair of bugs) fails this test.
 */
class SealedSecretsCrossLanguageTest {
    @Test fun `seal-mjs's seal opens with Kotlin's SealedSecrets, for the test key pair`() {
        val fixture = JSONObject(File("src/test/resources/plugin/sealed/fixture.json").readText())
        val binding = fixture.getString("binding")
        val name = fixture.getString("name")
        val value = fixture.getString("value")
        val seal = fixture.getString("seal")

        assertEquals(value, SealedSecrets.open(seal, binding, name, TestSealing.agreement, TestSealing.TEST_PUBLIC))
    }

    @Test fun `the fixture's seal rejects a wrong binding or name, and the production key`() {
        val fixture = JSONObject(File("src/test/resources/plugin/sealed/fixture.json").readText())
        val binding = fixture.getString("binding")
        val name = fixture.getString("name")
        val seal = fixture.getString("seal")

        org.junit.Assert.assertThrows(SealException::class.java) {
            SealedSecrets.open(seal, "other/repo", name, TestSealing.agreement, TestSealing.TEST_PUBLIC)
        }
        org.junit.Assert.assertThrows(SealException::class.java) {
            SealedSecrets.open(seal, binding, "otherName", TestSealing.agreement, TestSealing.TEST_PUBLIC)
        }
        // Sealed for the TEST key pair: the production agreement (a different private key) must not open it.
        org.junit.Assert.assertThrows(Exception::class.java) {
            SealedSecrets.open(seal, binding, name, TestSealing.agreement, SealedSecrets.KINO_PUBLIC_KEY_V1)
        }
    }
}
