package com.uniq.airplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import com.uniq.airplay.util.NetInfo

/**
 * Standby screen (SPEC §5F.1): TV name, IP, network type, server status.
 * The foreground service is started here and intentionally NOT stopped when
 * this activity goes away — mirroring must survive activity transitions.
 */
class MainActivity : Activity() {

    private lateinit var nameText: TextView
    private lateinit var ipText: TextView
    private lateinit var netText: TextView
    private lateinit var statusText: TextView
    private lateinit var testButton: Button
    private var uiBound = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Dev hook: `am start --ez dev_open_mirroring 1` jumps straight to the
        // mirroring surface (handy for Phase 3/4 testing without D-pad hunting).
        if (intent?.getBooleanExtra("dev_open_mirroring", false) == true) {
            Log.i(TAG, "dev hook: opening mirroring surface")
            startActivity(Intent(this, MirroringActivity::class.java))
            return
        }

        nameText = findViewById(R.id.row_name_value)
        ipText = findViewById(R.id.row_ip_value)
        netText = findViewById(R.id.row_net_value)
        statusText = findViewById(R.id.row_status_value)
        testButton = findViewById(R.id.test_mirroring_button)
        uiBound = true

        testButton.setOnClickListener {
            Log.i(TAG, "opening MirroringActivity (manual Phase-1 test)")
            startActivity(Intent(this, MirroringActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        AirPlayService.start(this)
        if (uiBound) refresh()
    }

    private fun refresh() {
        val prefs = getSharedPreferences("com.uniq.airplay", MODE_PRIVATE)
        nameText.text = prefs.getString("broadcast_name", "UNIQ TV Airplay")
        ipText.text = NetInfo.localIpv4() ?: "not found"
        netText.text = NetInfo.networkTypeLabel(this)
        statusText.text = when {
            AirPlayService.serverRunning ->
                "Native core: ${AirPlayNative.coreVersion()}\nServer: RUNNING — pick \"$nameText.text\" in Screen Mirroring"
            AirPlayNative.isAvailable() ->
                "Native core: ${AirPlayNative.coreVersion()}\nServer: not started"
            else -> "Native core: unavailable\nmDNS: not started"
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
