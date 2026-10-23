package com.nzm.wallet

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.toArgb
import androidx.fragment.app.FragmentActivity
import com.nzm.wallet.storage.Vault
import com.nzm.wallet.ui.WalletGate
import com.nzm.wallet.ui.WalletViewModel
import com.nzm.wallet.ui.theme.Dim
import com.nzm.wallet.ui.theme.WalletTheme
import com.nzm.wallet.wc.WcService

class MainActivity : FragmentActivity() {
    private val vm: WalletViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Dim.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Dim.Nav.toArgb())
        )
        // blocks screenshots, screen recording, recents thumbnail
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val vault = Vault(applicationContext)
        // only on a real launch: after a rotation or theme change the same intent comes back
        if (savedInstanceState == null) takeWcLink(intent)
        setContent { WalletTheme { WalletGate(this, vault, vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeWcLink(intent)
    }

    override fun onStart() {
        super.onStart()
        WcService.onForeground()
    }

    /** wc: links from sites. Kept until the wallet is unlocked and WalletConnect is ready. */
    private fun takeWcLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme.equals("wc", ignoreCase = true)) vm.pendingWcUri = d.toString()
    }
}
