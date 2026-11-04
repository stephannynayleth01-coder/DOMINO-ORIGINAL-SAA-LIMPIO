package com.example.myapplication.domain.models

/*
 * Estado completo de una partida.
 *
 * IMPORTANTE: todos los parámetros tienen valor por defecto a propósito. Firebase
 * Realtime Database (CustomClassMapper/BeanMapper) exige un constructor sin
 * argumentos para poder deserializar; si algún parámetro no tiene default, Kotlin
 * no lo genera y `snapshot.getValue(GameState::class.java)` falla con:
 * "Class ... does not define a no-argument constructor".
 */
data class GameState(
    val roomId: String = "",
    val players: List<PlayerState> = emptyList(),
    val board: Board = Board(),
    val deck: List<Tile> = emptyList(),
    val turnPlayerId: String = "",
    val isFinished: Boolean = false,
    val winnerId: String? = null,
    val skipNextTurnFor: String? = null,
    /* Se incrementa en cada escritura; sirve para descartar snapshots atrasados. */
    val version: Long = 0L
)
