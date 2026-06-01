package com.visionassist.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sealed classes for navigation FSM states.
 * Provides type-safe, exhaustive state management.
 */
sealed class NavigationState {
    data object Detecting : NavigationState()
    data object Locked : NavigationState()
    data object Guiding : NavigationState()
    data object WaitingForClear : NavigationState()
    data object Clear : NavigationState()
}

/**
 * Sealed classes for authentication states.
 */
sealed class AuthState {
    data object Unauthenticated : AuthState()
    data object Loading : AuthState()
    data class Authenticated(val userId: String, val email: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

/**
 * Sealed classes for app states.
 */
sealed class AppState {
    data object Welcome : AppState()
    data object AskingUsername : AppState()
    data object AskingPassword : AppState()
    data object Camera : AppState()
}

/**
 * Thread-safe state management using Kotlin Flow.
 */
class NavigationStateManager {
    private val _navigationState = MutableStateFlow<NavigationState>(NavigationState.Detecting)
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _appState = MutableStateFlow<AppState>(AppState.Welcome)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    fun setNavigationState(state: NavigationState) {
        _navigationState.value = state
    }

    fun setAuthState(state: AuthState) {
        _authState.value = state
    }

    fun setAppState(state: AppState) {
        _appState.value = state
    }

    fun getCurrentNavigationState(): NavigationState = _navigationState.value
    fun getCurrentAuthState(): AuthState = _authState.value
    fun getCurrentAppState(): AppState = _appState.value
}
