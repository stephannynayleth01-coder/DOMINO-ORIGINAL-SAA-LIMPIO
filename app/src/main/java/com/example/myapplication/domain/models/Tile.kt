package com.example.myapplication.domain.models

import com.google.firebase.database.Exclude

/*
 * Ficha de dominó inmutable compatible con Firebase Realtime Database.
 */
data class Tile(
    val left: Int = 0,
    val right: Int = 0
) {
    constructor(numbers: IntArray) : this(
        left = if (numbers.size == 2) numbers[0] else throw IllegalArgumentException("A domino tile must have exactly 2 numbers"),
        right = if (numbers.size == 2) numbers[1] else throw IllegalArgumentException("A domino tile must have exactly 2 numbers")
    )

    @get:Exclude
    val numbers: IntArray
        get() = intArrayOf(left, right)

    @get:Exclude
    val score: Int
        get() = left + right

    fun reversed(): Tile = Tile(right, left)

    override fun toString(): String = "[$left|$right]"
}
