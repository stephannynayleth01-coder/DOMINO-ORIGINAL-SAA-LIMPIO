package com.example.myapplication.repositories

import android.util.Log
import com.example.myapplication.data.models.PlayerModel
import com.example.myapplication.data.models.RoomCodeGenerator
import com.example.myapplication.data.models.RoomModel
import com.example.myapplication.domain.models.GameState
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.atomic.AtomicReference

/**
 * Acceso a Realtime Database.
 *
 * Reglas de escritura del estado de partida:
 *  - El anfitrión crea el estado inicial solo si no existe (o si el anterior ya terminó).
 *  - Cada movimiento se aplica con una transacción que valida en el servidor que el
 *    jugador que escribe es el dueño del turno. Así dos dispositivos nunca se pisan.
 *  - La UI se alimenta siempre del listener (`onUpdate`), que es la fuente de verdad.
 */
class RoomRepository {
    private val TAG = "RoomRepository"
    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private val roomsRef: DatabaseReference by lazy { database.getReference("rooms") }
    private val gamesRef: DatabaseReference by lazy { database.getReference("games") }

    private val activeRoomsLocal = mutableMapOf<String, RoomModel>()
    private val roomListeners = mutableMapOf<String, ValueEventListener>()
    private val gameListeners = mutableMapOf<String, ValueEventListener>()

    // ---------------------------------------------------------------------
    // Salas
    // ---------------------------------------------------------------------

    fun createRoom(hostPlayer: PlayerModel): RoomModel {
        val roomCode = RoomCodeGenerator.generate(6)
        val room = RoomModel(
            id = roomCode,
            code = roomCode,
            player1 = hostPlayer,
            player2 = null,
            startAt = System.currentTimeMillis().toString(),
            winnerPlayerUid = null,
            turnPlayerUid = hostPlayer.uid,
            status = "WAITING",
            finished = false
        )

        activeRoomsLocal[roomCode] = room

        roomsRef.child(roomCode).setValue(room)
            .addOnSuccessListener { Log.d(TAG, "Room created on Firebase: $roomCode") }
            .addOnFailureListener { Log.w(TAG, "Firebase room write failed", it) }

        return room
    }

    fun getRoom(code: String, onResult: (RoomModel?) -> Unit) {
        val cleanCode = code.trim().uppercase()
        roomsRef.child(cleanCode).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(RoomModel::class.java)
                if (room != null) activeRoomsLocal[cleanCode] = room
                onResult(room)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "getRoom cancelled for $cleanCode: ${error.message}")
                onResult(activeRoomsLocal[cleanCode])
            }
        })
    }

    fun joinRoomByCode(code: String, guestPlayer: PlayerModel, onResult: (RoomModel?, String?) -> Unit) {
        val cleanCode = code.trim().uppercase()

        roomsRef.child(cleanCode).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(RoomModel::class.java)
                if (room == null) {
                    onResult(null, "No se encontró la sala con código $cleanCode")
                    return
                }

                val alreadyJoined = room.player2?.uid == guestPlayer.uid
                if (room.player2 != null && !alreadyJoined) {
                    onResult(null, "La sala $cleanCode ya está llena")
                    return
                }

                // Si el invitado vuelve a entrar a una partida en curso conservamos
                // el estado; solo reiniciamos salas terminadas o canceladas.
                val resetStatus = room.status == "FINISHED" || room.status == "CANCELLED"

                val updatedRoom = room.copy(
                    id = cleanCode,
                    code = cleanCode,
                    player2 = guestPlayer,
                    status = if (resetStatus) "WAITING" else room.status,
                    finished = if (resetStatus) false else room.finished
                )

                activeRoomsLocal[cleanCode] = updatedRoom

                // updateChildren evita pisar el status/turno que el anfitrión pudo
                // haber escrito mientras el invitado se estaba uniendo.
                val updates = mutableMapOf<String, Any?>(
                    "id" to cleanCode,
                    "code" to cleanCode,
                    "player2" to guestPlayer
                )
                if (resetStatus) {
                    updates["status"] = "WAITING"
                    updates["finished"] = false
                    updates["winnerPlayerUid"] = null
                }
                roomsRef.child(cleanCode).updateChildren(updates)
                    .addOnFailureListener { Log.w(TAG, "Could not persist join for $cleanCode", it) }

                onResult(updatedRoom, null)
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(null, "Error al conectar con la sala: ${error.message}")
            }
        })
    }

    fun joinOpenRoom(guestPlayer: PlayerModel, onResult: (RoomModel?, String?) -> Unit) {
        roomsRef.orderByChild("status").equalTo("WAITING")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val firstChild = snapshot.children
                        .firstOrNull { it.child("player2").value == null }
                    if (firstChild == null) {
                        onResult(null, "No hay salas abiertas disponibles. ¡Crea una nueva!")
                        return
                    }
                    val roomCode = firstChild.key ?: return
                    joinRoomByCode(roomCode, guestPlayer, onResult)
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(null, error.message)
                }
            })
    }

    fun listenToRoom(roomCode: String, onUpdate: (RoomModel) -> Unit, onError: (String) -> Unit = {}) {
        stopListeningToRoom(roomCode)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val room = snapshot.getValue(RoomModel::class.java)
                if (room != null) {
                    activeRoomsLocal[roomCode] = room
                    onUpdate(room)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Room listener cancelled for $roomCode: ${error.message}", error.toException())
                onError("No se pudo escuchar la sala: ${error.message}")
            }
        }

        roomListeners[roomCode] = listener
        roomsRef.child(roomCode).addValueEventListener(listener)
    }

    fun stopListeningToRoom(roomCode: String) {
        roomListeners.remove(roomCode)?.let { listener ->
            roomsRef.child(roomCode).removeEventListener(listener)
        }
    }

    /** Marca la sala como jugando y fija el turno inicial. */
    fun startRoomGame(roomCode: String, hostUid: String) {
        roomsRef.child(roomCode).updateChildren(
            mapOf(
                "status" to "PLAYING",
                "turnPlayerUid" to hostUid,
                "finished" to false,
                "winnerPlayerUid" to null
            )
        ).addOnSuccessListener {
            Log.d(TAG, "Room $roomCode -> PLAYING")
        }.addOnFailureListener {
            Log.w(TAG, "Could not set room $roomCode to PLAYING", it)
        }
    }

    fun finishRoom(roomCode: String, winnerUid: String?) {
        roomsRef.child(roomCode).updateChildren(
            mapOf(
                "status" to "FINISHED",
                "turnPlayerUid" to winnerUid,
                "finished" to true,
                "winnerPlayerUid" to winnerUid
            )
        ).addOnSuccessListener {
            Log.d(TAG, "Room $roomCode -> FINISHED (winner=$winnerUid)")
        }.addOnFailureListener {
            Log.w(TAG, "Could not finish room $roomCode", it)
        }
    }

    fun updateRoomStatus(roomCode: String, status: String) {
        roomsRef.child(roomCode).child("status").setValue(status)
            .addOnFailureListener { Log.w(TAG, "Failed to update room status", it) }
    }

    // ---------------------------------------------------------------------
    // Partida (GameState)
    // ---------------------------------------------------------------------

    /**
     * Crea el estado inicial si `/games/{roomId}` está vacío o si la partida anterior
     * ya había terminado (permite revancha). Nunca sobrescribe una partida viva.
     */
    fun startOrReplaceGame(
        roomId: String,
        initialState: GameState,
        onResult: (GameState?, String?) -> Unit = { _, _ -> }
    ) {
        gamesRef.child(roomId).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = try {
                    currentData.getValue(GameState::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing current game for $roomId", e)
                    null
                }

                if (current != null && !current.isFinished) {
                    return Transaction.abort()
                }

                return try {
                    // La revancha debe tener una versión mayor para no ser descartada
                    // por el filtro de snapshots atrasados del cliente.
                    val nextVersion = (current?.version ?: -1L) + 1L
                    currentData.value = initialState.copy(version = nextVersion)
                    Transaction.success(currentData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing initial game for $roomId", e)
                    Transaction.abort()
                }
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    Log.e(TAG, "Transaction failed creating game $roomId: ${error.message}", error.toException())
                    onResult(null, "No se pudo crear la partida: ${error.message}")
                    return
                }
                if (!committed) {
                    Log.d(TAG, "Game $roomId already exists; keeping current state")
                    onResult(null, null)
                    return
                }
                val saved = snapshot?.getValue(GameState::class.java)
                Log.d(TAG, "Game $roomId initialized (version=${saved?.version})")
                onResult(saved, null)
            }
        })
    }

    /**
     * Aplica un movimiento de forma atómica.
     *
     * @param expectedTurnPlayerId jugador que DEBE tener el turno en el servidor.
     * @param transform función pura que recibe el estado autoritativo y devuelve el nuevo.
     */
    fun saveGameState(
        roomId: String,
        expectedTurnPlayerId: String,
        transform: (GameState) -> Result<GameState>,
        onSuccess: (GameState) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (roomId.isBlank() || expectedTurnPlayerId.isBlank()) {
            onError("Sesión de partida inválida")
            return
        }

        val abortReason = AtomicReference<String?>(null)

        gamesRef.child(roomId).runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val current = try {
                    currentData.getValue(GameState::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Error deserializing game $roomId in transaction", e)
                    abortReason.set("No se pudo leer la partida")
                    return Transaction.abort()
                }

                if (current == null) {
                    abortReason.set("La partida todavía no existe")
                    return Transaction.abort()
                }
                if (current.isFinished) {
                    abortReason.set("La partida ya terminó")
                    return Transaction.abort()
                }
                if (current.turnPlayerId != expectedTurnPlayerId) {
                    abortReason.set("No es tu turno")
                    return Transaction.abort()
                }

                val nextState = transform(current).getOrElse { error ->
                    abortReason.set(error.message ?: "Movimiento inválido")
                    return Transaction.abort()
                }

                return try {
                    currentData.value = nextState.copy(version = current.version + 1)
                    Transaction.success(currentData)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing game $roomId", e)
                    abortReason.set("No se pudo guardar el movimiento")
                    Transaction.abort()
                }
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null) {
                    Log.e(TAG, "Transaction failed for $roomId: ${error.message}", error.toException())
                    onError("Error de sincronización: ${error.message}")
                    return
                }
                if (!committed) {
                    val reason = abortReason.get() ?: "Movimiento rechazado por el servidor"
                    Log.w(TAG, "Transaction aborted for $roomId: $reason")
                    onError(reason)
                    return
                }
                val saved = try {
                    snapshot?.getValue(GameState::class.java)
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading committed game $roomId", e)
                    null
                }
                Log.d(TAG, "Game $roomId saved (version=${saved?.version}, turn=${saved?.turnPlayerId})")
                if (saved != null) onSuccess(saved)
            }
        })
    }

    fun listenToGame(
        roomId: String,
        onUpdate: (GameState) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        stopListeningToGame(roomId)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val game = try {
                    snapshot.getValue(GameState::class.java)
                } catch (e: Exception) {
                    // Esto solo debería ocurrir si un modelo deja de tener constructor
                    // sin argumentos; lo dejamos visible en vez de fallar en silencio.
                    Log.e(TAG, "Error deserializing GameState for $roomId", e)
                    onError("No se pudo leer la partida: ${e.message}")
                    return
                }

                if (game != null) {
                    Log.d(
                        TAG,
                        "Game $roomId update: version=${game.version}, turn=${game.turnPlayerId}, " +
                            "board=${game.board.tiles.size}, deck=${game.deck.size}"
                    )
                    onUpdate(game)
                } else {
                    Log.w(TAG, "GameState snapshot for $roomId was null or empty")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Game listener cancelled for $roomId: ${error.message}", error.toException())
                onError("Sin conexión con la partida: ${error.message}")
            }
        }

        gameListeners[roomId] = listener
        gamesRef.child(roomId).addValueEventListener(listener)
    }

    fun stopListeningToGame(roomId: String) {
        gameListeners.remove(roomId)?.let { listener ->
            gamesRef.child(roomId).removeEventListener(listener)
        }
    }

    fun stopAllListeners() {
        roomListeners.keys.toList().forEach(::stopListeningToRoom)
        gameListeners.keys.toList().forEach(::stopListeningToGame)
    }
}
