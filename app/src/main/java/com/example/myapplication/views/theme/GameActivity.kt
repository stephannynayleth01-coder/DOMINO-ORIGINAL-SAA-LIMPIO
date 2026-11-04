package com.example.myapplication.views.theme

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.myapplication.data.enums.TypeScreen
import com.example.myapplication.viewmodels.GameViewModel
import com.example.myapplication.viewmodels.RoomViewModel
import com.example.myapplication.views.screens.GameScreen
import com.example.myapplication.views.screens.HomeScreen
import com.example.myapplication.views.screens.LobbyScreen
import com.example.myapplication.views.screens.RoomSelectionScreen

class GameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HockeyGameTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    var currentScreen by remember { mutableStateOf(TypeScreen.HOME) }

                    val roomViewModel: RoomViewModel by viewModels()
                    val gameViewModel: GameViewModel by viewModels()

                    val roomModel by roomViewModel.model.collectAsState()
                    val roomError by roomViewModel.error.collectAsState()

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentScreen) {
                            TypeScreen.HOME -> {
                                HomeScreen(
                                    onClickGame = {
                                        gameViewModel.startNewGame()
                                        currentScreen = TypeScreen.GAME
                                    },
                                    onClickMultiplayer = {
                                        roomViewModel.clearError()
                                        currentScreen = TypeScreen.LOBBY
                                    }
                                )
                            }

                            TypeScreen.LOBBY -> {
                                LobbyScreen(
                                    roomCode = roomModel?.code ?: roomModel?.id ?: "A7K3Q9",
                                    hostName = roomModel?.player1?.getVisibleName() ?: "Anfitrión",
                                    guestName = roomModel?.player2?.getVisibleName(),
                                    onCancelClick = {
                                        currentScreen = TypeScreen.HOME
                                    },
                                    onStartGameClick = {
                                        val p1 = roomModel?.player1
                                        val p2 = roomModel?.player2
                                        gameViewModel.startNewGame(
                                            roomId = roomModel?.code ?: "room-1",
                                            p1Id = p1?.uid ?: "player1",
                                            p1Name = p1?.getVisibleName() ?: "Jugador 1",
                                            p2Id = p2?.uid ?: "player2",
                                            p2Name = p2?.getVisibleName() ?: "Jugador 2"
                                        )
                                        currentScreen = TypeScreen.GAME
                                    }
                                )
                            }

                            TypeScreen.GAME -> {
                                GameScreen(
                                    gameViewModel = gameViewModel,
                                    onBackToHome = {
                                        currentScreen = TypeScreen.HOME
                                    }
                                )
                            }

                            else -> {
                                HomeScreen(
                                    onClickGame = {
                                        gameViewModel.startNewGame()
                                        currentScreen = TypeScreen.GAME
                                    },
                                    onClickMultiplayer = {
                                        roomViewModel.clearError()
                                        currentScreen = TypeScreen.LOBBY
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
