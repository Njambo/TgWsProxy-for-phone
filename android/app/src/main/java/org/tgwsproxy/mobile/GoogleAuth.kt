package org.tgwsproxy.mobile

import android.app.Activity
import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.security.SecureRandom

/**
 * Вход через Google в одно нажатие (Credential Manager, поток «Sign in with Google»).
 * У приложения нет своего сервера, поэтому полученные имя, почта и фото используются
 * только на этом телефоне; токен Google нигде не отправляется и не проверяется.
 */
object GoogleAuth {
    data class Account(val name: String, val email: String, val photoUrl: String)

    /** Ошибка с понятным пользователю текстом. */
    class AuthException(message: String) : Exception(message)

    suspend fun signIn(activity: Activity, webClientId: String): Account {
        if (webClientId.isBlank()) {
            throw AuthException("Вход через Google ещё не настроен: в приложении не указан Client ID (см. README).")
        }
        val option = GetSignInWithGoogleOption.Builder(webClientId)
            .setNonce(nonce())
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        try {
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                return Account(
                    name = google.displayName.orEmpty(),
                    email = google.id,
                    photoUrl = google.profilePictureUri?.toString().orEmpty(),
                )
            }
            throw AuthException("Google вернул неожиданный ответ.")
        } catch (_: GetCredentialCancellationException) {
            throw AuthException("Вход отменён.")
        } catch (_: NoCredentialException) {
            throw AuthException("На телефоне нет аккаунта Google. Добавьте его в настройках телефона и повторите.")
        } catch (_: GoogleIdTokenParsingException) {
            throw AuthException("Не удалось разобрать ответ Google.")
        } catch (e: GetCredentialException) {
            throw AuthException("Не удалось войти: ${e.message ?: e.type}")
        }
    }

    /** Забывает выбранный аккаунт, чтобы при следующем входе снова показать выбор. */
    suspend fun signOut(context: Context) {
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
    }

    private fun nonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
