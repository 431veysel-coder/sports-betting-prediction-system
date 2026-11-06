package com.openminis.betting.app

import com.openminis.betting.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for data models and basic logic
 */
class ModelTests {

    @Test
    fun testBankrollDefaultValues() {
        val bankroll = Bankroll()
        assertEquals(1000.0, bankroll.currentBalance, 0.001)
        assertEquals(1000.0, bankroll.availableBalance, 0.001)
        assertEquals(0.0, bankroll.reservedBalance, 0.001)
        assertEquals(0.0, bankroll.totalProfit, 0.001)
        assertEquals(0.0, bankroll.roi, 0.001)
    }

    @Test
    fun testBankrollWithCustomValues() {
        val bankroll = Bankroll(
            currentBalance = 1500.0,
            availableBalance = 1200.0,
            reservedBalance = 300.0,
            totalStaked = 5000.0,
            totalWon = 5500.0,
            totalLost = 4500.0,
            totalProfit = 1000.0,
            roi = 20.0,
            maxDrawdown = 200.0,
            peakBalance = 2000.0
        )
        assertEquals(1500.0, bankroll.currentBalance, 0.001)
        assertEquals(1200.0, bankroll.availableBalance, 0.001)
        assertEquals(300.0, bankroll.reservedBalance, 0.001)
        assertEquals(1000.0, bankroll.totalProfit, 0.001)
        assertEquals(20.0, bankroll.roi, 0.001)
    }

    @Test
    fun testMatchModel() {
        val match = Match(
            id = "test_match_1",
            sportId = "football",
            leagueId = "bl1",
            homeTeamId = "team_1",
            awayTeamId = "team_2",
            homeTeam = "Bayern Munich",
            awayTeam = "Borussia Dortmund",
            league = "Bundesliga",
            matchDate = "2024-01-15 15:30:00",
            kickoffTs = 1705331400,
            status = "SCHEDULED"
        )
        assertEquals("test_match_1", match.id)
        assertEquals("Bayern Munich", match.homeTeam)
        assertEquals("Borussia Dortmund", match.awayTeam)
        assertEquals("SCHEDULED", match.status)
    }

    @Test
    fun testOddsModel() {
        val odds = Odds(
            id = 1,
            matchId = "test_match_1",
            bookmaker = "Bet365",
            marketType = "1X2",
            homeWin = 1.85,
            draw = 3.50,
            awayWin = 4.20,
            over25 = 1.75,
            under25 = 2.05,
            bttsYes = 1.65,
            bttsNo = 2.20,
            timestamp = "2024-01-15 10:00:00",
            isClosing = 0
        )
        assertEquals(1.85, odds.homeWin, 0.001)
        assertEquals(3.50, odds.draw, 0.001)
        assertEquals(4.20, odds.awayWin, 0.001)
    }

    @Test
    fun testPredictionMetrics() {
        val prediction = Prediction(
            id = 1,
            matchId = "test_match_1",
            modelId = "ensemble_v1",
            predictedOutcome = "HOME_WIN",
            marketType = "1X2",
            successProb = 0.65,
            confidence = 0.75,
            honesty = 0.85,
            reliability = 0.80,
            valueScore = 1.15,
            riskScore = 0.25,
            qualityScore = 0.90,
            consistency = 0.70,
            marketEfficiency = 0.85,
            scenarioScore = 0.75,
            finalScore = 78.5,
            kellyFraction = 0.032,
            recommendedStake = 32.0
        )
        
        assertEquals("HOME_WIN", prediction.predictedOutcome)
        assertEquals(0.65, prediction.successProb, 0.001)
        assertEquals(78.5, prediction.finalScore, 0.01)
        assertTrue(prediction.confidence > 0.5)
        assertTrue(prediction.valueScore > 1.0)
        assertTrue(prediction.riskScore < 0.5)
    }

    @Test
    fun testCouponModel() {
        val coupon = Coupon(
            id = "SNG_20240115_abc123",
            couponType = "SINGLE",
            totalOdds = 1.85,
            stake = 10.0,
            potentialReturn = 18.5,
            status = "PENDING",
            totalLegs = 1,
            wonLegs = 0,
            strategyTag = "VALUE",
            createdAt = "2024-01-15 10:00:00"
        )
        
        assertEquals("SINGLE", coupon.couponType)
        assertEquals(1.85, coupon.totalOdds, 0.001)
        assertEquals(10.0, coupon.stake, 0.001)
        assertEquals(18.5, coupon.potentialReturn, 0.001)
        assertEquals("PENDING", coupon.status)
    }

    @Test
    fun testCouponLegModel() {
        val leg = CouponLeg(
            id = 1,
            couponId = "SNG_20240115_abc123",
            matchId = "test_match_1",
            predictionId = 1,
            legOrder = 1,
            selection = "HOME_WIN",
            marketType = "1X2",
            odds = 1.85,
            stakeAllocated = 10.0,
            status = "PENDING"
        )
        
        assertEquals("HOME_WIN", leg.selection)
        assertEquals(1.85, leg.odds, 0.001)
        assertEquals(10.0, leg.stakeAllocated, 0.001)
    }

    @Test
    fun testBankrollTransaction() {
        val txn = BankrollTransaction(
            id = 1,
            amount = 100.0,
            txnType = "DEPOSIT",
            referenceType = "MANUAL",
            referenceId = "manual_1",
            balanceBefore = 1000.0,
            balanceAfter = 1100.0,
            note = "Test deposit",
            timestamp = "2024-01-15 10:00:00"
        )
        
        assertEquals(100.0, txn.amount, 0.001)
        assertEquals("DEPOSIT", txn.txnType)
        assertEquals(1100.0, txn.balanceAfter, 0.001)
    }
}