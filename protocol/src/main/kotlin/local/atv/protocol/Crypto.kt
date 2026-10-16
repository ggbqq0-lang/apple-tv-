/* HAP/Companion adaptation informed by pyatv (MIT); see THIRD_PARTY_NOTICES.md. */
package local.atv.protocol

import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

class ProtocolFailure(val code: String) : Exception(code)
fun ensure(value: Boolean, code: String) { if (!value) throw ProtocolFailure(code) }
fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
fun String.unhex(): ByteArray { ensure(length % 2 == 0, "INVALID_HEX"); return chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
fun BigInteger.unsigned(): ByteArray = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1,it.size) else it }
fun utf8(value: String) = value.toByteArray(Charsets.UTF_8)

object Crypto {
    private val random = SecureRandom()
    fun random(size: Int) = ByteArray(size).also(random::nextBytes)
    fun hash(vararg values: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { values.forEach(::update); digest() }
    fun hkdf(secret: ByteArray, salt: String, info: String): ByteArray = ByteArray(32).also {
        HKDFBytesGenerator(SHA512Digest()).apply { init(HKDFParameters(secret, utf8(salt), utf8(info))) }.generateBytes(it, 0, it.size)
    }
    fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray = byteArrayOf(), encrypt: Boolean): ByteArray {
        ensure(key.size == 32 && nonce.size <= 12, "CRYPTO_PARAMETERS")
        val n = ByteArray(12 - nonce.size) + nonce
        try {
            val cipher = ChaCha20Poly1305()
            cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, n, aad))
            val output = ByteArray(cipher.getOutputSize(input.size))
            val count = cipher.processBytes(input, 0, input.size, output, 0)
            val final = cipher.doFinal(output, count)
            return output.copyOf(count + final)
        } catch (_: Exception) { throw ProtocolFailure("AUTHENTICATION_TAG_INVALID") }
    }
    fun edPublic(seed: ByteArray) = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    fun sign(seed: ByteArray, message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, Ed25519PrivateKeyParameters(seed, 0)); update(message,0,message.size); generateSignature()
    }
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray) {
        val valid = try { Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey,0)); update(message,0,message.size); verifySignature(signature)
        } } catch (_: Exception) { false }
        ensure(valid, "DEVICE_SIGNATURE_INVALID")
    }
    fun xPublic(seed: ByteArray) = X25519PrivateKeyParameters(seed,0).generatePublicKey().encoded
    fun shared(seed: ByteArray, publicKey: ByteArray): ByteArray = ByteArray(32).also {
        try { X25519PrivateKeyParameters(seed,0).generateSecret(X25519PublicKeyParameters(publicKey,0),it,0) }
        catch (_: Exception) { throw ProtocolFailure("DEVICE_EPHEMERAL_KEY_INVALID") }
    }
}

/** BC implements SRP-6a arithmetic; HAP uses its own SHA512 evidence format. */
class HapSrp(pin: String, salt: ByteArray, serverPublic: ByteArray, privateValue: ByteArray = Crypto.random(32)) {
    val publicKey: ByteArray
    val sessionKey: ByteArray
    val proof: ByteArray
    val serverProof: ByteArray
    init {
        val group = SRP6StandardGroups.rfc5054_3072
        val client = object : SRP6Client() { override fun selectPrivateValue() = BigInteger(1,privateValue) }
        client.init(group, SHA512Digest(), SecureRandom())
        val a = client.generateClientCredentials(salt,utf8("Pair-Setup"),utf8(pin))
        val b = BigInteger(1,serverPublic)
        ensure(b.mod(group.n) != BigInteger.ZERO, "SRP_PUBLIC_KEY_INVALID")
        val secret = client.calculateSecret(b)
        publicKey = a.unsigned()
        sessionKey = Crypto.hash(secret.unsigned())
        val nHash = Crypto.hash(group.n.unsigned())
        val gHash = Crypto.hash(group.g.unsigned())
        val xor = ByteArray(64) { (nHash[it].toInt() xor gHash[it].toInt()).toByte() }
        proof = Crypto.hash(xor, Crypto.hash(utf8("Pair-Setup")), salt, publicKey, b.unsigned(), sessionKey)
        serverProof = Crypto.hash(publicKey,proof,sessionKey)
    }
    fun verifyServer(proof: ByteArray) { ensure(MessageDigest.isEqual(proof, serverProof), "SRP_SERVER_PROOF_INVALID") }
}

class Credentials(val publicKey: ByteArray, val privateKey: ByteArray, val accessoryId: ByteArray, val clientId: ByteArray) {
    init { ensure(publicKey.size == 32 && privateKey.size == 32 && accessoryId.isNotEmpty() && clientId.isNotEmpty(), "INVALID_CREDENTIALS") }
    fun encode() = Tlv.encode(linkedMapOf(1 to publicKey,2 to privateKey,3 to accessoryId,4 to clientId))
    override fun toString() = "Credentials([redacted])"
    companion object { fun decode(bytes: ByteArray): Credentials = Tlv.decode(bytes).let { Credentials(it.required(1),it.required(2),it.required(3),it.required(4)) } }
}
fun Map<Int,ByteArray>.required(key: Int) = this[key] ?: throw ProtocolFailure("AUTH_FIELD_MISSING")

object Tlv {
    fun encode(values: Map<Int,ByteArray>): ByteArray = java.io.ByteArrayOutputStream().run {
        values.forEach { (tag,value) ->
            if (value.isEmpty()) { write(tag); write(0) }
            else for (start in value.indices step 255) { val n = minOf(255,value.size-start); write(tag); write(n); write(value,start,n) }
        }; toByteArray()
    }
    fun decode(data: ByteArray): Map<Int,ByteArray> {
        val result = linkedMapOf<Int,ByteArray>(); var index = 0
        while (index < data.size) {
            ensure(index + 2 <= data.size, "TLV_TRUNCATED")
            val key = data[index++].toInt() and 255; val size = data[index++].toInt() and 255
            ensure(index + size <= data.size, "TLV_TRUNCATED")
            result[key] = (result[key] ?: byteArrayOf()) + data.copyOfRange(index,index+size); index += size
        }; return result
    }
}
