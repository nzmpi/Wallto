package com.nzm.wallet.chain

import com.nzm.wallet.chain.Rpc.ethCall
import com.nzm.wallet.dbs.Token
import com.nzm.wallet.utils.Helpers.isValidAddress
import com.nzm.wallet.utils.Helpers.sanitize
import com.nzm.wallet.utils.Helpers.toUtf8StringOrNull
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
import org.web3j.utils.Numeric
import java.io.IOException
import java.math.BigInteger

object Erc20 {
    private fun decode(fn: Function, raw: ByteArray): List<Type<*>> =
        FunctionReturnDecoder.decode(
            Numeric.toHexString(raw),
            fn.outputParameters
        )

    suspend fun balanceOf(
        rpcUrl: String,
        token: String,
        owner: String
    ): BigInteger {
        val fn = Function(
            "balanceOf",
            listOf<Type<*>>(Address(owner)),
            listOf(object : TypeReference<Uint256>() {})
        )
        val out = decode(fn, ethCall(rpcUrl, to = token, fn = fn))
        if (out.isEmpty()) throw IOException("Not an ERC-20 (no balanceOf result)")
        return out[0].value as BigInteger
    }

    private suspend fun readString(rpcUrl: String, token: String, method: String): String {
        val fn =
            Function(method, emptyList<Type<*>>(), listOf(object : TypeReference<Utf8String>() {}))
        val raw = ethCall(rpcUrl, to = token, fn = fn)
        val s = raw.toUtf8StringOrNull()
        if (!s.isNullOrEmpty()) return s
        // old tokens (MKR) return bytes32 instead of string
        if (raw.size == 32) return String(
            raw.takeWhile { it != 0.toByte() }.toByteArray(),
            Charsets.UTF_8
        )
        throw IOException("Token has no $method()")
    }

    private suspend fun readDecimals(rpcUrl: String, token: String): Int {
        val fn =
            Function("decimals", emptyList<Type<*>>(), listOf(object : TypeReference<Uint8>() {}))
        val out = decode(fn, ethCall(rpcUrl, to = token, fn = fn))
        if (out.isEmpty()) throw IOException("Token has no decimals()")
        val d = (out[0].value as BigInteger).toInt()
        require(d in 0..36) { "Unreasonable decimals: $d" }
        return d
    }

    suspend fun fetchInfo(rpcUrl: String, chainId: Long, address: String): Token {
        require(isValidAddress(address)) { "Invalid contract address or checksum" }
        val addr = Keys.toChecksumAddress(address.trim())
        require(Rpc.isContract(rpcUrl, addr)) { "No contract at this address on this chain" }
        try {
            val symbol = readString(rpcUrl, addr, "symbol").sanitize(32)
            val name = readString(rpcUrl, addr, "name").sanitize(64)
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
