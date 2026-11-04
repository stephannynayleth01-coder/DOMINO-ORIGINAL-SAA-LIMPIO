package com.udistrital.hockeygame.domain.engine

import com.example.myapplication.domain.models.domain.models.Board
import com.example.myapplication.domain.models.domain.models.PlayerState
import com.example.myapplication.domain.models.domain.models.Tile
import com.udistrital.hockeygame.domain.models.GameState
import kotlin.random.Random


class DominoEngine {

    fun createInitialGame(
        roomId: String,
        player1Id: String,
        player1Name: String,
        player2Id: String,
        player2Name: String,
        seed: Long? = null
    ): GameState {
        require(player1Id != player2Id) { "Players must have different ids" }

        val random = seed?.let { Random(it) } ?: Random.Default
        val deck = buildDeck().shuffled(random).toMutableList()

        val player1Hand = mutableListOf<Tile>()
        val player2Hand = mutableListOf<Tile>()

        repeat(INITIAL_HAND_SIZE) {
            player1Hand += deck.removeAt(0)
            player2Hand += deck.removeAt(0)
        }

        return GameState(
            roomId = roomId,
            players = listOf(
                PlayerState(id = player1Id, name = player1Name, hand = player1Hand),
                PlayerState(id = player2Id, name = player2Name, hand = player2Hand)
            ),
            board = Board(),
            deck = deck,
            turnPlayerId = player1Id
        )
    }

    fun playTile(
        state: GameState,
        playerId: String,
        tile: Tile,
        onLeftSide: Boolean
    ): Result<GameState> {
        if (state.isFinished) {
            return Result.failure(IllegalStateException("The game is already finished"))
        }
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("It is not $playerId's turn"))
        }

        val playerIndex = state.players.indexOfFirst { it.id == playerId }
        if (playerIndex == -1) {
            return Result.failure(IllegalArgumentException("Player $playerId was not found"))
        }

        val player = state.players[playerIndex]
        val tileIndex = player.hand.indexOf(tile)
        if (tileIndex == -1) {
            return Result.failure(IllegalArgumentException("Player $playerId does not own tile $tile"))
        }

        val updatedBoard = when {
            state.board.isEmpty -> Board(listOf(tile))
            onLeftSide -> state.board.placeLeft(tile)
                ?: return Result.failure(
                    IllegalArgumentException("Tile $tile cannot be placed on the left end")
                )

            else -> state.board.placeRight(tile)
                ?: return Result.failure(
                    IllegalArgumentException("Tile $tile cannot be placed on the right end")
                )
        }

        val updatedHand = player.hand.toMutableList().apply { removeAt(tileIndex) }
        val updatedPlayers = state.players.toMutableList().apply {
            this[playerIndex] = player.copy(hand = updatedHand)
        }

        val nextTurnPlayerId = when {
            state.skipNextTurnFor == playerId -> playerId
            else -> state.players.firstOrNull { it.id != playerId }?.id ?: playerId
        }

        val nextState = state.copy(
            players = updatedPlayers,
            board = updatedBoard,
            turnPlayerId = nextTurnPlayerId,
            skipNextTurnFor = null
        )

        return Result.success(checkGameEnd(nextState))
    }

    fun useSkipPower(state: GameState, playerId: String): Result<GameState> {
        if (state.isFinished) {
            return Result.failure(IllegalStateException("The game is already finished"))
        }
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("It is not $playerId's turn"))
        }
        if (state.players.none { it.id != playerId }) {
            return Result.failure(IllegalStateException("Skip power requires an opponent"))
        }
        if (state.skipNextTurnFor == playerId) {
            return Result.failure(IllegalStateException("Skip power is already active"))
        }

        val playerIndex = state.players.indexOfFirst { it.id == playerId }
        if (playerIndex == -1) {
            return Result.failure(IllegalArgumentException("Player $playerId was not found"))
        }

        val player = state.players[playerIndex]
        if (player.remainingSkipPowers <= 0) {
            return Result.failure(IllegalStateException("Player $playerId has no skip powers left"))
        }

        val updatedPlayer = player.copy(
            remainingSkipPowers = player.remainingSkipPowers - 1,
            skipPowersUsed = player.skipPowersUsed + 1
        )
        val updatedPlayers = state.players.toMutableList().apply {
            this[playerIndex] = updatedPlayer
        }

        return Result.success(
            state.copy(
                players = updatedPlayers,
                skipNextTurnFor = playerId
            )
        )
    }

    fun drawTile(state: GameState, playerId: String): Result<GameState> {
        if (state.isFinished) {
            return Result.failure(IllegalStateException("The game is already finished"))
        }
        if (state.turnPlayerId != playerId) {
            return Result.failure(IllegalStateException("It is not $playerId's turn"))
        }
        if (state.deck.isEmpty()) {
            return Result.failure(IllegalStateException("The deck is empty"))
        }

        val playerIndex = state.players.indexOfFirst { it.id == playerId }
        if (playerIndex == -1) {
            return Result.failure(IllegalArgumentException("Player $playerId was not found"))
        }

        val drawnTile = state.deck.first()
        val player = state.players[playerIndex]
        val updatedPlayers = state.players.toMutableList().apply {
            this[playerIndex] = player.copy(hand = player.hand + drawnTile)
        }

        val nextState = state.copy(
            players = updatedPlayers,
            deck = state.deck.drop(1)
        )

        return Result.success(checkGameEnd(nextState))
    }

    fun checkGameEnd(state: GameState): GameState {
        if (state.isFinished) return state

        val someoneEmptyHand = state.players.any { it.hand.isEmpty() }
        val blocked = state.deck.isEmpty() && state.players.none { it.canPlay(state.board) }

        if (!someoneEmptyHand && !blocked) return state

        val scoredPlayers = state.players.map { player ->
            player.copy(score = player.handScore)
        }

        val lowestScore = scoredPlayers.minOfOrNull { it.score } ?: 0
        val lowestScorePlayers = scoredPlayers.filter { it.score == lowestScore }
        val fewestPowersUsed = lowestScorePlayers.minOfOrNull { it.skipPowersUsed } ?: 0
        val finalists = lowestScorePlayers.filter { it.skipPowersUsed == fewestPowersUsed }

        return state.copy(
            players = scoredPlayers,
            isFinished = true,
            winnerId = finalists.singleOrNull()?.id,
            skipNextTurnFor = null
        )
    }

    private fun buildDeck(): List<Tile> = buildList {
        for (left in MIN_TILE_VALUE..MAX_TILE_VALUE) {
            for (right in left..MAX_TILE_VALUE) {
                add(Tile(intArrayOf(left, right)))
            }
        }
    }

    companion object {
        const val MIN_TILE_VALUE = 0
        const val MAX_TILE_VALUE = 6
        const val INITIAL_HAND_SIZE = 7
    }
}