package com.deckkey.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.deckkey.R
import com.deckkey.core.prefs.Settings as KbSettings
import com.deckkey.core.prefs.SettingsRepository
import com.deckkey.core.theme.Themes
import com.deckkey.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SettingsRepository
    private lateinit var themeAdapter: ThemeAdapter

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshStatus()
            Toast.makeText(
                this,
                if (granted) "Microphone enabled — tap 🎤 on the keyboard to voice type"
                else "Microphone denied — voice typing won't work",
                Toast.LENGTH_SHORT,
            ).show()
        }

    private var isPro: Boolean = false

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            if (!isPro) {
                Toast.makeText(this, "Custom background is a PRO feature. Please upgrade!", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val localPath = saveUriToInternalStorage(uri)
            if (localPath != null) {
                save { it.copy(backgroundUri = localPath) }
                Toast.makeText(this, "Background image set successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to load background image", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SettingsRepository(applicationContext)

        val deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "DECKKEY-DEVICE"
        binding.lblDeviceId.text = "Your Device ID: $deviceId"

        binding.btnActivatePro.setOnClickListener {
            val codeEntered = binding.txtActivationCode.text.toString().trim().uppercase()
            val expectedCode = generateActivationCode(deviceId)
            if (codeEntered == expectedCode) {
                save { it.copy(isPro = true) }
                Toast.makeText(this, "PRO PLAN ACTIVATED FOREVER! Thank you!", Toast.LENGTH_LONG).show()
                binding.txtActivationCode.text.clear()
            } else {
                Toast.makeText(this, "Invalid Activation Code. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.btnSelect.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        binding.btnMic.setOnClickListener {
            if (hasMicPermission()) {
                Toast.makeText(this, "Microphone already granted ✓", Toast.LENGTH_SHORT).show()
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.btnPickBg.setOnClickListener { galleryLauncher.launch("image/*") }
        binding.btnClearBg.setOnClickListener {
            save { it.copy(backgroundUri = "") }
            Toast.makeText(this, "Background cleared", Toast.LENGTH_SHORT).show()
        }

        setupThemePicker()
        bindSettings()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupThemePicker() {
        themeAdapter = ThemeAdapter(Themes.all, Themes.default.id) { theme ->
            save { it.copy(themeId = theme.id) }
        }
        binding.rvThemes.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = themeAdapter
        }
    }

    private fun refreshStatus() {
        binding.statusEnabled.text =
            getString(if (isImeEnabled()) R.string.status_enabled else R.string.status_disabled)
        binding.statusActive.text =
            getString(if (isImeActive()) R.string.status_active else R.string.status_inactive)
        binding.statusMic.text =
            getString(if (hasMicPermission()) R.string.status_mic_granted else R.string.status_mic_denied)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun bindSettings() {
        lifecycleScope.launch {
            repo.settings.collect { s ->
                isPro = s.isPro
                if (isPro) {
                    binding.lblProStatus.text = "Status: PRO (Activated) ✓"
                    binding.lblProStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    binding.layoutActivationBox.visibility = View.GONE
                    binding.lblProDesc.text = "Thank you for upgrading! All Pro features are unlocked."
                } else {
                    binding.lblProStatus.text = "Status: LITE (Free Plan)"
                    binding.lblProStatus.setTextColor(android.graphics.Color.RED)
                    binding.layoutActivationBox.visibility = View.VISIBLE
                    binding.lblProDesc.text = "Unlock Urdu/Chinese layout switching, custom backgrounds, and advanced customizations. Pay manually to 03163347485 and enter code."
                }

                binding.cbHaptics.isChecked = s.haptics
                binding.cbSound.isChecked = s.sound
                binding.cbPopup.isChecked = s.previewPopup

                binding.seekKeyHeight.max = KbSettings.MAX_KEY_HEIGHT - KbSettings.MIN_KEY_HEIGHT
                binding.seekKeyHeight.progress = s.keyHeightDp - KbSettings.MIN_KEY_HEIGHT
                binding.lblKeyHeight.text = getString(R.string.pref_key_height) + ": ${s.keyHeightDp}"

                binding.seekRepeatInterval.max = KbSettings.MAX_REPEAT_INTERVAL - KbSettings.MIN_REPEAT_INTERVAL
                binding.seekRepeatInterval.progress = s.repeatIntervalMs - KbSettings.MIN_REPEAT_INTERVAL
                binding.lblRepeatInterval.text = getString(R.string.pref_repeat_interval) + ": ${s.repeatIntervalMs}"

                themeAdapter.setSelected(s.themeId)

                binding.seekBgDim.max = 100
                binding.seekBgDim.progress = s.backgroundDim
                binding.lblBgDim.text = getString(R.string.bg_dim_label, s.backgroundDim)
            }
        }

        binding.cbHaptics.setOnCheckedChangeListener { _, v -> save { it.copy(haptics = v) } }
        binding.cbSound.setOnCheckedChangeListener { _, v -> save { it.copy(sound = v) } }
        binding.cbPopup.setOnCheckedChangeListener { _, v -> save { it.copy(previewPopup = v) } }

        binding.seekKeyHeight.setOnSeekBarChangeListener(simpleSeek { p ->
            val h = KbSettings.MIN_KEY_HEIGHT + p
            binding.lblKeyHeight.text = getString(R.string.pref_key_height) + ": $h"
            save { it.copy(keyHeightDp = h) }
        })
        binding.seekRepeatInterval.setOnSeekBarChangeListener(simpleSeek { p ->
            val v = KbSettings.MIN_REPEAT_INTERVAL + p
            binding.lblRepeatInterval.text = getString(R.string.pref_repeat_interval) + ": $v"
            save { it.copy(repeatIntervalMs = v) }
        })
        binding.seekBgDim.setOnSeekBarChangeListener(simpleSeek { p ->
            binding.lblBgDim.text = getString(R.string.bg_dim_label, p)
            save { it.copy(backgroundDim = p) }
        })
    }

    private fun save(transform: (KbSettings) -> KbSettings) {
        lifecycleScope.launch { repo.update(transform) }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeActive(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return id?.startsWith(packageName) == true
    }

    private fun saveUriToInternalStorage(uri: Uri): String? {
        return try {
            val file = java.io.File(filesDir, "keyboard_background.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to save image to internal storage", e)
            null
        }
    }

    private fun generateActivationCode(deviceId: String): String {
        val salt = "DECKKEY-PRO-SALT"
        val bytes = (deviceId + salt).toByteArray()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.take(4).joinToString("") { "%02X".format(it) }
    }
}
