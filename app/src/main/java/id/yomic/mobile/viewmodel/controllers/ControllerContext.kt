package id.yomic.mobile.viewmodel.controllers

import id.yomic.mobile.di.AppContainer
import id.yomic.mobile.model.AppLanguage
import id.yomic.mobile.model.AppSettingsState
import id.yomic.mobile.model.YomicUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared state/scope bridge injected into the 5 domain controllers owned by YomicViewModel.
 */
internal class ControllerContext(
    val scope: CoroutineScope,
    val state: MutableStateFlow<YomicUiState>,
    val container: AppContainer?
) {
    fun isEn(): Boolean = state.value.settings.appLanguage == AppLanguage.EN

    fun l(idText: String, enText: String): String = if (isEn()) enText else idText

    fun showMessage(message: String) {
        state.update { it.copy(snackbarMessage = message) }
    }

    fun clearMessage() {
        state.update { it.copy(snackbarMessage = null) }
    }

    fun updateSettings(transform: (AppSettingsState) -> AppSettingsState, toast: String? = null) {
        var nextSettings = state.value.settings
        state.update { st ->
            nextSettings = transform(st.settings)
            st.copy(
                settings = nextSettings,
                snackbarMessage = toast ?: st.snackbarMessage
            )
        }
        container?.let { c ->
            scope.launch {
                c.settingsRepository.saveSettings(nextSettings)
            }
        }
    }
}
