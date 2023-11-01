package edu.gatech.ccg.recordthesehands.splash

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import edu.gatech.ccg.recordthesehands.R
import edu.gatech.ccg.recordthesehands.databinding.ActivityLoadDataBinding
import edu.gatech.ccg.recordthesehands.hapticFeedbackOnTouchListener
import edu.gatech.ccg.recordthesehands.upload.DataManager
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class LoadDataActivity : ComponentActivity() {
  companion object {
    private val TAG = LoadDataActivity::class.simpleName
  }

  lateinit var dataManager: DataManager

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val binding = ActivityLoadDataBinding.inflate(layoutInflater)
    setContentView(binding.root)

    dataManager = DataManager(applicationContext)

    val createAccountButton = findViewById<Button>(R.id.createAccountButton)

    createAccountButton.setOnTouchListener(::hapticFeedbackOnTouchListener)
    createAccountButton.setOnClickListener {
      val username = findViewById<EditText>(R.id.usernameTextField).text.toString()
      val adminPassword = findViewById<EditText>(R.id.adminPasswordTextField).text.toString()
      lifecycleScope.launch {
        thread {  // Don't run network on UI thread.
          val result = dataManager.createAccount(username, adminPassword)
          runOnUiThread {
            AlertDialog.Builder(this@LoadDataActivity).apply {
              if (result) {
                setTitle("Success")
                setMessage("Created account for \"$username\" and stored credentials.")
              } else {
                setTitle("Failed")
                setMessage("Failed to Create account for \"$username\".")
              }
              setPositiveButton("OK") { _, _ -> }
              create()
              show()
            }
          }
        }
      }
    }
  }
}