package com.example.myapplication.data.models

data class PlayerModel(
    val uid: String = "",
    val email: String = "",
    val name: String = ""
) {
    fun getVisibleName(): String = name.ifBlank { email.substringBefore("@") }.ifBlank { "Jugador" }
}
