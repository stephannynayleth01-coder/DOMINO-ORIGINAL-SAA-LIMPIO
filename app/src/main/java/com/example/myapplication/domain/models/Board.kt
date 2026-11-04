package com.example.myapplication.domain.models

import com.google.firebase.database.Exclude

/*
 * Tablero de dominó compatible con Firebase Realtime Database.
 */
data class Board(
    val tiles: List<Tile> = emptyList()
) {

    @get:Exclude
    val isEmpty: Boolean
        get() = tiles.isEmpty()

    @get:Exclude
    val leftEnd: Int?
        get() = tiles.firstOrNull()?.left

    @get:Exclude
    val rightEnd: Int?
        get() = tiles.lastOrNull()?.right

    fun placeLeft(tile: Tile): Board? {
        if (tiles.isEmpty()) return Board(listOf(tile))

        val end = leftEnd ?: return null
        return when {
            tile.right == end -> copy(tiles = listOf(tile) + tiles)
            tile.left == end -> copy(tiles = listOf(tile.reversed()) + tiles)
            else -> null
        }
    }

    fun placeRight(tile: Tile): Board? {
        if (tiles.isEmpty()) return Board(listOf(tile))

        val end = rightEnd ?: return null
        return when {
            tile.left == end -> copy(tiles = tiles + tile)
            tile.right == end -> copy(tiles = tiles + tile.reversed())
            else -> null
        }
    }
}
