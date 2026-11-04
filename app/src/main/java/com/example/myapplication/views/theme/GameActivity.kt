package com.example.myapplication.views.theme

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.data.enums.TypeScreen
import com.example.myapplication.data.models.RoomModel
import com.example.myapplication.viewmodels.GameViewModel
import com.example.myapplication.viewmodels.RoomViewModel
import com.example.myapplication.views.screens.GameScreen
import com.example.myapplication.views.screens.HomeScreen
import com.example.myapplication.views.screens.LobbyScreen
import com.example.myapplication.views.screens.RoomSelectionScreen
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.map

class GameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HockeyGameTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var currentScreen by rememberSaveable { mutableStateOf(TypeScreen.HOME) }

                    val roomViewModel: RoomViewModel by viewModels()
                    val gameViewModel: GameViewModel by viewModels()

                    val roomModel by roomViewModel.model.collectAsState()
                    val roomError by roomViewModel.error.collectAsState()

                    // Solo nos interesa saber si ya llegó el estado de la partida,
                    // no cada movimiento: así no recomponemos toda la Activity.
                    val gameLoadedFlow = remember(gameViewModel) {
                        gameViewModel.uiState.map { it.gameState != null }
                    }
                    val gameLoaded by gameLoadedFlow.collectAsState(
                        initial = gameViewModel.uiState.value.gameState != null
                    )

                    val context = LocalContext.current
                    val prefs = remember {
                        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                    }
                    val auth = remember { FirebaseAuth.getInstance() }

                    fun saveRoomSession(roomCode: String) {
                        prefs.edit().putString(KEY_ROOM_CODE, roomCode).apply()
                    }

                    fun clearRoomSession() {
                        prefs.edit().remove(KEY_ROOM_CODE).apply()
                    }

                    fun startMultiplayerGame(room: RoomModel) {
                        val p1 = room.player1
                        val p2 = room.player2
                        val host = roomViewModel.isHost
                        val localId = roomViewModel.localPlayerId.ifBlank {
                            auth.currentUser?.uid
                                ?: if (host) p1.uid else p2?.uid.orEmpty()
                        }

                        gameViewModel.startNewGame(
                            roomId = room.code,
                            p1Id = p1.uid,
                            p1Name = p1.getVisibleName(),
                            p2Id = p2?.uid ?: "player2",
                            p2Name = p2?.getVisibleName() ?: "Jugador 2",
                            isHost = host,
                            localPlayerId = localId
                        )
                    }

                    fun goHome() {
                        gameViewModel.leaveGame()
                        roomViewModel.leaveRoom()
                        clearRoomSession()
                        currentScreen = TypeScreen.HOME
                    }

                    // Recupera una partida en curso tras recrear la Activity o reabrir la app.
                    LaunchedEffect(Unit) {
                        val savedCode = prefs.getString(KEY_ROOM_CODE, null)
                        when {
                            !savedCode.isNullOrBlank() && roomModel == null -> {
                                roomViewModel.resumeRoom(savedCode)
                            }

                            // Si se perdió el proceso jugando en local no hay nada que
                            // recuperar: volvemos al menú en vez de quedar cargando.
                            savedCode.isNullOrBlank() &&
                                currentScreen == TypeScreen.GAME &&
                                !gameLoaded -> {
                                currentScreen = TypeScreen.HOME
                            }
                        }
                    }

                    // Entrada automática del invitado (o reanudación) cuando la sala
                    // pasa a PLAYING. El anfitrión también entra por aquí, así ambos
                    // dispositivos siguen exactamente el mismo camino.
                    // Si se reanuda una sala que aún espera o que terminó, se abre el
                    // lobby para poder continuar o jugar la revancha.
                    LaunchedEffect(roomModel?.status, roomModel?.code, gameLoaded) {
                        val room = roomModel ?: return@LaunchedEffect

                        when (room.status) {
                            "PLAYING" -> {
                                val shouldEnter =
                                    currentScreen == TypeScreen.LOBBY ||
                                        currentScreen == TypeScreen.HOME ||
                                        (currentScreen == TypeScreen.GAME && !gameLoaded)
                                if (shouldEnter) {
                                    saveRoomSession(room.code)
                                    startMultiplayerGame(room)
                                    currentScreen = TypeScreen.GAME
                                }
                            }

                            "WAITING", "FINISHED" -> {
                                if (currentScreen == TypeScreen.HOME) {
                                    currentScreen = TypeScreen.LOBBY
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen) {
                            TypeScreen.HOME -> {
                                HomeScreen(
                                    onClickGame = {
                                        clearRoomSession()
                                        roomViewModel.leaveRoom()
                                        gameViewModel.startNewGame()
                                        currentScreen = TypeScreen.GAME
                                    },
                                    onClickMultiplayer = {
                                        roomViewModel.clearError()
                                        currentScreen = TypeScreen.ROOM_SELECTION
                                    }
                                )
                            }

                            TypeScreen.ROOM_SELECTION -> {
                                RoomSelectionScreen(
                                    onCreateRoomClick = {
                                        roomViewModel.createRoom()
                                        currentScreen = TypeScreen.LOBBY
                                    },
                                    onJoinByCodeClick = { code ->
                                        roomViewModel.joinRoomByCode(code) { success ->
                                            if (success) {
                                                currentScreen = TypeScreen.LOBBY
                                            }
                                        }
                                    },
                                    onJoinOpenRoomClick = {
                                        roomViewModel.joinOpenRoom { success ->
                                            if (success) {
                                                currentScreen = TypeScreen.LOBBY
                                            }
                                        }
                                    },
                                    onBackToHomeClick = {
                                        roomViewModel.leaveRoom()
                                        currentScreen = TypeScreen.HOME
                                    },
                                    errorMessage = roomError
                                )
                            }

                            TypeScreen.LOBBY -> {
                                LobbyScreen(
                                    roomCode = roomModel?.code ?: roomModel?.id ?: "A7K3Q9",
                                    hostName = roomModel?.player1?.getVisibleName() ?: "Anfitrión",
                                    guestName = roomModel?.player2?.getVisibleName(),
                                    isHost = roomViewModel.isHost,
                                    onCancelClick = {
                                        roomViewModel.leaveRoom()
                                        clearRoomSession()
                                        currentScreen = TypeScreen.ROOM_SELECTION
                                    },
                                    onStartGameClick = {
                                        val room = roomModel
                                        val opponent = room?.player2
                                        if (room == null || opponent == null) {
                                            return@LobbyScreen
                                        }
                                        // Solo se cambia el estado de la sala; la entrada
                                        // a la partida la hace el efecto de arriba en ambos
                                        // dispositivos cuando el listener vea PLAYING.
                                        saveRoomSession(room.code)
                                        roomViewModel.startRoomGame(room.code)
                                    }
                                )
                            }

                            TypeScreen.GAME -> {
                                GameScreen(
                                    gameViewModel = gameViewModel,
                                    onBackToHome = { goHome() },
                                    onBackToLobby = {
                                        clearRoomSession()
                                        gameViewModel.leaveGame()
                                        currentScreen = TypeScreen.LOBBY
                                    }
                                )
                            }

                            else -> {
                                HomeScreen(
                                    onClickGame = {
                                        clearRoomSession()
                                        roomViewModel.leaveRoom()
                                        gameViewModel.startNewGame()
                                        currentScreen = TypeScreen.GAME
                                    },
                                    onClickMultiplayer = {
                                        roomViewModel.clearError()
                                        currentScreen = TypeScreen.ROOM_SELECTION
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val SESSION_PREFS = "domino_session"
        private const val KEY_ROOM_CODE = "roomCode"
    }
}
