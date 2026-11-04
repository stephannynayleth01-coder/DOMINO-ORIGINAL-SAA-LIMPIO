package com.example.myapplication.domain.models

/*
 * Ficha de dominó inmutable compatible con Firebase.
 */
data class Tile(
    val left: Int = 0,
    val right: Int = 0
) {
    val score: Int
        get() = left + right

    fun reversed(): Tile = Tile(right, left)

    override fun toString(): String = "[$left|$right]"
}
