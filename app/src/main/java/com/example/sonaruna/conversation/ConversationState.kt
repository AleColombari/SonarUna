package com.example.sonaruna.conversation

/** The session stays in memory: no route, map or persistent storage is involved. */
enum class ConversationPhase {
    INITIALIZING,
    PERMISSIONS,
    LOCATING,
    ORIGIN,
    CONFIRM_ORIGIN,
    DESTINATION,
    CONFIRM_DESTINATION,
    COMPLETE,
    ERROR,
}

data class ApproximateLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
)

data class ConversationState(
    val phase: ConversationPhase = ConversationPhase.INITIALIZING,
    val location: ApproximateLocation? = null,
    val origin: String? = null,
    val destination: String? = null,
)

/** The Android layer translates these keys into localized string resources. */
enum class PromptKey {
    PERMISSIONS_EXPLANATION,
    LOCATING,
    ORIGIN_REQUEST,
    ORIGIN_CONFIRMATION,
    ORIGIN_RETRY,
    DESTINATION_REQUEST,
    DESTINATION_CONFIRMATION,
    DESTINATION_RETRY,
    CONFIRMATION_UNCLEAR,
    RECOGNITION_EMPTY,
    COMPLETE,
}

data class Prompt(
    val key: PromptKey,
    val arguments: List<String> = emptyList(),
)

data class Transition(
    val state: ConversationState,
    val prompt: Prompt? = null,
)
