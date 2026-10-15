package com.nzm.wallet.chain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint8
import org.web3j.crypto.Keys
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.http.HttpService
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger

const val ZERO_ADDRESS = "0x0000000000000000000000000000000000000000"

object Erc20 {
    private suspend fun ethCall(rpcUrl: String, to: String, fn: Function): String =
        withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val r = web3.ethCall(
                    Transaction.createEthCallTransaction(null, to, FunctionEncoder.encode(fn)),
                    DefaultBlockParameterName.LATEST
                ).send()
                if (r.hasError()) throw IOException(r.error.message)
                r.value ?: "0x"
            } finally {
                web3.shutdown()
            }
        }

    private fun decode(fn: Function, raw: String): List<Type<*>> =
        FunctionReturnDecoder.decode(raw, fn.outputParameters)

    suspend fun balanceOf(rpcUrl: String, token: String, owner: String): BigInteger {
        val fn = Function(
            "balanceOf",
            listOf<Type<*>>(Address(owner)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val out = decode(fn, ethCall(rpcUrl, token, fn))
        if (out.isEmpty()) throw IOException("Not an ERC-20 (no balanceOf result)")
        return out[0].value as BigInteger
    }

    private suspend fun readString(rpcUrl: String, token: String, method: String): String {
        val fn = Function(method, emptyList<Type<*>>(), listOf(object : TypeReference<Utf8String>() {}))
        val raw = ethCall(rpcUrl, token, fn)
        val s = try { decode(fn, raw).firstOrNull()?.value as? String } catch (_: Exception) { null }
        if (!s.isNullOrEmpty()) return s
        // old tokens (MKR) return bytes32 instead of string
        val bytes = Numeric.hexStringToByteArray(raw)
        if (bytes.size == 32) return String(bytes.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8)
        throw IOException("Token has no $method()")
    }

    private suspend fun readDecimals(rpcUrl: String, token: String): Int {
        val fn = Function("decimals", emptyList<Type<*>>(), listOf(object : TypeReference<Uint8>() {}))
        val out = decode(fn, ethCall(rpcUrl, token, fn))
        if (out.isEmpty()) throw IOException("Token has no decimals()")
        val d = (out[0].value as BigInteger).toInt()
        require(d in 0..36) { "Unreasonable decimals: $d" }
        return d
    }

    private suspend fun isContract(rpcUrl: String, address: String): Boolean =
        withContext(Dispatchers.IO) {
            val web3 = Web3j.build(HttpService(rpcUrl))
            try {
                val r = web3.ethGetCode(address, DefaultBlockParameterName.LATEST).send()
                if (r.hasError()) throw IOException(r.error.message)
                r.code != null && r.code != "0x"
            } finally {
                web3.shutdown()
            }
        }

    // strips control + bidi/format chars, caps length: token cannot spoof UI text
    private fun clean(s: String, max: Int): String =
        s.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }.trim().take(max)

    suspend fun fetchInfo(rpcUrl: String, chainId: Long, address: String): Token {
        require(SendTx.isValidAddress(address)) { "Invalid contract address or checksum" }
        val addr = Keys.toChecksumAddress(address.trim())
        require(isContract(rpcUrl, addr)) { "No contract at this address on this chain" }
        try {
            val symbol = clean(readString(rpcUrl, addr, "symbol"), 32)
            val name = clean(readString(rpcUrl, addr, "name"), 64)
            val decimals = readDecimals(rpcUrl, addr)
            require(symbol.isNotEmpty()) { "Token symbol is empty" }
            return Token(chainId, addr, symbol, name.ifEmpty { symbol }, decimals)
        } catch (e: IOException) {
            throw IOException("Not a readable ERC-20: ${e.message}")
        }
    }

    fun encodeTransfer(to: String, amount: BigInteger): String =
        FunctionEncoder.encode(
            Function(
                "transfer",
                listOf<Type<*>>(Address(to), Uint256(amount)),
                listOf(object : TypeReference<Bool>() {})
            )
        )
}
