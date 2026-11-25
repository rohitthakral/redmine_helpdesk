package com.leadcapture.odoo

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.leadcapture.odoo.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = SettingsStore(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            val c = store.load()
            binding.etUrl.setText(c.url)
            binding.etDb.setText(c.db)
            binding.etLogin.setText(c.login)
            binding.etApiKey.setText(c.apiKey)
        }

        binding.btnSave.setOnClickListener {
            val c = readForm() ?: return@setOnClickListener
            store.save(c)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        binding.btnTest.setOnClickListener { test() }
    }

    private fun readForm(): OdooConfig? {
        val c = OdooConfig(
            url = binding.etUrl.text?.toString().orEmpty().trim(),
            db = binding.etDb.text?.toString().orEmpty().trim(),
            login = binding.etLogin.text?.toString().orEmpty().trim(),
            apiKey = binding.etApiKey.text?.toString().orEmpty().trim(),
        )
        val required = getString(R.string.required)
        binding.tilUrl.error = if (c.url.isEmpty()) required else null
        binding.tilDb.error = if (c.db.isEmpty()) required else null
        binding.tilLogin.error = if (c.login.isEmpty()) required else null
        binding.tilApiKey.error = if (c.apiKey.isEmpty()) required else null
        if (!c.isComplete) return null
        val normalized = c.copy(url = c.baseUrl)
        binding.etUrl.setText(normalized.url)
        return normalized
    }

    private fun test() {
        val c = readForm() ?: return
        binding.btnTest.isEnabled = false
        binding.testResult.setText(R.string.testing)
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val info = OdooClient(c).testConnection()
                    if (info.canCreateLeads == false) getString(R.string.connection_ok_no_rights, c.login, info.serverVersion)
                    else getString(R.string.connection_ok, c.login, info.serverVersion)
                } catch (e: Exception) {
                    "✗ " + (e.message ?: e.javaClass.simpleName)
                }
            }
            binding.testResult.text = text
            binding.btnTest.isEnabled = true
        }
    }
}
