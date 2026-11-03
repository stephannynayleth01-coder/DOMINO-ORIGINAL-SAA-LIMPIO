package com.example.myapplication.domain.models

/*
 * Estado de un jugador dentro de una partida.
 */
data class PlayerState(
    val id: String,
    val name: String,
    val hand: List<Tile> = emptyList(),
    val score: Int = 0,
    val remainingSkipPowers: Int = 1,
    val skipPowersUsed: Int = 0
) {

    val handScore: Int
        get() = hand.sumOf { it.score }

    /*
     * Indica si el jugador tiene al menos una ficha jugable en [board].
     */
    fun canPlay(board: Board): Boolean {
        if (hand.isEmpty()) return false
        if (board.isEmpty) return true

        val leftEnd = board.leftEnd ?: return false
        val rightEnd = board.rightEnd ?: return false

        return hand.any { tile ->
            tile.left == leftEnd || tile.right == leftEnd || tile.left == rightEnd || tile.right == rightEnd
        }
    }
}
