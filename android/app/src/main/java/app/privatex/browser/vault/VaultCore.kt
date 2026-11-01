package app.privatex.browser.vault

import com.goterl.lazysodium.LazySodium
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class VaultException(message: String) : Exception(message)

/**
 * Sett's encrypted vault — a faithful Kotlin port of the PRIVACY X
 * suite's VaultX design (docs: the suite's core/vault.dart):
 *
 * - A random 32-byte **VDK** (vault data key) encrypts every record
 *   individually with XChaCha20-Poly1305 (fresh 24-byte nonce per
 *   record, the record id as additional data).
 * - The master password derives a KEK via Argon2id (crypto_pwhash,
 *   opsLimit 3, memLimit 256 MiB) that *wraps* the VDK — the password
 *   never encrypts data directly, so it can be changed by re-wrapping.
 * - An optional **recovery slot**: a random recovery key (shown once)
 *   derives a second KEK wrapping the same VDK.
 * - A verifier record (type "verifier") lets raw-VDK unlock paths
 *   (biometric unwrap) prove the key is right.
 *
 * The file is Sett's own format (version 1); it is design-compatible
 * with, not file-compatible with, the Flutter suite's vault.
 *
 * All vault state lives in this one JSON file. Nothing is transmitted
 * anywhere; there is no cloud anything.
 */
class VaultCore(
    private val sodium: LazySodium,
    private val file: File,
) {
    data class RecordMeta(
        val id: String,
        val type: String,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private data class Slot(val nonce: ByteArray, val wrappedVdk: ByteArray)
    private data class Record(
        val id: String,
        val type: String,
        val nonce: ByteArray,
        val ct: ByteArray,
        val createdAt: Long,
        var updatedAt: Long,
    )

    private var opsLimit: Long = DEFAULT_OPS
    private var memLimit: Long = DEFAULT_MEM
    private var salt: ByteArray = ByteArray(0)
    private var passwordSlot: Slot? = null
    private var recoverySalt: ByteArray? = null
    private var recoverySlot: Slot? = null
    private val records = mutableListOf<Record>()
    private var createdAt: Long = 0

    private var vdk: ByteArray? = null
    val isUnlocked: Boolean get() = vdk != null

    fun exists(): Boolean = file.exists()

    // ------------------------------------------------------------- create

    /**
     * Creates a new vault. Returns the recovery key (base64url, shown
     * once) when [withRecovery] is true. Throws if a vault exists.
     */
    fun create(masterPassword: CharArray, withRecovery: Boolean = true): String? {
        if (file.exists()) throw VaultException("A vault already exists")
        if (masterPassword.isEmpty()) throw VaultException("Master password must not be empty")
        sodium.sodiumInit()
        createdAt = System.currentTimeMillis()
        salt = sodium.randomBytesBuf(SALT_BYTES)
        val newVdk = sodium.randomBytesBuf(KEY_BYTES)

        val kek = deriveKek(masterPassword, salt)
        passwordSlot = wrapVdk(kek, newVdk)
        sodium.sodiumMemZero(kek, kek.size)

        var recoveryKey: String? = null
        if (withRecovery) {
            val raw = sodium.randomBytesBuf(32)
            recoveryKey = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
            recoverySalt = sodium.randomBytesBuf(SALT_BYTES)
            val rkek = deriveKek(raw.toChars(), recoverySalt!!)
            recoverySlot = wrapVdk(rkek, newVdk)
            sodium.sodiumMemZero(rkek, rkek.size)
            sodium.sodiumMemZero(raw, raw.size)
        }

        vdk = newVdk
        records.clear()
        // Verifier record: how raw-VDK unlock (biometrics) proves the key.
        putRecordInternal(VERIFIER_ID, TYPE_VERIFIER, VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8))
        save()
        loaded = true
        return recoveryKey
    }

    // ------------------------------------------------------------- unlock

    fun unlock(masterPassword: CharArray): Boolean {
        load()
        val kek = deriveKek(masterPassword, salt)
        val unwrapped = try {
            unwrapVdk(kek, passwordSlot ?: throw VaultException("Vault has no password slot"))
        } catch (e: VaultException) {
            sodium.sodiumMemZero(kek, kek.size)
            return false
        }
        sodium.sodiumMemZero(kek, kek.size)
        return acceptVdk(unwrapped)
    }

    fun unlockWithRecoveryKey(recoveryKey: String): Boolean {
        load()
        val slot = recoverySlot ?: return false
        val rSalt = recoverySalt ?: return false
        val raw = try {
            java.util.Base64.getUrlDecoder().decode(recoveryKey.trim())
        } catch (e: IllegalArgumentException) {
            return false
        }
        val rkek = deriveKek(raw.toChars(), rSalt)
        val unwrapped = try {
            unwrapVdk(rkek, slot)
        } catch (e: VaultException) {
            sodium.sodiumMemZero(rkek, rkek.size)
            sodium.sodiumMemZero(raw, raw.size)
            return false
        }
        sodium.sodiumMemZero(rkek, rkek.size)
        sodium.sodiumMemZero(raw, raw.size)
        return acceptVdk(unwrapped)
    }

    /** Raw-VDK unlock (biometric unwrap path). Validates via the verifier record. */
    fun unlockWithVdk(candidate: ByteArray): Boolean {
        load()
        return acceptVdk(candidate.copyOf())
    }

    private fun acceptVdk(candidate: ByteArray): Boolean {
        val verifier = records.firstOrNull { it.id == VERIFIER_ID } ?: run {
            sodium.sodiumMemZero(candidate, candidate.size)
            throw VaultException("Vault is corrupted (no verifier record)")
        }
        val plain = try {
            aeadDecrypt(candidate, verifier.nonce, verifier.ct, verifier.id)
        } catch (e: VaultException) {
            sodium.sodiumMemZero(candidate, candidate.size)
            return false
        }
        if (!plain.contentEquals(VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8))) {
            sodium.sodiumMemZero(candidate, candidate.size)
            return false
        }
        vdk = candidate
        return true
    }

    fun lock() {
        vdk?.let { sodium.sodiumMemZero(it, it.size) }
        vdk = null
    }

    /** While unlocked: a copy of the VDK, for biometric enrolment. Caller zeroes it. */
    fun vdkCopy(): ByteArray {
        checkUnlocked()
        return vdk!!.copyOf()
    }

    // ------------------------------------------------------------- records

    fun listRecords(): List<RecordMeta> {
        checkUnlocked()
        return records.filter { it.type != TYPE_VERIFIER }
            .map { RecordMeta(it.id, it.type, it.createdAt, it.updatedAt) }
    }

    fun readRecord(id: String): ByteArray {
        checkUnlocked()
        val record = records.firstOrNull { it.id == id }
            ?: throw VaultException("No such record")
        return aeadDecrypt(vdk!!, record.nonce, record.ct, record.id)
    }

    fun putRecord(id: String?, type: String, plaintext: ByteArray): String {
        checkUnlocked()
        val realId = putRecordInternal(id ?: UUID.randomUUID().toString(), type, plaintext)
        save()
        return realId
    }

    fun deleteRecord(id: String) {
        checkUnlocked()
        if (id == VERIFIER_ID) throw VaultException("The verifier record cannot be deleted")
        records.removeAll { it.id == id }
        save()
    }

    private fun putRecordInternal(id: String, type: String, plaintext: ByteArray): String {
        val key = vdk ?: throw VaultException("Vault is locked")
        val nonce = sodium.randomBytesBuf(NONCE_BYTES)
        val ct = aeadEncrypt(key, nonce, plaintext, id)
        val now = System.currentTimeMillis()
        val existing = records.firstOrNull { it.id == id }
        if (existing != null) {
            records.remove(existing)
            records.add(Record(id, type, nonce, ct, existing.createdAt, now))
        } else {
            records.add(Record(id, type, nonce, ct, now, now))
        }
        return id
    }

    // ------------------------------------------------------------- password change

    /** Re-wraps the VDK under a new master password (new salt). Vault must be unlocked. */
    fun changeMasterPassword(newPassword: CharArray) {
        checkUnlocked()
        if (newPassword.isEmpty()) throw VaultException("Master password must not be empty")
        salt = sodium.randomBytesBuf(SALT_BYTES)
        val kek = deriveKek(newPassword, salt)
        passwordSlot = wrapVdk(kek, vdk!!)
        sodium.sodiumMemZero(kek, kek.size)
        save()
    }

    // ------------------------------------------------------------- crypto helpers

    private fun deriveKek(password: CharArray, kdfSalt: ByteArray): ByteArray {
        val out = ByteArray(KEY_BYTES)
        val pwBytes = password.toBytes()
        val ok = sodium.cryptoPwHash(
            out, out.size, pwBytes, pwBytes.size, kdfSalt,
            opsLimit, NativeLong(memLimit), PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        sodium.sodiumMemZero(pwBytes, pwBytes.size)
        if (!ok) throw VaultException("Key derivation failed")
        return out
    }

    private fun wrapVdk(kek: ByteArray, vdkToWrap: ByteArray): Slot {
        val nonce = sodium.randomBytesBuf(NONCE_BYTES)
        return Slot(nonce, aeadEncrypt(kek, nonce, vdkToWrap, WRAP_AD))
    }

    private fun unwrapVdk(kek: ByteArray, slot: Slot): ByteArray =
        aeadDecrypt(kek, slot.nonce, slot.wrappedVdk, WRAP_AD)

    private fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, ad: String): ByteArray {
        val adBytes = ad.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(plaintext.size + ABYTES)
        val cipherLen = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            cipher, cipherLen, plaintext, plaintext.size.toLong(),
            adBytes, adBytes.size.toLong(), null, nonce, key,
        )
        if (!ok) throw VaultException("Encryption failed")
        return cipher.copyOf(cipherLen[0].toInt())
    }

    private fun aeadDecrypt(key: ByteArray, nonce: ByteArray, cipher: ByteArray, ad: String): ByteArray {
        val adBytes = ad.toByteArray(Charsets.UTF_8)
        val plain = ByteArray(cipher.size)
        val plainLen = LongArray(1)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plain, plainLen, null, cipher, cipher.size.toLong(),
            adBytes, adBytes.size.toLong(), nonce, key,
        )
        if (!ok) throw VaultException("Incorrect password or corrupted vault")
        return plain.copyOf(plainLen[0].toInt())
    }

    private fun checkUnlocked() {
        if (vdk == null) throw VaultException("Vault is locked")
    }

    // ------------------------------------------------------------- file model

    private fun save() {
        val root = JSONObject()
        root.put("version", 1)
        root.put("createdAt", createdAt)
        root.put(
            "kdf",
            JSONObject()
                .put("opsLimit", opsLimit)
                .put("memLimit", memLimit)
                .put("salt", b64(salt)),
        )
        val slots = JSONObject()
        passwordSlot?.let {
            slots.put("password", JSONObject().put("nonce", b64(it.nonce)).put("wrappedVdk", b64(it.wrappedVdk)))
        }
        if (recoverySlot != null && recoverySalt != null) {
            slots.put(
                "recovery",
                JSONObject()
                    .put("salt", b64(recoverySalt!!))
                    .put("nonce", b64(recoverySlot!!.nonce))
                    .put("wrappedVdk", b64(recoverySlot!!.wrappedVdk)),
            )
        }
        root.put("slots", slots)
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("type", r.type)
                    .put("nonce", b64(r.nonce))
                    .put("ct", b64(r.ct))
                    .put("createdAt", r.createdAt)
                    .put("updatedAt", r.updatedAt),
            )
        }
        root.put("records", arr)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private var loaded = false

    private fun load() {
        if (loaded) return
        if (!file.exists()) throw VaultException("No vault exists")
        val root = JSONObject(file.readText())
        createdAt = root.optLong("createdAt")
        val kdf = root.getJSONObject("kdf")
        opsLimit = kdf.getLong("opsLimit")
        memLimit = kdf.getLong("memLimit")
        salt = unb64(kdf.getString("salt"))
        val slots = root.getJSONObject("slots")
        slots.optJSONObject("password")?.let {
            passwordSlot = Slot(unb64(it.getString("nonce")), unb64(it.getString("wrappedVdk")))
        }
        slots.optJSONObject("recovery")?.let {
            recoverySalt = unb64(it.getString("salt"))
            recoverySlot = Slot(unb64(it.getString("nonce")), unb64(it.getString("wrappedVdk")))
        }
        records.clear()
        val arr = root.getJSONArray("records")
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            records.add(
                Record(
                    r.getString("id"), r.getString("type"),
                    unb64(r.getString("nonce")), unb64(r.getString("ct")),
                    r.getLong("createdAt"), r.getLong("updatedAt"),
                ),
            )
        }
        loaded = true
    }

    // java.util.Base64 (not android.util) so the core runs identically
    // in JVM unit tests against the system libsodium.
    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

    private fun CharArray.toBytes(): ByteArray {
        val bytes = ByteArray(size * 2)
        // UTF-16 code units, low byte first — deterministic; the suite
        // used UTF-8 of the Dart string. Sett's format is its own, so
        // what matters is that create/unlock agree. Chars are wiped by
        // callers; this buffer is wiped in deriveKek.
        for (i in indices) {
            bytes[i * 2] = (this[i].code and 0xFF).toByte()
            bytes[i * 2 + 1] = (this[i].code shr 8).toByte()
        }
        return bytes
    }

    private fun ByteArray.toChars(): CharArray {
        val chars = CharArray(size)
        for (i in indices) chars[i] = (this[i].toInt() and 0xFF).toChar()
        return chars
    }

    companion object {
        const val DEFAULT_OPS = 3L
        const val DEFAULT_MEM = 268435456L
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 24
        const val ABYTES = 16
        const val TYPE_VERIFIER = "verifier"
        const val TYPE_LOGIN = "login"
        private const val VERIFIER_ID = "sett-vault-verifier"
        private const val VERIFIER_PLAINTEXT = "sett-vault-verifier-v1"
        private const val WRAP_AD = "sett-vdk-wrap-v1"
    }
}
