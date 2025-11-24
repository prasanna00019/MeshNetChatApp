package com.appsv.nearbyapi

import java.io.Serializable

data class Message(
    val msgId: String,
    val senderId: String,
    val recipientId: String,
    val messageType: String, // "TEXT", "IMAGE", or "KEY"
    val messageText: String,  // Content, Base64 Image, or Base64 Public Key
    val timestamp: Long = System.currentTimeMillis(),
    var isDeleted: Boolean = false,
    var deletedForEveryone: Boolean = false
) : Serializable