package com.nzm.wallet.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class TxSimTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val thief = "0x2222222222222222222222222222222222222222"
    private val coll = "0x3333333333333333333333333333333333333333"

    // published event topics, typed out so a wrong signature string in TxSim fails here
    private val transfer = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private val transferSingle =
        "0xc3d58168c5ae7397731d063d5bbf3d657854427343f4c083240f7aacaa2d0f62"
    private val transferBatch = "0x4a39dc06d4c0dbc64b70af90fd698a233a518aa5d07e595d983b8c0526c8f7fb"
    private val approvalForAll =
        "0x17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31"

    private fun w(hex: String) = hex.removePrefix("0x").padStart(64, '0')
    private fun w(n: Int) = w(n.toString(16))
    private fun log(vararg topics: String, data: String = "0x") = JSONObject()
        .put("address", coll)
        .put("topics", JSONArray(topics.map { if (it.length == 66) it else "0x" + w(it) }))
        .put("data", data)

    @Test
    fun erc1155TransfersOutAreShown() {
        val single = log(transferSingle, thief, me, thief, data = "0x" + w(7) + w(3))
        val batch = log(
            transferBatch, thief, me, thief,
            data = "0x" + w(0x40) + w(0xa0) + w(2) + w(1) + w(2) + w(2) + w(5) + w(6)
        )
        val r = TxSim.readLogs(JSONArray().put(single).put(batch), me)
        assertEquals(
            listOf("−3 × #7", "−5 × #1", "−6 × #2"),
            r.nfts.map { it.substringBefore(" (") })
    }

    @Test
    fun approvalForAllIsShownAndRevokeIsNot() {
        val give = log(approvalForAll, me, thief, data = "0x" + w(1))
        val revoke = log(approvalForAll, me, thief, data = "0x" + w(0))
        val r = TxSim.readLogs(JSONArray().put(give).put(revoke), me)
        assertEquals(1, r.nftApprovals.size)
        assertTrue(r.nftApprovals[0].unlimited && r.nftApprovals[0].nft)
    }

    @Test
    fun erc20AndErc721StillWork() {
        val out = log(transfer, me, thief, data = "0x" + w(100))
        val nft = log(transfer, thief, me, w(9))
        val r = TxSim.readLogs(JSONArray().put(out).put(nft), me)
        assertEquals(BigInteger.valueOf(-100), r.deltas[coll])
        assertEquals("+NFT #9", r.nfts.single().substringBefore(" ("))
    }
}
