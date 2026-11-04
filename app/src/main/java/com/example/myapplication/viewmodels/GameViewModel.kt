package com.example.myapplication.viewmodels

import androidx.lifecycle.ViewModel
import com.example.myapplication.domain.engine.DominoEngine
import com.example.myapplication.domain.models.Board
import com.example.myapplication.domain.models.GameState
import com.example.myapplication.domain.models.Tile
import com.example.myapplication.repositories.RoomRepository
import com.example.myapplication.views.components.PowerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

data class GameUiState(
    val gameState: GameState? = null,
    val selectedTile: Tile? = null,
    val activePower: PowerType? = null,
    val activeEventMessage: String? = null,
    val errorMessage: String? = null
)

class GameViewModel : ViewModel() {
    private val engine = DominoEngine()
    private val repository = RoomRepository()
    private var activeRoomId: String = "local-room"

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    fun startNewGame(
        roomId: String = "local-room",
        p1Id: String = "player1",
        p1Name: String = "Jugador 1",
        p2Id: String = "player2",
        p2Name: String = "Jugador 2",
        isHost: Boolean = true
    ) {
        activeRoomId = roomId

        if (isHost || roomId == "local-room") {
            val initialGame = engine.createInitialGame(
                roomId = roomId,
                player1Id = p1Id,
                player1Name = p1Name,
                player2Id = p2Id,
                player2Name = p2Name
            )
            _uiState.value = GameUiState(gameState = initialGame)
            if (roomId != "local-room") {
                repository.saveGameState(roomId, initialGame)
            }
        }

        if (roomId != "local-room") {
            repository.listenToGame(roomId) { syncedGameState ->
                _uiState.value = _uiState.value.copy(gameState = syncedGameState)
            }
        }
    }

    private fun updateAndSyncState(newState: GameState) {
        _uiState.value = _uiState.value.copy(
            gameState = newState,
            selectedTile = null,
            activePower = null,
            errorMessage = null
        )
        if (activeRoomId != "local-room") {
            repository.saveGameState(activeRoomId, newState)
        }
    }

    fun selectTile(tile: Tile) {
        val currentSelected = _uiState.value.selectedTile
        _uiState.value = _uiState.value.copy(
            selectedTile = if (currentSelected == tile) null else tile,
            errorMessage = null
        )
    }

    fun playTile(onLeftSide: Boolean) {
        val state = _uiState.value.gameState ?: return
        val tile = _uiState.value.selectedTile ?: return
        val activePower = _uiState.value.activePower

        val result = if (activePower == PowerType.WILDCARD) {
            val updatedBoard = if (state.board.isEmpty) {
                Board(listOf(tile))
            } else if (onLeftSide) {
                Board(listOf(tile) + state.board.tiles)
            } else {
                Board(state.board.tiles + tile)
            }

            val playerIndex = state.players.indexOfFirst { it.id == state.turnPlayerId }
            if (playerIndex != -1) {
                val player = state.players[playerIndex]
                val updatedHand = player.hand - tile
                val updatedPlayers = state.players.toMutableList().apply {
                    this[playerIndex] = player.copy(hand = updatedHand)
                }
                val nextTurnId = state.players.firstOrNull { it.id != state.turnPlayerId }?.id ?: state.turnPlayerId
                val nextState = state.copy(
                    players = updatedPlayers,
                    board = updatedBoard,
                    turnPlayerId = nextTurnId
                )
                Result.success(engine.checkGameEnd(nextState))
            } else {
                Result.failure(IllegalArgumentException("Jugador no encontrado"))
            }
        } else {
            engine.playTile(state, state.turnPlayerId, tile, onLeftSide)
        }

        if (result.isSuccess) {
            val updatedState = result.getOrThrow()
            updateAndSyncState(updatedState)
            checkAndTriggerEvent()
        } else {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Esa ficha no coincide con el extremo elegido"
            )
        }
    }

    fun activatePower(powerType: PowerType) {
        val state = _uiState.value.gameState ?: return
        if (state.turnPlayerId.isBlank()) return

        when (powerType) {
            PowerType.SKIP -> {
                val result = engine.useSkipPower(state, state.turnPlayerId)
                if (result.isSuccess) {
                    val updatedState = result.getOrThrow()
                    updateAndSyncState(updatedState)
                    _uiState.value = _uiState.value.copy(
                        activeEventMessage = "¡Poder de Salto activado! Conservas tu turno."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "No pudiste activar el poder de Salto"
                    )
                }
            }
            PowerType.WILDCARD -> {
                val current = _uiState.value.activePower
                _uiState.value = _uiState.value.copy(
                    activePower = if (current == PowerType.WILDCARD) null else PowerType.WILDCARD,
                    activeEventMessage = if (current != PowerType.WILDCARD) "¡Comodín activado! Elige una ficha y colócala en cualquier extremo." else null
                )
            }
            PowerType.SWAP -> {
                val opponent = state.players.firstOrNull { it.id != state.turnPlayerId }
                val meIndex = state.players.indexOfFirst { it.id == state.turnPlayerId }
                val oppIndex = state.players.indexOfFirst { it.id != state.turnPlayerId }

                if (opponent != null && meIndex != -1 && oppIndex != -1 && state.players[meIndex].hand.isNotEmpty() && opponent.hand.isNotEmpty()) {
                    val myHand = state.players[meIndex].hand.toMutableList()
                    val oppHand = opponent.hand.toMutableList()

                    val myRandomTile = myHand.removeAt(Random.nextInt(myHand.size))
                    val oppRandomTile = oppHand.removeAt(Random.nextInt(oppHand.size))

                    myHand.add(oppRandomTile)
                    oppHand.add(myRandomTile)

                    val updatedPlayers = state.players.toMutableList().apply {
                        this[meIndex] = this[meIndex].copy(hand = myHand)
                        this[oppIndex] = this[oppIndex].copy(hand = oppHand)
                    }

                    val updatedState = state.copy(players = updatedPlayers)
                    updateAndSyncState(updatedState)
                    _uiState.value = _uiState.value.copy(
                        activeEventMessage = "¡Poder Intercambio! Has cambiado una ficha aleatoria con tu oponente."
                    )
                }
            }
            PowerType.INVERT -> {
                val nextTurnId = state.players.firstOrNull { it.id != state.turnPlayerId }?.id ?: state.turnPlayerId
                val updatedState = state.copy(turnPlayerId = nextTurnId)
                updateAndSyncState(updatedState)
                _uiState.value = _uiState.value.copy(
                    activeEventMessage = "¡Poder Inversión! El orden de turnos ha cambiado."
                )
            }
        }
    }

    fun drawTile() {
        val state = _uiState.value.gameState ?: return
        val result = engine.drawTile(state, state.turnPlayerId)
        if (result.isSuccess) {
            val updatedState = result.getOrThrow()
            updateAndSyncState(updatedState)
        } else {
            _uiState.value = _uiState.value.copy(
                errorMessage = "No se pudo robar ficha del pozo"
            )
        }
    }

    fun passTurn() {
        val state = _uiState.value.gameState ?: return
        val nextTurnId = state.players.firstOrNull { it.id != state.turnPlayerId }?.id ?: state.turnPlayerId
        val nextState = engine.checkGameEnd(state.copy(turnPlayerId = nextTurnId))
        updateAndSyncState(nextState)
    }

    fun dismissEventMessage() {
        _uiState.value = _uiState.value.copy(activeEventMessage = null)
    }

    private fun checkAndTriggerEvent() {
        if (Random.nextFloat() < 0.20f) {
            val events = listOf(
                "¡EVENTO: Tormenta de Fichas! El clima altera las jugadas.",
                "¡EVENTO: Bonificación Caos! Puntos extra en la ronda.",
                "¡EVENTO: Pozo Revuelto! Las fichas restantes se han mezclado."
            )
            val randomMsg = events[Random.nextInt(events.size)]
            _uiState.value = _uiState.value.copy(activeEventMessage = randomMsg)
        }
    }
}
