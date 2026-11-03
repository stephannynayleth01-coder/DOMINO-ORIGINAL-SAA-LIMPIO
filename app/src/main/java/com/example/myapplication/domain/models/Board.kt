package com.example.myapplication.domain.models

/*
 * Tablero de dominó.
 */
data class Board(
    val tiles: List<Tile> = emptyList()
) {

    val isEmpty: Boolean
        get() = tiles.isEmpty()

    val leftEnd: Int?
        get() = tiles.firstOrNull()?.left

    val rightEnd: Int?
        get() = tiles.lastOrNull()?.right

    /*
     * Coloca ficha en el extremo izquierdo, invirtiéndola si es necesario y retorna un tablero nuevo o null si la ficha no coincide con el extremo.
     */
    fun placeLeft(tile: Tile): Board? {
        if (tiles.isEmpty()) return Board(listOf(tile))

        val end = leftEnd ?: return null
        return when {
            tile.right == end -> copy(tiles = listOf(tile) + tiles)
            tile.left == end -> copy(tiles = listOf(tile.reversed()) + tiles)
            else -> null
        }
    }

    /*
     * Coloca ficha en el extremo derecho, invirtiéndola si es necesario y return un tablero nuevo o null si la ficha no coincide con el extremo.
     */
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
