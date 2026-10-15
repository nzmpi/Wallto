package com.nzm.wallet.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.http.HttpService
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

object Rpc {
    suspend fun chainId(rpcUrl: String): Long = withContext(Dispatchers.IO) {
        val web3 = Web3j.build(HttpService(rpcUrl))
        try {
            val r = web3.ethChainId().send()
            if (r.hasError()) throw IOException(r.error.message)
            r.chainId.longValueExact()
        } finally {
            web3.shutdown()
        }
    }

    suspend fun balance(rpcUrl: String, address: String): BigInteger = withContext(Dispatchers.IO) {
        val web3 = Web3j.build(HttpService(rpcUrl))
        try {
            val r = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send()
            if (r.hasError()) throw IOException(r.error.message)
            r.balance
        } finally {
            web3.shutdown()
        }
    }

    fun formatUnits(value: BigInteger, decimals: Int, maxFraction: Int = 6): String =
        BigDecimal(value).movePointLeft(decimals)
            .setScale(maxFraction, RoundingMode.DOWN)
            .stripTrailingZeros()
            .toPlainString()
}