package id.yomic.mobile.data.service

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Biometric & Device Credential (PIN/Pattern/Password) App Lock helper backed by AndroidX BiometricPrompt.
 */
object SecurityController {

    private val allowedAuthenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun canAuthenticate(context: Context): Boolean {
        val bm = BiometricManager.from(context)
        val result = bm.canAuthenticate(allowedAuthenticators)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            // If device has no screen lock / biometric enrolled, let user unlock instead of locking them out forever
            onSuccess()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFailure(errString.toString())
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val infoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            infoBuilder.setAllowedAuthenticators(allowedAuthenticators)
        } else {
            @Suppress("DEPRECATION")
            infoBuilder.setDeviceCredentialAllowed(true)
        }

        runCatching {
            prompt.authenticate(infoBuilder.build())
        }.onFailure { err ->
            onFailure(err.message ?: "Autentikasi gagal")
        }
    }
}
