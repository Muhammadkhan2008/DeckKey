package com.deckkey.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.deckkey.R
import com.deckkey.core.prefs.Settings as KbSettings
import com.deckkey.core.prefs.SettingsRepository
import com.deckkey.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Setup wizard + settings screen.
 *
 * Three jobs:
 *  1. Walk the user through enabling DeckKey in system settings and selecting it.
 *  2. Show live enabled/active status.
 *  3. Edit [KbSettings] (haptics, popup, key height, repeat speed), persisted to DataStore;
 *     the running IME picks up changes immediately via its settings Flow.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: SettingsRepository

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SettingsRepository(applicationContext)

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

        bindSettings()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isImeEnabled()
        val active = isImeActive()
        binding.statusEnabled.text =
            getString(if (enabled) R.string.status_enabled else R.string.status_disabled)
        binding.statusActive.text =
            getString(if (active) R.string.status_active else R.string.status_inactive)
        binding.statusMic.text =
            getString(if (hasMicPermission()) R.string.status_mic_granted else R.string.status_mic_denied)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun bindSettings() {
        lifecycleScope.launch {
            val s = repo.settings.first()
            binding.cbHaptics.isChecked = s.haptics
            binding.cbSound.isChecked = s.sound
            binding.cbPopup.isChecked = s.previewPopup

            binding.seekKeyHeight.max = KbSettings.MAX_KEY_HEIGHT - KbSettings.MIN_KEY_HEIGHT
            binding.seekKeyHeight.progress = s.keyHeightDp - KbSettings.MIN_KEY_HEIGHT
            binding.lblKeyHeight.text = getString(R.string.pref_key_height) + ": ${s.keyHeightDp}"

            binding.seekRepeatInterval.max = KbSettings.MAX_REPEAT_INTERVAL - KbSettings.MIN_REPEAT_INTERVAL
            binding.seekRepeatInterval.progress = s.repeatIntervalMs - KbSettings.MIN_REPEAT_INTERVAL
            binding.lblRepeatInterval.text = getString(R.string.pref_repeat_interval) + ": ${s.repeatIntervalMs}"
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
    }

    private fun save(transform: (KbSettings) -> KbSettings) {
        lifecycleScope.launch { repo.update(transform) }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
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
}
