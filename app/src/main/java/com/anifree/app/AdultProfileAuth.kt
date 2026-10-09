package com.anifree.app

import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

object AdultProfileAuth {
    fun request(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val manager = BiometricManager.from(activity)
        if (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false) // fail closed when no secure screen lock exists
            return
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onResult(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onResult(false) }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Desbloquear perfil Adulto")
            .setSubtitle("Confirme com biometria ou bloqueio de tela")
            .setAllowedAuthenticators(authenticators)
            .build())
    }
}
