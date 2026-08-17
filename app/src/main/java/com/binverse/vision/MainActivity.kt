package com.binverse.vision

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.binverse.vision.databinding.ActivityMainBinding
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient()

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val file = photoFile
            if (success && file != null) {
                showPreview(file)
                classifyImage(file)
            } else {
                Toast.makeText(this, "Capture cancelled", Toast.LENGTH_SHORT).show()
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("binverse_prefs", Context.MODE_PRIVATE)

        binding.btnCapture.setOnClickListener { checkPermissionAndCapture() }
        binding.btnSendRobot.setOnClickListener { sendToRobot() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun backendUrl(): String {
        return prefs.getString("backend_url", getString(R.string.default_backend_url))
            ?: getString(R.string.default_backend_url)
    }

    // ---- Camera capture ----

    private fun checkPermissionAndCapture() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val imagesDir = File(cacheDir, "images")
        if (!imagesDir.exists()) imagesDir.mkdirs()
        val file = File(imagesDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        photoFile = file
        photoUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun showPreview(file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        binding.ivPreview.setImageBitmap(bitmap)
        binding.tvResult.visibility = View.GONE
        binding.btnSendRobot.visibility = View.GONE
    }

    /** Downscales the captured photo before upload — saves bandwidth and API tokens. */
    private fun compressImage(original: File): File {
        val bitmap = BitmapFactory.decodeFile(original.absolutePath)
        val maxDimension = 1024
        val ratio = minOf(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height,
            1f
        )
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }

        val compressedFile = File(original.parentFile, "compressed_${original.name}")
        FileOutputStream(compressedFile).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        return compressedFile
    }

    // ---- Networking ----

    private fun classifyImage(file: File) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvResult.visibility = View.GONE
        binding.btnSendRobot.visibility = View.GONE

        val uploadFile = try {
            compressImage(file)
        } catch (e: Exception) {
            file
        }

        val mediaType = "image/jpeg".toMediaTypeOrNull()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("image", uploadFile.name, uploadFile.asRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url("${backendUrl()}/api/classify")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string()
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    if (bodyString == null) {
                        Toast.makeText(this@MainActivity, "Empty response from server", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    try {
                        val json = JSONObject(bodyString)
                        if (json.optBoolean("success")) {
                            val data = json.getJSONObject("data")
                            displayResult(data)
                        } else {
                            val errorMsg = json.optString("error", "Unknown error")
                            val details = json.optString("details", "")
                            Toast.makeText(
                                this@MainActivity,
                                "Error: $errorMsg${if (details.isNotEmpty()) " ($details)" else ""}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Could not parse server response", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun displayResult(data: JSONObject) {
        val objectName = data.optString("object", "unknown")
        val category = data.optString("category", "unknown")
        val confidence = data.optDouble("confidence", 0.0) * 100
        val action = data.optString("action", "N/A")
        val description = data.optString("description", "")

        binding.tvResult.text = buildString {
            append("Object: $objectName\n")
            append("Category: ${category.uppercase()}\n")
            append("Confidence: ${"%.0f".format(confidence)}%\n")
            append("Action: $action\n\n")
            append(description)
        }
        binding.tvResult.visibility = View.VISIBLE
        binding.btnSendRobot.visibility = View.VISIBLE
    }

    private fun sendToRobot() {
        val request = Request.Builder()
            .url("${backendUrl()}/api/send-to-robot")
            .post("".toRequestBody(null))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Sent to robot!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Server error sending to robot", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    // ---- Settings ----

    private fun showSettingsDialog() {
        val input = EditText(this)
        input.setText(backendUrl())

        AlertDialog.Builder(this)
            .setTitle("Backend URL")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newUrl = input.text.toString().trim().trimEnd('/')
                if (newUrl.isNotEmpty()) {
                    prefs.edit().putString("backend_url", newUrl).apply()
                    Toast.makeText(this, "Backend URL updated", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
