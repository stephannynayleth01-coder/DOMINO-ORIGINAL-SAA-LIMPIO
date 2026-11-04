package com.example.myapplication.domain.models

data class GameState(
    val roomId: String,
    val players: List<PlayerState>,
    val board: Board = Board(),
    val deck: List<Tile> = emptyList(),
    val turnPlayerId: String,
    val isFinished: Boolean = false,
    val winnerId: String? = null,
    val skipNextTurnFor: String? = null
)
