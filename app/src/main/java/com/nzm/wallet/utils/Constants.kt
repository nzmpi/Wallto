package com.nzm.wallet.utils

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import java.math.BigInteger

object Constants {
    val TWO_256: BigInteger = BigInteger.ONE.shiftLeft(256)
    val TWO_255: BigInteger = BigInteger.ONE.shiftLeft(255)
    val MAX_UINT = TWO_256 - BigInteger.ONE
    const val ZERO = "0x0000000000000000000000000000000000000000"
    const val PROVIDER = "AndroidKeyStore"
    const val TRANSFORM = "AES/GCM/NoPadding"
    val SHAPE = RoundedCornerShape(20.dp)
}
