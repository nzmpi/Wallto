package com.nzm.wallet.chain

import com.nzm.wallet.abi.AbiCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.crypto.Keys
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
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

    fun describeError(e: Response.Error): String =
        AbiCodec.decodeRevert(e.data) ?: e.message

    suspend fun ethCall(
        rpcUrl: String,
        from: String? = null,
        to: String,
        fn: Function? = null,
        data: ByteArray? = null
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require((fn == null) xor (data == null)) {
                "Provide either fn or data"
            }
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val callData = fn?.let {
                    FunctionEncoder.encode(it)
                } ?: Numeric.toHexString(data!!)
                val r = web3.ethCall(
                    Transaction.createEthCallTransaction(from, to, callData),
                    DefaultBlockParameterName.LATEST
                ).send()
                if (r.hasError()) throw IOException(describeError(r.error))
                Numeric.hexStringToByteArray(r.value ?: "0x")
            } finally {
                web3.shutdown()
            }
        }

    suspend fun isContract(rpcUrl: String, address: String): Boolean =
        withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val r = web3.ethGetCode(address, DefaultBlockParameterName.LATEST).send()
                if (r.hasError()) throw IOException(describeError(r.error))
                r.code != null && r.code != "0x"
            } finally {
                web3.shutdown()
            }
        }
}