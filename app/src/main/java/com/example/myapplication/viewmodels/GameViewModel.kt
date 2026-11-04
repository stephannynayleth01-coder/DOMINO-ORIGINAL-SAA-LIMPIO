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
    val localPlayerId: String = "",
    val isHost: Boolean = false,
    val isMultiplayer: Boolean = false,
    val selectedTile: Tile? = null,
    val activePower: PowerType? = null,
    val activeEventMessage: String? = null,
    val errorMessage: String? = null,
    val isSyncing: Boolean = false
)

class GameViewModel : ViewModel() {
    private val engine = DominoEngine()
    private val repository = RoomRepository()

    private var activeRoomId: String = LOCAL_ROOM_ID
    private var listenedRoomId: String? = null
    private var roomFinishedReported = false
    private var lastStart: StartParams? = null

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private data class StartParams(
        val roomId: String,
        val p1Id: String,
        val p1Name: String,
        val p2Id: String,
        val p2Name: String,
        val isHost: Boolean,
        val localPlayerId: String
    )

    /**
     * Prepara la partida.
     *
     * - `roomId == "local-room"`: partida de un solo dispositivo (sin Firebase).
     * - Anfitrión: registra el listener y crea el estado inicial en `/games/{roomId}`
     *   solo si no existe una partida viva (o si la anterior terminó).
     * - Invitado: solo registra el listener; nunca escribe el estado inicial.
     */
    fun startNewGame(
        roomId: String = LOCAL_ROOM_ID,
        p1Id: String = "player1",
        p1Name: String = "Jugador 1",
        p2Id: String = "player2",
        p2Name: String = "Jugador 2",
        isHost: Boolean = true,
        localPlayerId: String = p1Id
    ) {
        val effectiveLocalId = localPlayerId.ifBlank { if (isHost) p1Id else p2Id }
        val multiplayer = roomId != LOCAL_ROOM_ID
        val params = StartParams(roomId, p1Id, p1Name, p2Id, p2Name, isHost, effectiveLocalId)
        lastStart = params

        val roomChanged = activeRoomId != roomId || listenedRoomId != roomId
        activeRoomId = roomId

        if (roomChanged) {
            roomFinishedReported = false
            _uiState.value = GameUiState(
                localPlayerId = effectiveLocalId,
                isHost = isHost,
                isMultiplayer = multiplayer,
                isSyncing = multiplayer
            )
        } else {
            _uiState.value = _uiState.value.copy(
                localPlayerId = effectiveLocalId,
                isHost = isHost,
                isMultiplayer = multiplayer,
                isSyncing = multiplayer
            )
        }

        if (!multiplayer) {
            _uiState.value = _uiState.value.copy(
                gameState = engine.createInitialGame(
                    roomId = roomId,
                    player1Id = p1Id,
                    player1Name = p1Name,
                    player2Id = p2Id,
                    player2Name = p2Name
                ),
                isSyncing = false
            )
            return
        }

        // El listener se registra antes de escribir para no perder ningún snapshot.
        listenedRoomId = roomId
        repository.listenToGame(
            roomId = roomId,
            onUpdate = ::applyRemoteState,
            onError = { message ->
                _uiState.value = _uiState.value.copy(isSyncing = false, errorMessage = message)
            }
        )

        if (isHost) {
            val initialGame = engine.createInitialGame(
                roomId = roomId,
                player1Id = p1Id,
                player1Name = p1Name,
                player2Id = p2Id,
                player2Name = p2Name
            )
            // Sin estado optimista: la transacción y el listener son la fuente de
            // verdad. Así una partida viva nunca se pisa con la copia inicial.
            repository.startOrReplaceGame(roomId, initialGame) { saved, error ->
                _uiState.value = _uiState.value.copy(isSyncing = false, errorMessage = error)
                if (saved != null) applyRemoteState(saved)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sincronización
    // ---------------------------------------------------------------------

    private fun applyRemoteState(remote: GameState) {
        val current = _uiState.value
        val currentVersion = current.gameState?.version ?: -1L

        // La versión solo crece; un snapshot viejo (eco/offline) no debe retroceder.
        if (remote.version < currentVersion) {
            return
        }

        val localId = current.localPlayerId
        val isMyTurn = remote.turnPlayerId == localId
        val myHand = remote.players.firstOrNull { it.id == localId }?.hand.orEmpty()

        _uiState.value = current.copy(
            gameState = remote,
            selectedTile = current.selectedTile?.takeIf { isMyTurn && it in myHand },
            activePower = current.activePower?.takeIf { isMyTurn && !remote.isFinished },
            isSyncing = false,
            errorMessage = null
        )

        if (remote.isFinished) {
            notifyRoomFinished(remote)
        } else {
            // Una partida nueva (revancha) vuelve a habilitar el aviso de fin.
            roomFinishedReported = false
        }
    }

    private fun notifyRoomFinished(state: GameState) {
        if (!_uiState.value.isMultiplayer || roomFinishedReported) return
        roomFinishedReported = true
        repository.finishRoom(state.roomId.ifBlank { activeRoomId }, state.winnerId)
    }

    private fun submitMove(
        transform: (GameState) -> Result<GameState>,
        onSuccessAction: () -> Unit = {}
    ) {
        val state = _uiState.value.gameState ?: return
        if (!ensureMyTurn(state)) return

        val localId = _uiState.value.localPlayerId

        if (!_uiState.value.isMultiplayer) {
            val result = transform(state)
            if (result.isSuccess) {
                applyRemoteState(result.getOrThrow())
                onSuccessAction()
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = result.exceptionOrNull()?.toReadableMessage() ?: "Movimiento inválido"
                )
            }
            return
        }

        _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null)
        repository.saveGameState(
            roomId = activeRoomId,
            expectedTurnPlayerId = localId,
            transform = transform,
            onSuccess = { saved ->
                applyRemoteState(saved)
                onSuccessAction()
            },
            onError = { message ->
                _uiState.value = _uiState.value.copy(isSyncing = false, errorMessage = message)
            }
        )
    }

    private fun ensureMyTurn(state: GameState): Boolean {
        val current = _uiState.value
        if (current.localPlayerId.isBlank()) {
            _uiState.value = current.copy(errorMessage = "No se pudo identificar a tu jugador")
            return false
        }
        if (state.turnPlayerId != current.localPlayerId) {
            _uiState.value = current.copy(errorMessage = "Espera, es el turno de tu oponente")
            return false
        }
        if (state.isFinished) {
            _uiState.value = current.copy(errorMessage = "La partida ya terminó")
            return false
        }
        return true
    }

    // ---------------------------------------------------------------------
    // Acciones del jugador
    // ---------------------------------------------------------------------

    fun selectTile(tile: Tile) {
        val state = _uiState.value.gameState ?: return
        if (!ensureMyTurn(state)) return

        val currentSelected = _uiState.value.selectedTile
        _uiState.value = _uiState.value.copy(
            selectedTile = if (currentSelected == tile) null else tile,
            errorMessage = null
        )
    }

    fun playTile(onLeftSide: Boolean) {
        val ui = _uiState.value
        val state = ui.gameState ?: return
        val tile = ui.selectedTile ?: return
        if (!ensureMyTurn(state)) return

        val localId = ui.localPlayerId
        val wildcard = ui.activePower == PowerType.WILDCARD

        submitMove(
            transform = { current ->
                if (wildcard) {
                    placeWildcard(current, localId, tile, onLeftSide)
                } else {
                    engine.playTile(current, localId, tile, onLeftSide)
                }
            },
            onSuccessAction = {
                _uiState.value = _uiState.value.copy(selectedTile = null, activePower = null)
                checkAndTriggerEvent()
            }
        )
    }

    /**
     * Comodín: permite colocar cualquier ficha en cualquier extremo, sin validar
     * que coincida con el número del extremo.
     */
    private fun placeWildcard(
        state: GameState,
        playerId: String,
        tile: Tile,
        onLeftSide: Boolean
    ): Result<GameState> {
        if (state.isFinished) {
            return Result.failure(IllegalStateException("La partida ya terminó"))
        }
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("No es tu turno"))
        }

        val playerIndex = state.players.indexOfFirst { it.id == playerId }
        if (playerIndex == -1) {
            return Result.failure(IllegalArgumentException("Jugador no encontrado"))
        }

        val player = state.players[playerIndex]
        if (tile !in player.hand) {
            return Result.failure(IllegalArgumentException("La ficha no está en tu mano"))
        }

        val updatedBoard = when {
            state.board.isEmpty -> Board(listOf(tile))
            onLeftSide -> Board(listOf(tile) + state.board.tiles)
            else -> Board(state.board.tiles + tile)
        }

        val updatedPlayers = state.players.toMutableList().apply {
            this[playerIndex] = player.copy(hand = player.hand - tile)
        }

        val nextTurnId = if (state.skipNextTurnFor == playerId) {
            playerId
        } else {
            state.players.firstOrNull { it.id != playerId }?.id ?: playerId
        }

        return Result.success(
            engine.checkGameEnd(
                state.copy(
                    players = updatedPlayers,
                    board = updatedBoard,
                    turnPlayerId = nextTurnId,
                    skipNextTurnFor = null
                )
            )
        )
    }

    fun activatePower(powerType: PowerType) {
        val ui = _uiState.value
        val state = ui.gameState ?: return
        if (!ensureMyTurn(state)) return

        val localId = ui.localPlayerId

        when (powerType) {
            PowerType.SKIP -> {
                submitMove(
                    transform = { current -> engine.useSkipPower(current, localId) },
                    onSuccessAction = {
                        _uiState.value = _uiState.value.copy(
                            activeEventMessage = "¡Poder de Salto activado! Conservas tu turno."
                        )
                    }
                )
            }

            PowerType.WILDCARD -> {
                val current = ui.activePower
                val next = if (current == PowerType.WILDCARD) null else PowerType.WILDCARD
                _uiState.value = ui.copy(
                    activePower = next,
                    activeEventMessage = if (next != null) {
                        "¡Comodín activado! Elige una ficha y colócala en cualquier extremo."
                    } else {
                        null
                    }
                )
            }

            PowerType.SWAP -> {
                submitMove(
                    transform = { current -> swapRandomTile(current, localId) },
                    onSuccessAction = {
                        _uiState.value = _uiState.value.copy(
                            activeEventMessage = "¡Poder Intercambio! Has cambiado una ficha aleatoria con tu oponente."
                        )
                    }
                )
            }

            PowerType.INVERT -> {
                submitMove(
                    transform = { current -> invertTurn(current, localId) },
                    onSuccessAction = {
                        _uiState.value = _uiState.value.copy(
                            activeEventMessage = "¡Poder Inversión! El orden de turnos ha cambiado."
                        )
                    }
                )
            }
        }
    }

    private fun swapRandomTile(state: GameState, playerId: String): Result<GameState> {
        if (state.isFinished) {
            return Result.failure(IllegalStateException("La partida ya terminó"))
        }
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("No es tu turno"))
        }

        val meIndex = state.players.indexOfFirst { it.id == playerId }
        val oppIndex = state.players.indexOfFirst { it.id != playerId }
        if (meIndex == -1 || oppIndex == -1) {
            return Result.failure(IllegalStateException("Se necesitan dos jugadores"))
        }

        val me = state.players[meIndex]
        val opponent = state.players[oppIndex]
        if (me.hand.isEmpty() || opponent.hand.isEmpty()) {
            return Result.failure(IllegalStateException("Ambos jugadores necesitan fichas"))
        }

        val myHand = me.hand.toMutableList()
        val oppHand = opponent.hand.toMutableList()
        val myTile = myHand.removeAt(Random.nextInt(myHand.size))
        val oppTile = oppHand.removeAt(Random.nextInt(oppHand.size))
        myHand.add(oppTile)
        oppHand.add(myTile)

        val updatedPlayers = state.players.toMutableList().apply {
            this[meIndex] = me.copy(hand = myHand)
            this[oppIndex] = opponent.copy(hand = oppHand)
        }

        return Result.success(state.copy(players = updatedPlayers))
    }

    private fun invertTurn(state: GameState, playerId: String): Result<GameState> {
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("No es tu turno"))
        }
        val nextTurnId = state.players.firstOrNull { it.id != playerId }?.id ?: playerId
        return Result.success(state.copy(turnPlayerId = nextTurnId, skipNextTurnFor = null))
    }

    fun drawTile() {
        val state = _uiState.value.gameState ?: return
        if (!ensureMyTurn(state)) return
        val localId = _uiState.value.localPlayerId
        submitMove(transform = { current -> engine.drawTile(current, localId) })
    }

    fun passTurn() {
        val state = _uiState.value.gameState ?: return
        if (!ensureMyTurn(state)) return
        val localId = _uiState.value.localPlayerId
        submitMove(
            transform = { current ->
                if (current.turnPlayerId != localId) {
                    Result.failure(IllegalStateException("No es tu turno"))
                } else {
                    val nextTurnId = current.players.firstOrNull { it.id != localId }?.id ?: localId
                    Result.success(engine.checkGameEnd(current.copy(turnPlayerId = nextTurnId)))
                }
            }
        )
    }

    fun dismissEventMessage() {
        _uiState.value = _uiState.value.copy(activeEventMessage = null)
    }

    /**
     * Revancha local (un solo dispositivo). En multijugador la pantalla vuelve al
     * lobby y el anfitrión inicia la partida de nuevo.
     */
    fun restartGame() {
        val params = lastStart ?: return
        if (params.roomId != LOCAL_ROOM_ID) return

        _uiState.value = _uiState.value.copy(
            gameState = engine.createInitialGame(
                roomId = params.roomId,
                player1Id = params.p1Id,
                player1Name = params.p1Name,
                player2Id = params.p2Id,
                player2Name = params.p2Name
            ),
            selectedTile = null,
            activePower = null,
            activeEventMessage = null,
            errorMessage = null
        )
    }

    fun leaveGame() {
        listenedRoomId?.let(repository::stopListeningToGame)
        listenedRoomId = null
        activeRoomId = LOCAL_ROOM_ID
        roomFinishedReported = false
        lastStart = null
        _uiState.value = GameUiState()
    }

    override fun onCleared() {
        super.onCleared()
        listenedRoomId?.let(repository::stopListeningToGame)
        listenedRoomId = null
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

    private fun Throwable.toReadableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Movimiento inválido"

    companion object {
        const val LOCAL_ROOM_ID = "local-room"
    }
}
