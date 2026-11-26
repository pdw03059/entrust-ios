// 표 잠금 열쇠: 안드로이드 키스토어(가능하면 StrongBox 보안 칩) 안에서 만들고 밖으로 꺼낼 수 없다.
//  - 백업 · 다른 기기로 옮겨지지 않음(키스토어 열쇠는 기기 밖으로 나가지 않음, 앱 백업도 끔)
//  - 지문(강한 생체 인증)으로만 서명, 기기 암호 · 패턴으로는 못 씀. 지문을 새로 등록하면 열쇠가 영구 무효 → 표를 다시 받기
//  아이폰 Sources/DeviceKey.swift(Secure Enclave · biometryCurrentSet)와 같은 원칙.
package kr.co.synapseai.entrust.ui

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kr.co.synapseai.entrust.core.EcP256
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class DeviceKeyException(message: String) : Exception(message)

object DeviceKey {
    private const val ALIAS = "entrust.deviceKey.v1"
    private fun ks() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    val exists: Boolean get() = runCatching { ks().containsAlias(ALIAS) }.getOrDefault(false)

    /** 지문이 등록돼 있고 강한 생체 인증을 쓸 수 있는지. 못 쓰면 사용자에게 보일 이유 */
    fun biometricProblem(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "지문을 먼저 등록해 주세요. 설정 › 보안 › 지문."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "이 폰에서는 지문(강한 생체 인증)을 쓸 수 없어요."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "보안 업데이트가 필요해요."
        else -> "지문 인증을 쓸 수 없어요."
    }

    /** 새 열쇠 만들기(같은 폰의 이전 표 열쇠는 지움). 돌려주는 값 = 공개 열쇠(65바이트) */
    fun create(context: Context): ByteArray {
        biometricProblem(context)?.let { throw DeviceKeyException(it) }
        delete()
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .setUnlockedDeviceRequired(true)
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(-1)
            if (strongBox) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val pub = try {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec(true)) }.generateKeyPair().public
        } catch (e: StrongBoxUnavailableException) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec(false)) }.generateKeyPair().public
        } catch (e: java.security.ProviderException) {
            // 일부 기기는 StrongBox 실패를 ProviderException 으로 알림 → 일반 보안 영역(TEE)으로
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec(false)) }.generateKeyPair().public
        }
        return EcP256.raw65(pub)
    }

    fun publicKey(): ByteArray {
        val cert = ks().getCertificate(ALIAS) ?: throw DeviceKeyException("잠금 열쇠가 없어요. 표를 다시 받아 주세요.")
        return EcP256.raw65(cert.publicKey)
    }

    /** 지문 확인 1번 → 서명(DER). 지문이 바뀌었으면 실패한다. 결과는 화면 스레드에서 돌려준다. */
    fun sign(activity: FragmentActivity, message: ByteArray, title: String, subtitle: String, done: (Result<ByteArray>) -> Unit) {
        val sig = try {
            val key = ks().getKey(ALIAS, null) as? PrivateKey ?: throw DeviceKeyException("잠금 열쇠가 없어요. 표를 다시 받아 주세요.")
            Signature.getInstance("SHA256withECDSA").apply { initSign(key) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            done(Result.failure(DeviceKeyException("지문 등록이 바뀌어 이 폰의 잠금 열쇠가 무효가 됐어요. 표를 다시 받아 주세요."))); return
        } catch (e: DeviceKeyException) {
            done(Result.failure(e)); return
        } catch (e: Exception) {
            done(Result.failure(DeviceKeyException("잠금 열쇠를 쓸 수 없어요: ${e.message}"))); return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val s = result.cryptoObject?.signature
                done(runCatching { s!!.update(message); s.sign() }.recoverCatching { throw DeviceKeyException("서명에 실패했어요: ${it.message}") })
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                done(Result.failure(DeviceKeyException(errString.toString())))
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText("취소")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(sig))
    }

    fun delete() { runCatching { ks().deleteEntry(ALIAS) } }

    /** 열쇠가 StrongBox(별도 보안 칩)에 있는지 — 화면 안내용 */
    fun isStrongBox(): Boolean? = runCatching {
        val key = ks().getKey(ALIAS, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX else null
    }.getOrNull()
}
