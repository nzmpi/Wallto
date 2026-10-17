package com.nzm.wallet

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
        setContent { WalletTheme { WalletGate(this, vault, vm) } }
    }
}