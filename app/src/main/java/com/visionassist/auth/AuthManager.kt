package com.visionassist.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Manages Firebase Authentication for secure login/signup.
 * Replaces insecure plaintext credentials with Firebase Auth.
 */
class AuthManager {
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Sign up a new user with email and password.
     * @param email User email
     * @param password Secure password (min 6 chars enforced by Firebase)
     * @return Result with user ID or error message
     */
    suspend fun signUp(email: String, password: String): Result<String> = try {
        if (email.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty"))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters"))
        }

        val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
        val userId = result.user?.uid ?: throw Exception("User creation failed")
        Timber.i("User signed up successfully: $userId")
        Result.success(userId)
    } catch (e: FirebaseAuthException) {
        Timber.e("Sign up failed: ${e.message}")
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e("Sign up error: ${e.message}")
        Result.failure(e)
    }

    /**
     * Log in an existing user.
     * @param email User email
     * @param password User password
     * @return Result with user ID or error message
     */
    suspend fun login(email: String, password: String): Result<String> = try {
        if (email.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty"))
        }

        val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
        val userId = result.user?.uid ?: throw Exception("Login failed")
        Timber.i("User logged in successfully: $userId")
        Result.success(userId)
    } catch (e: FirebaseAuthException) {
        Timber.e("Login failed: ${e.message}")
        Result.failure(e)
    } catch (e: Exception) {
        Timber.e("Login error: ${e.message}")
        Result.failure(e)
    }

    /**
     * Log out the current user.
     */
    fun logout() {
        firebaseAuth.signOut()
        Timber.i("User logged out")
    }

    /**
     * Get the currently authenticated user's ID.
     * @return User ID or null if not authenticated
     */
    fun getCurrentUserId(): String? = firebaseAuth.currentUser?.uid

    /**
     * Check if user is currently authenticated.
     * @return true if user is logged in
     */
    fun isAuthenticated(): Boolean = firebaseAuth.currentUser != null

    /**
     * Get current user's email.
     * @return Email or null
     */
    fun getCurrentUserEmail(): String? = firebaseAuth.currentUser?.email
}
