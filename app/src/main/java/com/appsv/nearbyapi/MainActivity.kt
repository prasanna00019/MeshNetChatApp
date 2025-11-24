package com.appsv.nearbyapi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.appsv.nearbyapi.database.ChatDatabase
import com.appsv.nearbyapi.database.MessageEntity
import com.example.offlinechatapp.R
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.crypto.tink.*
import com.google.crypto.tink.config.TinkConfig
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    
    private val strategy = Strategy.P2P_STAR
    private val serviceId = "com.appsv.nearbyapi.SERVICE_ID"
    private val heartbeatIntervalMs = 15000L
    private val timeoutMs = 45000L
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    // Database
    private lateinit var database: ChatDatabase

    // Persist User ID
    private val myUsername: String by lazy {
        val prefs = getSharedPreferences("ChatAppPrefs", Context.MODE_PRIVATE)
        var id = prefs.getString("USER_ID", null)
        if (id == null) {
            id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            if (id == null || id == "9774d56d682e549c") {
                id = UUID.randomUUID().toString()
            }
            prefs.edit().putString("USER_ID", id).apply()
        }
        id!!
    }

    private lateinit var privateKeysetHandle: KeysetHandle
    private lateinit var publicKeysetHandle: KeysetHandle
    private lateinit var hybridDecrypt: HybridDecrypt
    private val peerPublicKeys = mutableMapOf<String, HybridEncrypt>()
    private var myPublicKeyStr: String = ""

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var userIdTextView: TextView
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var recipientSpinner: Spinner
    private lateinit var recipientAdapter: ArrayAdapter<String>
    private val recipientList = ArrayList<String>()
    private lateinit var targetUserEditText: EditText
    private lateinit var sendBroadcastButton: Button
    private lateinit var sendPrivateButton: Button
    private lateinit var showMembersButton: Button
    private lateinit var advertiseButton: Button
    private lateinit var discoverButton: Button
    private lateinit var discoveredDevicesRecyclerView: RecyclerView
    private lateinit var discoveredDevicesLabel: TextView
    private lateinit var attachImageButton: ImageButton
    private lateinit var attachmentPreviewTextView: TextView
    private lateinit var sendingProgressBar: ProgressBar

    // NEW: Send to Self button
    private lateinit var sendToSelfButton: Button

    private var pendingImageUri: Uri? = null

    private lateinit var messageAdapter: MessageAdapter
    private lateinit var discoveredDeviceAdapter: DiscoveredDeviceAdapter

    private var messages = mutableListOf<Message>()
    private var seenSet = mutableSetOf<String>()
    private val connectedEndpoints = mutableMapOf<String, String>()
    private val discoveredEndpoints = mutableMapOf<String, DiscoveredEndpointInfo>()

    private val imagePickerLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                pendingImageUri = it
                val fileName = getFileName(it)
                attachmentPreviewTextView.text = getString(R.string.attached_file, fileName)
                attachmentPreviewTextView.visibility = View.VISIBLE
                messageEditText.isEnabled = false
                messageEditText.hint = getString(R.string.image_attached)
            }
        }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val KEY_MESSAGES = "messages_key"
        private const val KEY_SEEN_SET = "seen_set_key"

        @Volatile private var isAdvertising = false
        @Volatile private var isDiscovering = false

        private val REQUIRED_PERMISSIONS: Array<String> by lazy {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Database
        database = ChatDatabase.getDatabase(this)

        // Init Myself in MeshRepository
        MeshRepository.updateMember(myUsername, getString(R.string.you), System.currentTimeMillis())

        initEncryption()

        if (savedInstanceState != null) {
            val savedSeenSet = savedInstanceState.getSerializable(KEY_SEEN_SET) as? HashSet<String>
            if (savedSeenSet != null) {
                seenSet = savedSeenSet
            }
        }

        connectionsClient = Nearby.getConnectionsClient(this)

        // Initialize UI elements
        userIdTextView = findViewById(R.id.userIdTextView)
        messagesRecyclerView = findViewById(R.id.messagesRecyclerView)
        messageEditText = findViewById(R.id.messageEditText)
        recipientSpinner = findViewById(R.id.recipientSpinner)
        targetUserEditText = findViewById(R.id.targetUserEditText)
        sendBroadcastButton = findViewById(R.id.sendBroadcastButton)
        sendPrivateButton = findViewById(R.id.sendPrivateButton)
        showMembersButton = findViewById(R.id.showMembersButton)
        advertiseButton = findViewById(R.id.advertiseButton)
        discoverButton = findViewById(R.id.discoverButton)
        discoveredDevicesRecyclerView = findViewById(R.id.discoveredDevicesRecyclerView)
        discoveredDevicesLabel = findViewById(R.id.discoveredDevicesLabel)
        attachImageButton = findViewById(R.id.attachImageButton)
        attachmentPreviewTextView = findViewById(R.id.attachmentPreviewTextView)
        sendingProgressBar = findViewById(R.id.sendingProgressBar)
        sendToSelfButton = findViewById(R.id.sendToSelfButton)

        // Add "Myself" to recipient list for testing
        recipientList.add(getString(R.string.myself))
        recipientAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, recipientList)
        recipientSpinner.adapter = recipientAdapter

        userIdTextView.text = getString(R.string.user_id_label, myUsername)

        messageAdapter = MessageAdapter(
            messages,
            myUsername,
            onDeleteForMe = { msgId -> handleDeleteForMe(msgId) },
            onDeleteForEveryone = { msgId -> handleDeleteForEveryone(msgId) }
        )
        messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        messagesRecyclerView.adapter = messageAdapter

        // Load messages from database
        loadMessagesFromDatabase()

        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener { Toast.makeText(this, getString(R.string.connection_requested), Toast.LENGTH_SHORT).show() }
                    .addOnFailureListener { Toast.makeText(this, getString(R.string.failed_to_request_connection), Toast.LENGTH_SHORT).show() }
            } else {
                Toast.makeText(this, getString(R.string.already_connected), Toast.LENGTH_SHORT).show()
            }
        }
        discoveredDevicesRecyclerView.layoutManager = LinearLayoutManager(this)
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter

        setupClickListeners()
    }

// Continued in Part 2...
// Continuation of MainActivity.kt

    private fun setupClickListeners() {
        advertiseButton.setOnClickListener {
            if (isAdvertising) stopAdvertising() else {
                if (hasPermissions()) startAdvertising() else requestPermissions()
            }
        }

        discoverButton.setOnClickListener {
            if (isDiscovering) stopDiscovery() else {
                if (hasPermissions()) startDiscovery() else requestPermissions()
            }
        }

        showMembersButton.setOnClickListener {
            val intent = Intent(this, MembersActivity::class.java)
            startActivity(intent)
        }

        attachImageButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
        attachmentPreviewTextView.setOnClickListener { clearAttachment() }

        sendBroadcastButton.setOnClickListener {
            sendMessage("BROADCAST")
        }

        sendPrivateButton.setOnClickListener {
            val manualId = targetUserEditText.text.toString().trim()
            val spinnerId = recipientSpinner.selectedItem?.toString()

            if (manualId.isNotEmpty()) {
                sendMessage(manualId)
            } else if (spinnerId == getString(R.string.myself)) {
                sendMessage(myUsername) // Send to self
            } else if (spinnerId != null) {
                sendMessage(spinnerId)
            } else {
                Toast.makeText(this, getString(R.string.please_select_or_enter_recipient), Toast.LENGTH_SHORT).show()
            }
        }

        // NEW: Quick button to send to self
        sendToSelfButton.setOnClickListener {
            sendMessage(myUsername)
        }
    }

    private fun loadMessagesFromDatabase() {
        mainScope.launch {
            try {
                val dbMessages = withContext(Dispatchers.IO) {
                    database.messageDao().getMessagesForUser(myUsername)
                }

                messages.clear()
                messages.addAll(dbMessages.map { entity ->
                    Message(
                        msgId = entity.msgId,
                        senderId = entity.senderId,
                        recipientId = entity.recipientId,
                        messageType = entity.messageType,
                        messageText = entity.messageText,
                        timestamp = entity.timestamp,
                        isDeleted = entity.isDeleted,
                        deletedForEveryone = entity.deletedForEveryone
                    )
                })

                messageAdapter.notifyDataSetChanged()
                messagesRecyclerView.scrollToPosition(messages.size - 1)

                Log.d("MainActivity", "Loaded ${messages.size} messages from database")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading messages", e)
            }
        }
    }

    private fun handleDeleteForMe(msgId: String) {
        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.messageDao().deleteMessageForMe(msgId)
                }
                messageAdapter.updateMessage(msgId, isDeleted = true, deletedForEveryone = false)
                Toast.makeText(this@MainActivity, getString(R.string.message_deleted_for_you), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error deleting message for me", e)
                Toast.makeText(this@MainActivity, getString(R.string.failed_to_delete_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDeleteForEveryone(msgId: String) {
        mainScope.launch {
            try {
                // Update in database
                withContext(Dispatchers.IO) {
                    database.messageDao().deleteMessageForEveryone(msgId)
                }

                // Update UI
                messageAdapter.updateMessage(msgId, isDeleted = true, deletedForEveryone = true)

                // Send delete notification to network
                val deleteMessage = Message(
                    msgId = "DEL-$msgId-${System.currentTimeMillis()}",
                    senderId = myUsername,
                    recipientId = "BROADCAST",
                    messageType = "DELETE_FOR_EVERYONE",
                    messageText = msgId // The ID of the message to delete
                )

                seenSet.add(deleteMessage.msgId)
                forwardMessage(deleteMessage)

                Toast.makeText(this@MainActivity, getString(R.string.message_deleted_for_everyone), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error deleting message for everyone", e)
                Toast.makeText(this@MainActivity, getString(R.string.failed_to_delete_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initEncryption() {
        try {
            TinkConfig.register()
            privateKeysetHandle = KeysetHandle.generateNew(
                KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")
            )
            publicKeysetHandle = privateKeysetHandle.publicKeysetHandle
            hybridDecrypt = privateKeysetHandle.getPrimitive(HybridDecrypt::class.java)

            val outputStream = ByteArrayOutputStream()
            CleartextKeysetHandle.write(
                publicKeysetHandle,
                BinaryKeysetWriter.withOutputStream(outputStream)
            )
            myPublicKeyStr = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            Log.d("Encryption", "Keys generated successfully.")
        } catch (e: Exception) {
            Log.e("Encryption", "Error initializing encryption", e)
            Toast.makeText(this, getString(R.string.encryption_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun startHeartbeat() {
        mainScope.launch {
            while (isActive) {
                if (connectedEndpoints.isNotEmpty()) {
                    sendPresenceHeartbeat()
                }
                MeshRepository.pruneExpiredMembers(timeoutMs)
                delay(heartbeatIntervalMs)
            }
        }
    }

    private fun sendPresenceHeartbeat() {
        val msgId = "$myUsername-P-${System.currentTimeMillis()}"
        val content = "${System.currentTimeMillis()}_$myUsername"
        val message = Message(msgId, myUsername, "BROADCAST", "PRESENCE", content)

        seenSet.add(msgId)
        MeshRepository.updateMember(myUsername, "You", System.currentTimeMillis())
        forwardMessage(message)
    }

    private fun broadcastPublicKey() {
        if (connectedEndpoints.isEmpty()) return
        val msgId = "$myUsername-${System.currentTimeMillis()}-KEY"
        val message = Message(msgId, myUsername, "BROADCAST", "KEY", myPublicKeyStr)
        seenSet.add(msgId)
        forwardMessage(message)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_SEEN_SET, HashSet(seenSet))
    }

    override fun onStart() {
        super.onStart()
        if (!hasPermissions()) requestPermissions()
        startHeartbeat()
    }

    override fun onDestroy() {
        connectionsClient.stopAllEndpoints()
        connectedEndpoints.clear()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && !(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })) {
            Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
        }
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(myUsername, serviceId, connectionLifecycleCallback, advertisingOptions)
            .addOnSuccessListener {
                isAdvertising = true
                advertiseButton.text = getString(R.string.stop_advertising)
                Toast.makeText(this, getString(R.string.advertising), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { isAdvertising = false }
    }

    private fun stopAdvertising() {
        connectionsClient.stopAdvertising()
        isAdvertising = false
        advertiseButton.text = getString(R.string.advertise)
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                isDiscovering = true
                discoverButton.text = getString(R.string.stop_discovery)
                discoveredDevicesLabel.visibility = View.VISIBLE
                discoveredDevicesRecyclerView.visibility = View.VISIBLE
            }
            .addOnFailureListener { isDiscovering = false }
    }

    private fun stopDiscovery() {
        connectionsClient.stopDiscovery()
        isDiscovering = false
        discoverButton.text = getString(R.string.discover)
        discoveredEndpoints.clear()
        updateDiscoveredDevicesList()
        discoveredDevicesLabel.visibility = View.GONE
        discoveredDevicesRecyclerView.visibility = View.GONE
    }

// Continued in Part 3...
// Continuation of MainActivity.kt - Part 3

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.accept_connection))
                .setMessage(getString(R.string.connect_to_user, connectionInfo.endpointName))
                .setPositiveButton(getString(R.string.accept)) { _, _ ->
                    connectionsClient.acceptConnection(endpointId, payloadCallback)
                    connectedEndpoints[endpointId] = connectionInfo.endpointName
                }
                .setNegativeButton(getString(R.string.reject)) { _, _ -> connectionsClient.rejectConnection(endpointId) }
                .show()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Toast.makeText(this@MainActivity, getString(R.string.connected), Toast.LENGTH_SHORT).show()
                stopDiscovery()
                broadcastPublicKey()
                sendPresenceHeartbeat()
            } else {
                connectedEndpoints.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val username = connectedEndpoints[endpointId]
            connectedEndpoints.remove(endpointId)

            if (username != null) {
                peerPublicKeys.remove(username)
                runOnUiThread {
                    if (recipientList.contains(username)) {
                        recipientList.remove(username)
                        recipientAdapter.notifyDataSetChanged()
                    }
                    Toast.makeText(this@MainActivity, getString(R.string.disconnected), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (!connectedEndpoints.containsKey(endpointId)) {
                discoveredEndpoints[endpointId] = info
                updateDiscoveredDevicesList()
            }
        }
        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            updateDiscoveredDevicesList()
        }
    }

    private fun updateDiscoveredDevicesList() {
        discoveredDeviceAdapter = DiscoveredDeviceAdapter(discoveredEndpoints.toList()) { endpointId ->
            if (!connectedEndpoints.containsKey(endpointId)) {
                connectionsClient.requestConnection(myUsername, endpointId, connectionLifecycleCallback)
            }
        }
        discoveredDevicesRecyclerView.adapter = discoveredDeviceAdapter
    }

    private fun sendMessage(recipientId: String) {
        val inputText = messageEditText.text.toString().trim()
        val imageUri = pendingImageUri

        if (imageUri == null && inputText.isEmpty()) return

        sendingProgressBar.visibility = View.VISIBLE
        sendBroadcastButton.isEnabled = false
        sendPrivateButton.isEnabled = false
        sendToSelfButton.isEnabled = false

        clearAttachment()
        messageEditText.text.clear()

        thread {
            try {
                val msgId = "$myUsername-${System.currentTimeMillis()}"
                var content: String
                val rawContent: String
                var type: String

                if (imageUri != null) {
                    val base64Img = uriToBase64(imageUri) ?: return@thread
                    rawContent = base64Img
                    content = base64Img
                    type = "IMAGE"
                } else {
                    rawContent = inputText
                    content = inputText
                    type = "TEXT"
                }

                // Handle sending to self (offline mode - no encryption needed)
                if (recipientId == myUsername) {
                    val msgForLocal = Message(
                        msgId,
                        myUsername,
                        myUsername,
                        type,
                        rawContent,
                        timestamp = System.currentTimeMillis()
                    )

                    // Save to database
                    mainScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                database.messageDao().insertMessage(
                                    MessageEntity(
                                        msgId = msgForLocal.msgId,
                                        senderId = msgForLocal.senderId,
                                        recipientId = msgForLocal.recipientId,
                                        messageType = msgForLocal.messageType,
                                        messageText = msgForLocal.messageText,
                                        timestamp = msgForLocal.timestamp
                                    )
                                )
                            }

                            runOnUiThread {
                                messages.add(msgForLocal)
                                messageAdapter.notifyItemInserted(messages.size - 1)
                                messagesRecyclerView.scrollToPosition(messages.size - 1)
                                resetUI()
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error saving message to self", e)
                            runOnUiThread { resetUI() }
                        }
                    }
                    return@thread
                }

                // Handle network messages (existing encryption logic)
                if (recipientId != "BROADCAST" && connectedEndpoints.isNotEmpty()) {
                    val recipientKey = peerPublicKeys[recipientId]
                    if (recipientKey != null) {
                        try {
                            val encryptedBytes = recipientKey.encrypt(content.toByteArray(StandardCharsets.UTF_8), null)
                            content = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, getString(R.string.encryption_failed), Toast.LENGTH_SHORT).show()
                                resetUI()
                            }
                            return@thread
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, getString(R.string.warning_key_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                val msgForNetwork = Message(
                    msgId,
                    myUsername,
                    recipientId,
                    type,
                    content,
                    timestamp = System.currentTimeMillis()
                )

                val msgForLocal = Message(
                    msgId,
                    myUsername,
                    recipientId,
                    type,
                    rawContent,
                    timestamp = System.currentTimeMillis()
                )

                seenSet.add(msgId)

                // Save to database
                mainScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            database.messageDao().insertMessage(
                                MessageEntity(
                                    msgId = msgForLocal.msgId,
                                    senderId = msgForLocal.senderId,
                                    recipientId = msgForLocal.recipientId,
                                    messageType = msgForLocal.messageType,
                                    messageText = msgForLocal.messageText,
                                    timestamp = msgForLocal.timestamp
                                )
                            )
                        }

                        runOnUiThread {
                            messages.add(msgForLocal)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRecyclerView.scrollToPosition(messages.size - 1)
                            resetUI()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error saving message", e)
                        runOnUiThread { resetUI() }
                    }
                }

                if (connectedEndpoints.isNotEmpty()) {
                    forwardMessage(msgForNetwork)
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error in sendMessage thread", e)
                runOnUiThread { resetUI() }
            }
        }
    }

    private fun resetUI() {
        sendingProgressBar.visibility = View.GONE
        sendBroadcastButton.isEnabled = true
        sendPrivateButton.isEnabled = true
        sendToSelfButton.isEnabled = true
    }

    private fun forwardMessage(message: Message) {
        val messageString = "${message.messageType}|${message.msgId}|${message.senderId}|${message.recipientId}|${message.messageText}"
        val payload = Payload.fromBytes(messageString.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(connectedEndpoints.keys.toList(), payload)
            .addOnFailureListener { e -> Log.e("MainActivity", "Send failed", e) }
    }

// Continued in Part 4...
// Continuation of MainActivity.kt - Part 4

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedString = payload.asBytes()?.toString(StandardCharsets.UTF_8) ?: return
                val parts = receivedString.split("|", limit = 5)
                if (parts.size == 5) {
                    val type = parts[0].trim()
                    val msgId = parts[1].trim()
                    val sender = parts[2].trim()
                    val recipient = parts[3].trim()
                    var content = parts[4]

                    if (seenSet.contains(msgId)) return
                    seenSet.add(msgId)

                    // Handle DELETE_FOR_EVERYONE
                    if (type == "DELETE_FOR_EVERYONE") {
                        val originalMsgId = content
                        mainScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    database.messageDao().deleteMessageForEveryone(originalMsgId)
                                }
                                messageAdapter.updateMessage(originalMsgId, isDeleted = true, deletedForEveryone = true)
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error processing delete", e)
                            }
                        }
                        forwardMessage(Message(msgId, sender, recipient, type, content))
                        return
                    }

                    // Handle PRESENCE
                    if (type == "PRESENCE") {
                        val data = content.split("_", limit = 2)
                        if (data.size == 2) {
                            val timestamp = data[0].toLongOrNull() ?: System.currentTimeMillis()
                            val name = data[1]
                            MeshRepository.updateMember(sender, name, timestamp)
                        }
                        forwardMessage(Message(msgId, sender, recipient, type, content))
                        return
                    }

                    // Handle KEY
                    if (type == "KEY") {
                        handleIncomingKey(sender, content)
                        forwardMessage(Message(msgId, sender, recipient, type, content))
                        return
                    }

                    var shouldForward = true

                    if (recipient == "BROADCAST") {
                        displayMessage(msgId, sender, recipient, type, content)
                        shouldForward = true
                    } else if (recipient == myUsername) {
                        // Message for ME
                        try {
                            val ciphertext = Base64.decode(content, Base64.NO_WRAP)
                            val decryptedBytes = hybridDecrypt.decrypt(ciphertext, null)
                            content = String(decryptedBytes, StandardCharsets.UTF_8)
                        } catch (e: Exception) {
                            Log.e("Encryption", "Decryption failed (or plaintext)", e)
                        }

                        displayMessage(msgId, sender, recipient, type, content)
                        shouldForward = false
                    } else {
                        // Message for SOMEONE ELSE -> Flood it
                        shouldForward = true
                    }

                    if (shouldForward) {
                        val originalMsg = Message(msgId, sender, recipient, type, parts[4])
                        forwardMessage(originalMsg)
                    }
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun displayMessage(msgId: String, sender: String, recipient: String, type: String, content: String) {
        val msgObj = Message(
            msgId,
            sender,
            recipient,
            type,
            content,
            timestamp = System.currentTimeMillis()
        )

        // Save to database
        mainScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.messageDao().insertMessage(
                        MessageEntity(
                            msgId = msgObj.msgId,
                            senderId = msgObj.senderId,
                            recipientId = msgObj.recipientId,
                            messageType = msgObj.messageType,
                            messageText = msgObj.messageText,
                            timestamp = msgObj.timestamp
                        )
                    )
                }

                runOnUiThread {
                    messages.add(msgObj)
                    messageAdapter.notifyItemInserted(messages.size - 1)
                    messagesRecyclerView.scrollToPosition(messages.size - 1)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error saving received message", e)
            }
        }
    }

    private fun handleIncomingKey(senderId: String, keyBase64: String) {
        if (peerPublicKeys.containsKey(senderId)) return

        try {
            val keyBytes = Base64.decode(keyBase64, Base64.NO_WRAP)
            val handle = CleartextKeysetHandle.read(
                BinaryKeysetReader.withInputStream(ByteArrayInputStream(keyBytes))
            )
            val encryptPrimitive = handle.getPrimitive(HybridEncrypt::class.java)
            peerPublicKeys[senderId] = encryptPrimitive

            runOnUiThread {
                if (!recipientList.contains(senderId)) {
                    recipientList.add(senderId)
                    recipientAdapter.notifyDataSetChanged()
                    Toast.makeText(this, getString(R.string.user_available, senderId), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("Encryption", "Failed to parse key from $senderId", e)
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var name = getString(R.string.unknown_jpg)
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) name = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
        }
        return name
    }

    private fun clearAttachment() {
        pendingImageUri = null
        attachmentPreviewTextView.visibility = View.GONE
        messageEditText.isEnabled = true
        messageEditText.hint = getString(R.string.type_a_message)
    }

    private fun uriToBase64(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            val scaled = scaleBitmap(bitmap, 800)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        if (originalWidth <= maxDimension && originalHeight <= maxDimension) return bitmap
        val newWidth: Int
        val newHeight: Int
        if (originalWidth > originalHeight) {
            newWidth = maxDimension
            newHeight = (originalHeight * (maxDimension.toFloat() / originalWidth)).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (originalWidth * (maxDimension.toFloat() / originalHeight)).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}