package app.privatex.browser

import app.privatex.browser.vault.PasswordGenerator
import app.privatex.browser.vault.VaultCore
import app.privatex.browser.vault.VaultEntry
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Vault tests run the REAL crypto: lazysodium-java against the system
 * libsodium on this machine — the same native library (different
 * build) that ships inside the Android app via lazysodium-android.
 * Argon2id at production cost (ops 3, 256 MiB) makes each derivation
 * ~1s; the suite stays small on purpose.
 */
class VaultCoreTest {

    private fun newVaultFile(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "sett-vault-test-${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, "vault.json")
    }

    private fun sodium() = LazySodiumJava(SodiumJava())

    @Test
    fun createUnlockCrudAndRecovery() {
        val file = newVaultFile()
        val vault = VaultCore(sodium(), file)
        assertFalse(vault.exists())

        val recovery = vault.create("correct horse battery".toCharArray(), withRecovery = true)
        assertNotNull(recovery)
        assertTrue(vault.exists())
        assertTrue(vault.isUnlocked)

        // Entry round-trip through the encrypted record layer.
        val entry = VaultEntry("", "Example", "talha@example.com", "s3cret!", "https://example.com", "note")
        val id = vault.putRecord(null, VaultCore.TYPE_LOGIN, entry.toBytes())
        val read = VaultEntry.fromBytes(id, vault.readRecord(id))
        assertEquals("Example", read.title)
        assertEquals("talha@example.com", read.username)
        assertEquals("s3cret!", read.password)
        assertEquals(1, vault.listRecords().size)

        // The file on disk must not contain the plaintext password.
        val raw = file.readText()
        assertFalse("vault file leaks plaintext", raw.contains("s3cret"))
        assertFalse("vault file leaks the title", raw.contains("Example"))

        vault.lock()
        assertFalse(vault.isUnlocked)

        // Wrong password fails; right password succeeds (fresh instance = fresh load).
        val vault2 = VaultCore(sodium(), file)
        assertFalse(vault2.unlock("wrong password".toCharArray()))
        assertFalse(vault2.isUnlocked)
        assertTrue(vault2.unlock("correct horse battery".toCharArray()))
        assertEquals(1, vault2.listRecords().size)

        // Update + delete.
        val updated = VaultEntry(id, "Example 2", "talha@example.com", "n3w-pass", "https://example.com", "")
        vault2.putRecord(id, VaultCore.TYPE_LOGIN, updated.toBytes())
        assertEquals("Example 2", VaultEntry.fromBytes(id, vault2.readRecord(id)).title)
        vault2.deleteRecord(id)
        assertEquals(0, vault2.listRecords().size)
        vault2.lock()

        // Recovery key unlocks a fresh instance.
        val vault3 = VaultCore(sodium(), file)
        assertTrue(vault3.unlockWithRecoveryKey(recovery!!))
        assertTrue(vault3.isUnlocked)

        // Password change: old fails, new works.
        vault3.changeMasterPassword("brand new password".toCharArray())
        vault3.lock()
        val vault4 = VaultCore(sodium(), file)
        assertFalse(vault4.unlock("correct horse battery".toCharArray()))
        assertTrue(vault4.unlock("brand new password".toCharArray()))
    }

    @Test
    fun tamperedRecordFailsDecryption() {
        val file = newVaultFile()
        val vault = VaultCore(sodium(), file)
        vault.create("pw12345678".toCharArray(), withRecovery = false)
        val id = vault.putRecord(null, VaultCore.TYPE_LOGIN, "payload".toByteArray())
        vault.lock()
        // Flip a char in the LOGIN record's ciphertext (the LAST "ct" —
        // the verifier record comes first and must stay intact so the
        // unlock itself still succeeds).
        val text = file.readText()
        val marker = "\"ct\":\""
        val start = text.lastIndexOf(marker) + marker.length
        val flipped = text.substring(0, start + 4) +
            (if (text[start + 4] == 'A') 'B' else 'A') +
            text.substring(start + 5)
        file.writeText(flipped)
        val vault2 = VaultCore(sodium(), file)
        assertTrue(vault2.unlock("pw12345678".toCharArray()))
        try {
            vault2.readRecord(id)
            fail("tampered record must not decrypt")
        } catch (expected: Exception) {
            // AEAD authentication failure — as designed.
        }
    }

    @Test
    fun generatorShape() {
        val withSymbols = PasswordGenerator.generate(20, symbols = true)
        assertEquals(20, withSymbols.length)
        val noSymbols = PasswordGenerator.generate(12, symbols = false)
        assertEquals(12, noSymbols.length)
        assertTrue(noSymbols.all { it.isLetterOrDigit() })
        // Guaranteed class coverage (one per class by construction).
        assertTrue(withSymbols.any { it.isLowerCase() })
        assertTrue(withSymbols.any { it.isUpperCase() })
        assertTrue(withSymbols.any { it.isDigit() })
        try {
            PasswordGenerator.generate(4)
            fail("short passwords must be rejected")
        } catch (expected: IllegalArgumentException) {
            // as designed
        }
    }
}
