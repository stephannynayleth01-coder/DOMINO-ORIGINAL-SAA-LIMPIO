package com.example.myapplication.viewmodels

import androidx.lifecycle.ViewModel
import com.example.myapplication.data.models.PlayerModel
import com.example.myapplication.data.models.RoomModel
import com.example.myapplication.repositories.RoomRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RoomViewModel : ViewModel() {
    private val repository = RoomRepository()
    private val auth = FirebaseAuth.getInstance()

    private val _model = MutableStateFlow<RoomModel?>(null)
    val model: StateFlow<RoomModel?> = _model.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** uid real del jugador local dentro de la sala. */
    var localPlayerId: String = ""
        private set

    /** true si este dispositivo creó la sala. */
    var isHost: Boolean = false
        private set

    fun createRoom() {
        stopCurrentRoomListener()
        val currentUser = auth.currentUser
        val host = PlayerModel(
            uid = currentUser?.uid ?: "local-host-id",
            email = currentUser?.email.orEmpty(),
            name = currentUser?.displayName.orEmpty()
        )
        val room = repository.createRoom(host)
        localPlayerId = host.uid
        isHost = true
        _model.value = room
        _error.value = null

        repository.listenToRoom(
            roomCode = room.code,
            onUpdate = { updated -> _model.value = updated },
            onError = { message -> _error.value = message }
        )
    }

    fun joinRoomByCode(code: String, onComplete: (Boolean) -> Unit) {
        if (code.isBlank()) {
            _error.value = "Ingresa el código de la sala"
            onComplete(false)
            return
        }

        _isLoading.value = true
        _error.value = null

        stopCurrentRoomListener()
        val currentUser = auth.currentUser
        val guest = PlayerModel(
            uid = currentUser?.uid ?: "local-guest-id",
            email = currentUser?.email.orEmpty(),
            name = currentUser?.displayName.orEmpty()
        )

        repository.joinRoomByCode(code, guest) { room, errorMsg ->
            _isLoading.value = false
            if (room != null) {
                localPlayerId = guest.uid
                isHost = false
                _model.value = room
                repository.listenToRoom(
                    roomCode = room.code,
                    onUpdate = { updated -> _model.value = updated },
                    onError = { message -> _error.value = message }
                )
                onComplete(true)
            } else {
                _error.value = errorMsg ?: "No se pudo ingresar a la sala"
                onComplete(false)
            }
        }
    }

    fun joinOpenRoom(onComplete: (Boolean) -> Unit = {}) {
        _isLoading.value = true
        _error.value = null

        stopCurrentRoomListener()
        val currentUser = auth.currentUser
        val guest = PlayerModel(
            uid = currentUser?.uid ?: "local-guest-id",
            email = currentUser?.email.orEmpty(),
            name = currentUser?.displayName.orEmpty()
        )

        repository.joinOpenRoom(guest) { room, errorMsg ->
            _isLoading.value = false
            if (room != null) {
                localPlayerId = guest.uid
                isHost = false
                _model.value = room
                repository.listenToRoom(
                    roomCode = room.code,
                    onUpdate = { updated -> _model.value = updated },
                    onError = { message -> _error.value = message }
                )
                onComplete(true)
            } else {
                _error.value = errorMsg ?: "No hay salas disponibles"
                onComplete(false)
            }
        }
    }

    /**
     * Recupera una sala tras reiniciar la app. Solo restaura identidad y listener;
     * la navegación a la partida la decide GameActivity según `status`.
     */
    fun resumeRoom(code: String) {
        stopCurrentRoomListener()
        val uid = auth.currentUser?.uid
        repository.getRoom(code) { room ->
            if (room == null) return@getRoom

            isHost = uid != null && room.player1.uid == uid
            localPlayerId = when {
                uid != null && room.player1.uid == uid -> room.player1.uid
                uid != null && room.player2?.uid == uid -> room.player2?.uid.orEmpty()
                else -> ""
            }

            _model.value = room
            _error.value = null
            repository.listenToRoom(
                roomCode = room.code,
                onUpdate = { updated -> _model.value = updated },
                onError = { message -> _error.value = message }
            )
        }
    }

    fun startRoomGame(roomCode: String) {
        val hostId = _model.value?.player1?.uid.orEmpty()
        repository.startRoomGame(roomCode, hostId)
    }

    fun leaveRoom() {
        stopCurrentRoomListener()
        _model.value = null
        _error.value = null
        localPlayerId = ""
        isHost = false
    }

    private fun stopCurrentRoomListener() {
        _model.value?.code?.let(repository::stopListeningToRoom)
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopCurrentRoomListener()
    }
}
