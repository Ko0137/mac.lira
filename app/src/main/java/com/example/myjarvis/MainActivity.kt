package com.example.myjarvis

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settingsButton = findViewById<ImageButton>(R.id.settingsButton)
        val chatDisplay = findViewById<TextView>(R.id.chatDisplay)
        val inputField = findViewById<EditText>(R.id.inputField)
        val sendButton = findViewById<Button>(R.id.sendButton)

        // Открытие активити настроек по клику на шестеренку
        settingsButton.setOnClickListener {
            val intent = Intent(MainActivity.this, SettingsActivity::class.java)
            startActivity(intent)
        }

        sendButton.setOnClickListener {
            val text = inputField.text.toString().trim()
            if (text.isNotEmpty()) {
                chatDisplay.append("\nВы: $text\n")
                chatDisplay.append("[L.I.R.A.]: Обрабатываю запрос...\n")
                inputField.setText("")
            }
        }
    }
}
