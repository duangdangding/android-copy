package com.clipditto.app.sync.relay

import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端中继的加密工具：与 PC 端 relay.rs 逐字节对齐，两端必须能互相加解密。
 *
 * - 握手签名：HMAC-SHA256(接入密钥, nonce + deviceId + ts)，小写 hex
 * - 端到端加密：HKDF-SHA256(分组密钥, salt="lscopy-relay-e2e", info=分组ID) → AES-256 密钥；
 *   AES-256-GCM，每条随机 12 字节 nonce，密文为 base64(nonce ‖ ciphertext‖tag)，
 *   元数据 remoteDeviceId/remoteId/timestamp/type 保持明文并作为 AAD 绑定，
 *   篡改元数据会导致解密失败。
 *
 * 注意：不要用互传文件那套 XOR 流加密（SyncCrypto 是 LAN 配对联谊的轻量方案），
 * 防不住服务器端的主动攻击者。
 */
object RelayCrypto {

    private const val TAG = "RelayCrypto"

    /** HKDF 盐（与 PC 端一致，不可改） */
    private val HKDF_SALT = "lscopy-relay-e2e".toByteArray(Charsets.UTF_8)

    private const val GCM_NONCE_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    /** HMAC-SHA256 小写 hex（握手签名用：hmacHex(接入密钥, "$nonce$deviceId$ts")） */
    fun hmacHex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * 派生分组加密密钥：HKDF-SHA256(分组密钥, salt=固定, info=分组ID) → 32 字节 AES 密钥。
     * Android 无内置 HKDF，手工实现 extract + expand（32 字节输出单块即可）：
     *   PRK = HMAC-SHA256(salt, IKM)
     *   T1  = HMAC-SHA256(PRK, info ‖ 0x01)
     */
    fun deriveKey(groupKey: String, groupId: String): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        // extract
        hmac.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        val prk = hmac.doFinal(groupKey.toByteArray(Charsets.UTF_8))
        // expand（L=32 ≤ HashLen，只需 T(1)）
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        hmac.update(groupId.toByteArray(Charsets.UTF_8))
        return hmac.doFinal(byteArrayOf(0x01))
    }

    /** AAD：绑定元数据防篡改（与 PC 端 e2e_aad 的拼接顺序一致） */
    fun aad(deviceId: String, remoteId: Long, timestampMs: Long, wireType: Int): ByteArray =
        "$deviceId:$remoteId:$timestampMs:$wireType".toByteArray(Charsets.UTF_8)

    /** 加密 → base64(nonce ‖ ciphertext‖tag)；失败返回 null（调用方静默丢弃，不降级发明文） */
    fun encrypt(key: ByteArray, aad: ByteArray, msg: ByteArray): String? = runCatching {
        val nonce = ByteArray(GCM_NONCE_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce)
        )
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(msg)
        Base64.getEncoder().encode(nonce + ct)
    }.getOrElse {
        Log.w(TAG, "加密失败：${it.message}")
        null
    }.let { bytes -> bytes?.let { String(it, Charsets.US_ASCII) } }

    /** 解密 base64(nonce ‖ ciphertext‖tag)；密钥不对/AAD 被篡改/格式损坏都返回 null */
    fun decrypt(key: ByteArray, aad: ByteArray, data: String): ByteArray? = runCatching {
        val buf = Base64.getDecoder().decode(data.trim())
        if (buf.size <= GCM_NONCE_LEN) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, buf, 0, GCM_NONCE_LEN)
        )
        cipher.updateAAD(aad)
        cipher.doFinal(buf, GCM_NONCE_LEN, buf.size - GCM_NONCE_LEN)
    }.getOrNull()
}
