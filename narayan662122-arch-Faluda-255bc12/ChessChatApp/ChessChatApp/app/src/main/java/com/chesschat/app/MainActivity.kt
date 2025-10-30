package com.chesschat.app

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.chesschat.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Filhaal button kuch nahi karega
        binding.startButton.setOnClickListener {
            // Yahan baad me code add karenge
        }
    }
}                        Toast.makeText(this, "Overlay permission denied. Some features may not work.", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .show()
            } else {
                appendToChat("✓ Overlay permission granted\n")
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val componentName = android.content.ComponentName(this, ChessAccessibilityService::class.java)
        val service = componentName.flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(service) == true
    }

    private fun checkAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("Accessibility Service Required")
                .setMessage("For auto-play functionality, enable the Chess Automation accessibility service.\n\nGo to Settings > Accessibility > Chess Automation and turn it ON.\n\nWithout this, the app can detect moves but cannot play them automatically.")
                .setPositiveButton("Open Settings") { dialog, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                        appendToChat("ℹ️ Enable accessibility service for auto-play\n")
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open settings", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Skip") { dialog, _ ->
                    appendToChat("⚠️ Auto-play disabled (accessibility service not enabled)\n")
                    dialog.dismiss()
                }
                .show()
        } else {
            appendToChat("✓ Accessibility service enabled\n")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            OVERLAY_PERMISSION_REQUEST_CODE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(this)) {
                        appendToChat("✓ Overlay permission granted\n")
                        Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
                    } else {
                        appendToChat("⚠️ Overlay permission denied\n")
                        Toast.makeText(this, "Overlay permission denied.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            
            SCREEN_CAPTURE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val boardX = sharedPreferences.getInt("pending_board_x", 12)
                    val boardY = sharedPreferences.getInt("pending_board_y", 502)
                    val boardSize = sharedPreferences.getInt("pending_board_size", 698)
                    
                    val serviceIntent = Intent(this, MoveDetectionOverlayService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                        putExtra("boardX", boardX)
                        putExtra("boardY", boardY)
                        putExtra("boardSize", boardSize)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    
                    Toast.makeText(this, "Move detection overlay started!", Toast.LENGTH_SHORT).show()
                    appendToChat("✓ Move detection active\n")
                    appendToChat("Board: x=$boardX, y=$boardY, size=$boardSize\n")
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showBaseUrlDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter Server URL")
        builder.setMessage("Please enter your chess engine server URL\n(e.g., https://xxxx.ngrok-free.app)")

        val input = EditText(this)
        input.hint = "https://xxxx.ngrok-free.app"
        builder.setView(input)

        builder.setPositiveButton("Save") { dialog, _ ->
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                baseUrl = url
                sharedPreferences.edit().putString("base_url", url).apply()
                appendToChat("🔥 Brutal Chess Engine Chat Started!\n")
                appendToChat("Connected to: $url\n")
                appendToChat("Press ⋮ to see options.\n")
                dialog.dismiss()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
            finish()
        }

        builder.setCancelable(false)
        builder.show()
    }

    private fun toggleMenu() {
        if (menuOverlay.visibility == View.VISIBLE) {
            hideMenu()
        } else {
            showMenu()
        }
    }

    private fun showMenu() {
        menuOverlay.visibility = View.VISIBLE
        
        menuHideRunnable?.let { handler.removeCallbacks(it) }
        
        menuHideRunnable = Runnable {
            hideMenu()
        }
        handler.postDelayed(menuHideRunnable!!, 5000)
    }

    private fun hideMenu() {
        menuOverlay.visibility = View.GONE
        menuHideRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun sendStartCommand() {
        if (baseUrl.isEmpty()) {
            appendToChat("⚠️ Error: Server URL not set\n")
            return
        }

        appendToChat("You: start\n")

        Thread {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/start")
                    .post("".toRequestBody(null))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.post {
                            appendToChat("⚠️ Error: ${e.message}\n")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: "No response"
                        handler.post {
                            if (response.isSuccessful) {
                                appendToChat("Engine: $responseBody\n")
                                gameStarted = true
                            } else {
                                appendToChat("⚠️ Error (${response.code}): $responseBody\n")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                handler.post {
                    appendToChat("⚠️ Error: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun sendColorCommand(color: String) {
        if (baseUrl.isEmpty()) {
            appendToChat("⚠️ Error: Server URL not set\n")
            return
        }

        if (!gameStarted) {
            appendToChat("⚠️ Type 'start' to begin the game first.\n")
            return
        }

        appendToChat("You: $color\n")

        Thread {
            try {
                val requestBody = color.toRequestBody(null)

                val request = Request.Builder()
                    .url("$baseUrl/move")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.post {
                            appendToChat("⚠️ Error: ${e.message}\n")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: "No response"
                        handler.post {
                            if (response.isSuccessful) {
                                appendToChat("Engine: $responseBody\n")
                            } else {
                                appendToChat("⚠️ Error (${response.code}): $responseBody\n")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                handler.post {
                    appendToChat("⚠️ Error: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun sendMove() {
        val move = inputEditText.text.toString().trim()
        if (move.isEmpty()) return

        if (baseUrl.isEmpty()) {
            appendToChat("⚠️ Error: Server URL not set\n")
            return
        }

        if (!gameStarted) {
            appendToChat("⚠️ Press 'Start' button in menu (⋮) to begin the game first.\n")
            return
        }

        appendToChat("You: $move\n")
        inputEditText.text.clear()

        Thread {
            try {
                val requestBody = move.toRequestBody(null)

                val request = Request.Builder()
                    .url("$baseUrl/move")
                    .post(requestBody)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        handler.post {
                            appendToChat("⚠️ Error: ${e.message}\n")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: "No response"
                        handler.post {
                            if (response.isSuccessful) {
                                appendToChat("Engine: $responseBody\n")
                            } else {
                                appendToChat("⚠️ Error (${response.code}): $responseBody\n")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                handler.post {
                    appendToChat("⚠️ Error: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun appendToChat(text: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        chatTextView.append("[$timestamp] $text")
    }

    override fun onDestroy() {
        super.onDestroy()
        menuHideRunnable?.let { handler.removeCallbacks(it) }
    }
}
