package org.privatetwo.app.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow

class PairingViewModel(
    private val pairingManager: PairingManager
) : ViewModel() {

    val uiState: StateFlow<PairingUiState> = pairingManager.uiState
    val connectionState = pairingManager.connectionState

    fun getSignalingUrl(): String = pairingManager.getSignalingUrl()

    fun updateSignalingUrl(url: String) {
        pairingManager.updateSignalingUrl(url)
    }

    fun generatePairingCode() {
        pairingManager.generatePairingCode()
    }

    fun enterPairingCode(code: String) {
        pairingManager.enterPairingCode(code)
    }

    fun confirmSasMatch() {
        pairingManager.confirmSasMatch()
    }

    fun rejectSasMatch() {
        pairingManager.rejectSasMatch()
    }

    fun cancelPairing() {
        pairingManager.cancelPairing()
    }

    fun unpair() {
        pairingManager.unpair()
    }

    class Factory(private val pairingManager: PairingManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return PairingViewModel(pairingManager) as T
        }
    }
}
