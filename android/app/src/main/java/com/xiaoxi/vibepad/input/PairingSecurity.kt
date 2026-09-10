package com.xiaoxi.vibepad.input

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("vibepad_pairing", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val clientId: ByteArray
        get() {
            prefs.getString(PREF_CLIENT_ID, null)?.let {
                return Base64.decode(it, Base64.NO_WRAP)
            }
            val id = ByteArray(16).also(SecureRandom()::nextBytes)
            prefs.edit().putString(PREF_CLIENT_ID, Base64.encodeToString(id, Base64.NO_WRAP)).commit()
            return id
        }

    val hasSecret: Boolean get() = keyStore.containsAlias(keyAlias())

    fun saveSecret(secret: ByteArray) {
        val entry = KeyStore.SecretKeyEntry(SecretKeySpec(secret, "HmacSHA256"))
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        keyStore.setEntry(keyAlias(), entry, protection)
    }

    fun hmac(data: ByteArray): ByteArray {
        val key = keyStore.getKey(keyAlias(), null) as? SecretKey
            ?: throw IllegalStateException("No VibePad pairing key")
        return Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(data)
        }
    }

    fun clearSecret() {
        if (keyStore.containsAlias(keyAlias())) keyStore.deleteEntry(keyAlias())
    }

    private fun keyAlias() = "vibepad_pairing_${clientId.toHex()}"

    private companion object {
        const val PREF_CLIENT_ID = "client_id"
    }
}

class PairingExchange(private val clientId: ByteArray) {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        generateKeyPair()
    }
    val clientPublicKey: ByteArray = rawPublicKey(keyPair.public as ECPublicKey)
    private var serverPublicKey: ByteArray? = null
    private var derivedSecret: ByteArray? = null

    fun pairRequest(deviceName: String): ByteArray {
        val name = deviceName.toByteArray(Charsets.UTF_8).take(63).toByteArray()
        return clientId + byteArrayOf(name.size.toByte()) + name + clientPublicKey
    }

    fun acceptOffer(serverPublic: ByteArray): String {
        require(serverPublic.size == 65 && serverPublic[0] == 0x04.toByte())
        serverPublicKey = serverPublic.copyOf()
        val ownPublic = keyPair.public as ECPublicKey
        val point = ECPoint(
            BigInteger(1, serverPublic.copyOfRange(1, 33)),
            BigInteger(1, serverPublic.copyOfRange(33, 65)),
        )
        val remote = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, ownPublic.params))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(remote, true)
            generateSecret()
        }
        val transcript = clientId + clientPublicKey + serverPublic
        val salt = sha256(transcript)
        val secret = hkdf(shared, salt, "VibePad pairing v2".toByteArray(), 32)
        derivedSecret = secret
        val codeBytes = softwareHmac(secret, "VibePad SAS".toByteArray() + transcript)
        val number = ((codeBytes[0].toLong() and 0xff) shl 24 or
            ((codeBytes[1].toLong() and 0xff) shl 16) or
            ((codeBytes[2].toLong() and 0xff) shl 8) or
            (codeBytes[3].toLong() and 0xff)) % 1_000_000
        return "%06d".format(number)
    }

    fun verifyPairAccept(proof: ByteArray): ByteArray? {
        val serverPublic = serverPublicKey ?: return null
        val secret = derivedSecret ?: return null
        val transcript = clientId + clientPublicKey + serverPublic
        val expected = softwareHmac(secret, "pair-accept".toByteArray() + transcript)
        return secret.takeIf { constantTimeEqual(expected, proof) }
    }

    private fun rawPublicKey(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun hkdf(input: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = softwareHmac(salt, input)
        var previous = ByteArray(0)
        val result = ArrayList<Byte>()
        var counter = 1
        while (result.size < length) {
            previous = softwareHmac(prk, previous + info + byteArrayOf(counter.toByte()))
            result.addAll(previous.toList())
            counter++
        }
        return result.take(length).toByteArray()
    }

    private fun softwareHmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun sha256(data: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(data)
}

fun constantTimeEqual(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var difference = 0
    first.indices.forEach { difference = difference or (first[it].toInt() xor second[it].toInt()) }
    return difference == 0
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

