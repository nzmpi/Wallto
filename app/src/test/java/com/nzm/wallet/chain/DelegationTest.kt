package com.nzm.wallet.chain

import com.nzm.wallet.dbs.Chain
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.SignedRawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionDecoder
import org.web3j.crypto.transaction.type.Transaction7702
import org.web3j.rlp.RlpEncoder
import org.web3j.rlp.RlpList
import org.web3j.rlp.RlpString
import org.web3j.utils.Numeric
import java.math.BigInteger

class DelegationTest {
    // well-known test key (Hardhat account 0), never holds real funds
    private val creds = Credentials.create("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80")
    private val chain = Chain(11155111, "Sepolia", "https://example.invalid", "ETH")
    private val delegate = "0x000000009B1D0aF20D8C6d0A44e162d11F9b8f00"
    private val nonce = BigInteger.valueOf(7)
    private val plan = TxPlan(
        chain, creds.address, creds.address, BigInteger.ZERO, nonce, Delegation.GAS_LIMIT,
        Fee.Eip1559(BigInteger.valueOf(30_000_000_000), BigInteger.valueOf(1_000_000_000))
    )

    @Test
    fun web3jDecodesOurTransactionAsSignedByTheAccount() {
        val raw = Delegation.encode(plan, delegate, creds)
        assertEquals(0x04, raw[0].toInt())

        val decoded = TransactionDecoder.decode(Numeric.toHexString(raw)) as SignedRawTransaction
        assertTrue(decoded.from.equals(creds.address, true))
        val tx = decoded.transaction as Transaction7702
        assertEquals(chain.chainId, tx.chainId)
        assertEquals(nonce, tx.nonce)
        assertTrue(tx.to.equals(creds.address, true))
        assertEquals(BigInteger.ZERO, tx.value)

        val auth = tx.authorizationList.single()
        assertEquals(BigInteger.valueOf(chain.chainId), auth.chainId) // never chain 0
        assertTrue(auth.address.equals(delegate, true))
        assertEquals(nonce + BigInteger.ONE, auth.nonce)              // sender's nonce is bumped first
    }

    @Test
    fun authorizationIsSignedByTheAccount() {
        val tx = (TransactionDecoder.decode(
            Numeric.toHexString(Delegation.encode(plan, delegate, creds))
        ) as SignedRawTransaction).transaction as Transaction7702
        val a = tx.authorizationList.single()
        // EIP-7702: keccak(0x05 || rlp([chain_id, address, nonce]))
        val hash = Hash.sha3(
            byteArrayOf(0x05) + RlpEncoder.encode(
                RlpList(
                    RlpString.create(a.chainId),
                    RlpString.create(Numeric.hexStringToByteArray(a.address)),
                    RlpString.create(a.nonce)
                )
            )
        )
        val sig = Sign.SignatureData(
            (27 + a.yParity.toInt()).toByte(),
            Numeric.toBytesPadded(a.r, 32),
            Numeric.toBytesPadded(a.s, 32)
        )
        val key = Sign.signedMessageHashToKey(hash, sig)
        assertArrayEquals(
            Numeric.toBytesPadded(creds.ecKeyPair.publicKey, 64),
            Numeric.toBytesPadded(key, 64)
        )
    }

    @Test
    fun refusesAnythingButASelfCallWithoutValue() {
        assertThrows(IllegalArgumentException::class.java) {
            Delegation.encode(plan.copy(to = delegate), delegate, creds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Delegation.encode(plan.copy(value = BigInteger.ONE), delegate, creds)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Delegation.encode(plan, "0x1234", creds)
        }
    }
}
