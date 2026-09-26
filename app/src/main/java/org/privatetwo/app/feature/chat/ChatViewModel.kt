package org.privatetwo.app.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.privatetwo.app.core.signaling.SignalingClient
import org.privatetwo.app.core.signaling.SignalingConnectionState
import org.privatetwo.app.feature.files.FileTransferManager
import java.io.File

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val fileTransferManager: FileTransferManager,
    private val signalingClient: SignalingClient
) : ViewModel() {

    val messages: StateFlow<List<ChatMessage>> = chatRepository.getMessagesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val signalingState: StateFlow<SignalingConnectionState> = signalingClient.connectionState
    val isPeerTyping: StateFlow<Boolean> = chatRepository.isPeerTyping
    val isPeerOnline: StateFlow<Boolean> = chatRepository.isPeerOnline
    val peerLastSeenTimestamp: StateFlow<Long> = chatRepository.peerLastSeenTimestamp
    val partnerDisplayName: StateFlow<String?> = chatRepository.partnerDisplayName
    val partnerAvatarPath: StateFlow<String?> = chatRepository.partnerAvatarPath
    val audioSyncEvent: StateFlow<AudioSyncEvent?> = chatRepository.audioSyncEvent

    fun sendNameExchange() {
        chatRepository.sendNameExchange()
    }

    fun onInputTextChanged(text: String) {
        viewModelScope.launch {
            chatRepository.sendTypingIndicator(text.isNotBlank())
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                chatRepository.sendMessage(text)
            } catch (e: Exception) {
                // Handled in repository / status updated
            }
        }
    }

    fun sendCard(cardJson: String) {
        viewModelScope.launch {
            try {
                chatRepository.sendCard(cardJson)
            } catch (e: Exception) {
                // Handled in repository
            }
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            chatRepository.retryMessage(messageId)
        }
    }

    fun deleteMessage(messageId: String) {
        deleteMessageForMe(messageId)
    }

    fun deleteMessageForMe(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForMe(messageId)
        }
    }

    fun deleteMessageForEveryone(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForEveryone(messageId)
        }
    }

    fun sendAudioSync(action: String, messageId: String?, fileName: String?, positionMs: Int) {
        chatRepository.sendAudioSync(action, messageId, fileName, positionMs)
    }

    fun clearConversation() {
        viewModelScope.launch {
            chatRepository.clearConversation()
        }
    }

    fun sendPhoto(photoFile: File, isViewOnce: Boolean = false) {
        viewModelScope.launch {
            try {
                chatRepository.sendPhoto(photoFile, isViewOnce)
            } catch (e: Exception) {
                // Handled in repository
            }
        }
    }

    fun markViewOnceOpened(messageId: String, localPath: String?) {
        viewModelScope.launch {
            chatRepository.markViewOnceOpened(messageId, localPath)
        }
    }

    fun sendFile(file: File) {
        viewModelScope.launch {
            try {
                chatRepository.sendFile(file)
            } catch (e: Exception) {
                // Handled in repository
            }
        }
    }

    fun sendAudio(audioFile: File) {
        viewModelScope.launch {
            try {
                chatRepository.sendAudio(audioFile)
            } catch (e: Exception) {
                // Handled in repository
            }
        }
    }

    fun markAllIncomingAsRead() {
        viewModelScope.launch {
            chatRepository.markAllIncomingAsRead()
        }
    }

    class Factory(
        private val chatRepository: ChatRepository,
        private val fileTransferManager: FileTransferManager,
        private val signalingClient: SignalingClient
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(chatRepository, fileTransferManager, signalingClient) as T
        }
    }
}
