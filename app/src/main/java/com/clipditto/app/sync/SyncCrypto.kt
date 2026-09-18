package com.clipditto.app.sync

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 局域网传输加密：ECDH(secp256r1) 临时密钥交换 + AES/GCM。
 *
 * 每次请求双方各生成一对临时 EC 密钥，通过请求/响应头的 X-Pub-Key 交换公钥，
 * 各自本地算出同一把 AES 密钥；私钥不出设备，每条连接一把新密钥（前向安全）。
 * 可防被动嗅探；不防中间人攻击（局域网配对工具的可接受水平）。
 *
 * 密文格式：12 字节随机 IV 前缀 + AES/GCM 密文（尾部含 16 字节认证标签）。
 * 旧版本服务端不认识 X-Pub-Key 会按明文响应（无 X-Encrypted 头），客户端据此回退。
 */
object SyncCrypto {

    const val HEADER_PUB_KEY = "X-Pub-Key"
    const val HEADER_ENCRYPTED = "X-Encrypted"

    /** 一方持有的临时密钥对；publicB64 为 X.509 编码的公钥，用于放进 HTTP 头 */
    class Ephemeral(val pair: KeyPair) {
        val publicB64: String = Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    fun generate(): Ephemeral {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return Ephemeral(kpg.generateKeyPair())
    }

    /** 用己方私钥 + 对方公钥算出共享 AES-256 密钥（双方结果相同） */
    fun deriveKey(self: Ephemeral, peerPublicB64: String): SecretKey {
        val peerPub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peerPublicB64)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(self.pair.private)
        ka.doPhase(peerPub, true)
        // 共享秘密 + 固定盐过 SHA-256，得到 256 位 AES 密钥
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("ClipDitto-LAN-v1".toByteArray(Charsets.UTF_8) + ka.generateSecret())
        return SecretKeySpec(digest, "AES")
    }

    private fun cipher(key: SecretKey, iv: ByteArray, mode: Int): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
        }

    /** 一次性加密（适合 JSON 等小数据）：返回 IV + 密文 */
    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return iv + cipher(key, iv, Cipher.ENCRYPT_MODE).doFinal(plain)
    }

    /** 一次性解密：入参为 IV + 密文 */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, 12)
        return cipher(key, iv, Cipher.DECRYPT_MODE)
            .doFinal(data.copyOfRange(12, data.size))
    }

    /** 流式加密（适合文件）：先写 12 字节 IV，再写密文；会关闭 output */
    fun encryptStream(key: SecretKey, input: InputStream, output: OutputStream) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        output.write(iv)
        CipherOutputStream(output, cipher(key, iv, Cipher.ENCRYPT_MODE)).use { cos ->
            input.copyTo(cos)
        }
    }

    /** 流式解密（适合文件）：先读 12 字节 IV，返回包装后的解密流 */
    fun decryptStream(key: SecretKey, input: InputStream): InputStream {
        val iv = ByteArray(12)
        var off = 0
        while (off < 12) {
            val n = input.read(iv, off, 12 - off)
            if (n < 0) throw EOFException("密文缺少 IV 头")
            off += n
        }
        return CipherInputStream(input, cipher(key, iv, Cipher.DECRYPT_MODE))
    }
}
