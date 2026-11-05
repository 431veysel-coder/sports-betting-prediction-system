package com.openminis.betting.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.model.*
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class DataRepository private constructor(context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    companion object {
        @Suppress("UNUSED_PARAMETER")
        @Volatile private var instance: DataRepository? = null
        fun getInstance(context: Context): DataRepository = instance ?: synchronized(this) {
            instance ?: DataRepository(context.applicationContext).also { instance = it }
        }
    }

    // ============================================
    // BANKROLL OPERATIONS
    // ============================================
    fun getBankroll(): Bankroll {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM bankroll WHERE id = 1", null)
        return try {
            if (cursor.moveToFirst()) {
                cursorToBankroll(cursor)
            } else {
                Bankroll()
            }
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("DataRepository", "getBankroll failed", e)
            Bankroll()
        } finally {
            cursor.close()
        }
    }

    fun updateBankroll(bankroll: Bankroll) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("current_balance", bankroll.currentBalance)
            put("available_balance", bankroll.availableBalance)
            put("reserved_balance", bankroll.reservedBalance)
            put("total_staked", bankroll.totalStaked)
            put("total_won", bankroll.totalWon)
            put("total_lost", bankroll.totalLost)
            put("total_profit", bankroll.totalProfit)
            put("roi", bankroll.roi)
            put("max_drawdown", bankroll.maxDrawdown)
            put("peak_balance", bankroll.peakBalance)
            put("updated_at", dateFormat.format(Date()))
        }
        db.update("bankroll", values, "id = 1", null)
    }

    fun addTransaction(txn: BankrollTransaction): Long {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("amount", txn.amount)
            put("txn_type", txn.txnType)
            put("reference_type", txn.referenceType)
            put("reference_id", txn.referenceId)
            put("balance_before", txn.balanceBefore)
            put("balance_after", txn.balanceAfter)
            put("note", txn.note)
            put("timestamp", txn.timestamp)
        }
        return db.insert("bankroll_transactions", null, values)
    }

    fun getRecentTransactions(limit: Int): List<BankrollTransaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM bankroll_transactions ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        return try {
            cursorToTransactions(cursor)
        } finally {
            cursor.close()
        }
    }

    // ============================================
    // MATCH OPERATIONS
    // ============================================
    fun getUpcomingMatches(): List<Match> {
        val db = dbHelper.readableDatabase
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val cursor = db.rawQuery("""
            SELECT m.*, t1.name as home_team, t2.name as away_team, l.name as league
            FROM matches m
            LEFT JOIN teams t1 ON m.home_team_id = t1.id
            LEFT JOIN teams t2 ON m.away_team_id = t2.id
            LEFT JOIN leagues l ON m.league_id = l.id
            WHERE m.status = 'SCHEDULED' AND m.match_date >= ?
            ORDER BY m.match_date ASC
        """.trimIndent(), arrayOf(now))
        return try {
            cursorToMatches(cursor)
        } finally {
            cursor.close()
        }
    }

    fun upsertMatches(matches: List<Match>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (m in matches) {
                val values = ContentValues().apply {
                    put("id", m.id)
                    put("sport_id", m.sportId)
                    put("league_id", m.leagueId)
                    put("home_team_id", m.homeTeamId)
                    put("away_team_id", m.awayTeamId)
                    put("match_date", m.matchDate)
                    put("kickoff_ts", m.kickoffTs)
                    put("status", m.status)
                    put("home_score", m.homeScore)
                    put("away_score", m.awayScore)
                    put("updated_at", dateFormat.format(Date()))
                }
                db.insertWithOnConflict("matches", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getMatchById(id: String): Match? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM matches WHERE id = ?", arrayOf(id))
        return try {
            if (cursor.moveToFirst()) cursorToMatch(cursor) else null
        } finally {
            cursor.close()
        }
    }

    // ============================================
    // ODDS OPERATIONS
    // ============================================
    fun upsertOdds(oddsList: List<Odds>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (o in oddsList) {
                val values = ContentValues().apply {
                    put("match_id", o.matchId)
                    put("bookmaker", o.bookmaker)
                    put("market_type", o.marketType)
                    put("home_win", o.homeWin)
                    put("draw", o.draw)
                    put("away_win", o.awayWin)
                    put("over_25", o.over25)
                    put("under_25", o.under25)
                    put("btts_yes", o.bttsYes)
                    put("btts_no", o.bttsNo)
                    put("timestamp", o.timestamp)
                    put("is_closing", o.isClosing)
                }
                db.insertWithOnConflict("odds", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getLatestOdds(matchId: String): Odds? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1", arrayOf(matchId))
        return try {
            if (cursor.moveToFirst()) cursorToOdds(cursor) else null
        } finally {
            cursor.close()
        }
    }

    // ============================================
    // PREDICTION OPERATIONS
    // ============================================
    fun upsertPredictions(predictions: List<Prediction>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (p in predictions) {
                val values = ContentValues().apply {
                    put("match_id", p.matchId)
                    put("model_id", p.modelId)
                    put("predicted_outcome", p.predictedOutcome)
                    put("market_type", p.marketType)
                    put("success_prob", p.successProb)
                    put("confidence", p.confidence)
                    put("honesty", p.honesty)
                    put("reliability", p.reliability)
                    put("value_score", p.valueScore)
                    put("risk_score", p.riskScore)
                    put("quality_score", p.qualityScore)
                    put("consistency", p.consistency)
                    put("market_efficiency", p.marketEfficiency)
                    put("scenario_score", p.scenarioScore)
                    put("final_score", p.finalScore)
                    put("kelly_fraction", p.kellyFraction)
                    put("recommended_stake", p.recommendedStake)
                }
                db.insertWithOnConflict("predictions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllPredictions(): List<Prediction> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM predictions ORDER BY final_score DESC", null)
        return try {
            cursorToPredictions(cursor)
        } finally {
            cursor.close()
        }
    }

    // ============================================
    // COUPON OPERATIONS
    // ============================================
    fun upsertCoupons(coupons: List<Coupon>) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            for (c in coupons) {
                val values = ContentValues().apply {
                    put("id", c.id)
                    put("coupon_type", c.couponType)
                    put("total_odds", c.totalOdds)
                    put("stake", c.stake)
                    put("potential_return", c.potentialReturn)
                    put("status", c.status)
                    put("total_legs", c.totalLegs)
                    put("won_legs", c.wonLegs)
                    put("strategy_tag", c.strategyTag)
                    put("created_at", c.createdAt)
                }
                db.insertWithOnConflict("coupons", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getAllCoupons(): List<Coupon> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM coupons ORDER BY created_at DESC", null)
        return try {
            cursorToCoupons(cursor)
        } finally {
            cursor.close()
        }
    }

    // ============================================
    // SYNC & GENERATE OPERATIONS
    // ============================================
    data class SyncResult(
        val success: Boolean = true,
        val count: Int = 0,
        val matches: List<Match> = emptyList(),
        val predictions: List<Prediction> = emptyList(),
        val error: String? = null
    )

    data class GenerateResult(
        val success: Boolean = true,
        val count: Int = 0,
        val coupons: List<Coupon> = emptyList(),
        val bankroll: Bankroll? = null,
        val error: String? = null
    )

    fun syncLiveData(): SyncResult {
        return try {
            // Simulate fetching from OpenLigaDB and other sources
            val newMatches = generateTestMatches()
            val newOdds = generateTestOdds(newMatches)
            
            upsertMatches(newMatches)
            upsertOdds(newOdds.flatten())
            
            // Generate predictions
            val predictions = generatePredictions(newMatches)
            upsertPredictions(predictions)
            
            SyncResult(
                success = true,
                count = newMatches.size,
                matches = newMatches,
                predictions = predictions
            )
        } catch (e: Exception) {
            Log.e("DataRepository", "Sync error", e)
            SyncResult(success = false, error = e.message)
        }
    }

    fun generateDailyCoupons(): GenerateResult {
        val db = dbHelper.writableDatabase
        return try {
            val bankroll = getBankroll()
            val predictions = getAllPredictions()
            
            // Filter valid predictions
            val validPreds = predictions.filter { 
                it.finalScore > 60 && it.valueScore > 1.02 && it.confidence > 0.55
            }.sortedByDescending { it.finalScore }
            
            if (validPreds.isEmpty()) {
                return GenerateResult(success = false, error = "No valid predictions found")
            }
            
            val stakePerCoupon = 10.0
            val newCoupons = mutableListOf<Coupon>()
            val now = dateFormat.format(Date())
            var totalStaked = 0.0
            
            // Generate SINGLE coupons (top 20)
            for (i in 0 until minOf(20, validPreds.size)) {
                val p = validPreds[i]
                val odds = getLatestOdds(p.matchId) ?: continue
                val oddVal = when (p.predictedOutcome) {
                    "HOME_WIN" -> odds.homeWin
                    "AWAY_WIN" -> odds.awayWin
                    "DRAW" -> odds.draw
                    else -> odds.homeWin
                }
                if (oddVal <= 1.0) continue
                
                val coupon = Coupon(
                    id = "SNG_${System.currentTimeMillis()}_${i}",
                    couponType = "SINGLE",
                    totalOdds = Math.round(oddVal * 100).toLong() / 100.0,
                    stake = stakePerCoupon,
                    potentialReturn = Math.round(stakePerCoupon * oddVal * 100).toLong() / 100.0,
                    totalLegs = 1,
                    createdAt = now
                )
                newCoupons.add(coupon)
                totalStaked += stakePerCoupon
            }
            
            // Generate ACCUMULATOR_4 (5 coupons)
            for (i in 0 until minOf(5, validPreds.size / 4)) {
                val selected = validPreds.subList(i * 4, minOf((i + 1) * 4, validPreds.size))
                var totalOdds = 1.0
                for (p in selected) {
                    val odds = getLatestOdds(p.matchId) ?: continue
                    val oddVal = when (p.predictedOutcome) {
                        "HOME_WIN" -> odds.homeWin
                        "AWAY_WIN" -> odds.awayWin
                        else -> odds.draw
                    }
                    totalOdds *= oddVal
                }
                val coupon = Coupon(
                    id = "ACC_${System.currentTimeMillis()}_${i}",
                    couponType = "ACCUMULATOR_4",
                    totalOdds = Math.round(totalOdds * 100).toLong() / 100.0,
                    stake = stakePerCoupon,
                    potentialReturn = Math.round(stakePerCoupon * totalOdds * 100).toLong() / 100.0,
                    totalLegs = 4,
                    createdAt = now
                )
                newCoupons.add(coupon)
                totalStaked += stakePerCoupon
            }
            
            // Generate SYSTEM_2_3 (5 coupons)
            for (i in 0 until minOf(5, validPreds.size / 3)) {
                val selected = validPreds.subList(i * 3, minOf((i + 1) * 3, validPreds.size))
                var totalOdds = 1.0
                for (p in selected) {
                    val odds = getLatestOdds(p.matchId) ?: continue
                    val oddVal = when (p.predictedOutcome) {
                        "HOME_WIN" -> odds.homeWin
                        "AWAY_WIN" -> odds.awayWin
                        else -> odds.draw
                    }
                    totalOdds *= oddVal
                }
                val coupon = Coupon(
                    id = "SYS_${System.currentTimeMillis()}_${i}",
                    couponType = "SYSTEM_2_3",
                    totalOdds = Math.round(totalOdds * 100).toLong() / 100.0,
                    stake = stakePerCoupon,
                    potentialReturn = Math.round(stakePerCoupon * totalOdds * 100).toLong() / 100.0,
                    totalLegs = 3,
                    createdAt = now
                )
                newCoupons.add(coupon)
                totalStaked += stakePerCoupon
            }
            
            // Save coupons
            upsertCoupons(newCoupons)
            
            // Update bankroll
            val newBankroll = Bankroll(
                currentBalance = bankroll.currentBalance - totalStaked,
                availableBalance = bankroll.availableBalance - totalStaked,
                reservedBalance = bankroll.reservedBalance + totalStaked,
                totalStaked = bankroll.totalStaked + totalStaked
            )
            updateBankroll(newBankroll)
            
            // Add transaction records
            for (c in newCoupons) {
                addTransaction(BankrollTransaction(
                    amount = -c.stake,
                    txnType = "STAKE",
                    referenceType = "COUPON",
                    referenceId = c.id,
                    balanceBefore = bankroll.currentBalance,
                    balanceAfter = newBankroll.currentBalance,
                    note = "Coupon ${c.couponType} placed",
                    timestamp = now
                ))
            }
            
            GenerateResult(
                success = true,
                count = newCoupons.size,
                coupons = newCoupons,
                bankroll = newBankroll
            )
        } catch (e: Exception) {
            Log.e("DataRepository", "Generate error", e)
            GenerateResult(success = false, error = e.message)
        }
    }

    // ============================================
    // TEST DATA GENERATORS
    // ============================================
    private fun generateTestMatches(): List<Match> {
        val now = Date()
        val matches = mutableListOf<Match>()
        val leagues = listOf("bl1" to "Bundesliga", "bl2" to "2. Bundesliga")
        val teams = listOf(
            "Bayern Munich" to "Borussia Dortmund",
            "RB Leipzig" to "Bayer Leverkusen",
            "Borussia Monchengladbach" to "VfB Stuttgart",
            "Eintracht Frankfurt" to "VfL Wolfsburg",
            "SC Freiburg" to "FC Augsburg",
            "FC Union Berlin" to "VfB Stuttgart",
            "1. FC Heidenheim 1846" to "VfL Wolfsburg",
            "FC Bayern München" to "RB Leipzig",
            "Bayer 04 Leverkusen" to "TSG Hoffenheim",
            "Eintracht Frankfurt" to "SV Werder Bremen"
        )
        
        for (i in 0 until 20) {
            val (home, away) = teams[i % teams.size]
            val (leagueId, leagueName) = leagues[i % leagues.size]
            val daysAhead = (i % 7) + 1
            val matchDate = Date(now.time + daysAhead * 86400000L)
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(matchDate)
            val kickoffTs = matchDate.time / 1000
            
            matches.add(Match(
                id = "match_${System.currentTimeMillis()}_$i",
                sportId = "football",
                leagueId = leagueId,
                homeTeamId = "team_${home.hashCode()}",
                awayTeamId = "team_${away.hashCode()}",
                homeTeam = home,
                awayTeam = away,
                league = leagueName,
                matchDate = dateStr,
                kickoffTs = kickoffTs,
                status = "SCHEDULED"
            ))
        }
        return matches
    }

    private fun generateTestOdds(matches: List<Match>): List<List<Odds>> {
        return matches.map { match ->
            val scenarios = listOf(
                Triple(1.85, 3.60, 4.20),  // Strong home
                Triple(2.10, 3.40, 3.30),  // Moderate home
                Triple(2.50, 3.20, 2.80),  // Even
                Triple(3.50, 3.40, 2.10),  // Moderate away
                Triple(4.50, 3.80, 1.75)   // Strong away
            )
            val (h, d, a) = scenarios.random()
            listOf(Odds(
                matchId = match.id,
                bookmaker = "TestBook",
                marketType = "1X2",
                homeWin = h,
                draw = d,
                awayWin = a,
                over25 = 1.75,
                under25 = 2.05,
                bttsYes = 1.85,
                bttsNo = 1.95,
                timestamp = dateFormat.format(Date()),
                isClosing = 0
            ))
        }
    }

    private fun generatePredictions(matches: List<Match>): List<Prediction> {
        return matches.map { match ->
            val odds = generateTestOdds(listOf(match))[0][0]
            val probs = calculateProbs(odds.homeWin, odds.draw, odds.awayWin)
            val best = probs.maxByOrNull { it.second }!!
            val outcome = when (best.first) {
                0 -> "HOME_WIN"
                1 -> "DRAW"
                else -> "AWAY_WIN"
            }
            val successProb = best.second
            val valueScore = successProb * when (outcome) {
                "HOME_WIN" -> odds.homeWin
                "AWAY_WIN" -> odds.awayWin
                else -> odds.draw
            }
            
            Prediction(
                matchId = match.id,
                predictedOutcome = outcome,
                marketType = "1X2",
                successProb = successProb,
                confidence = 0.55 + Math.random() * 0.3,
                honesty = 0.9,
                reliability = 0.75,
                valueScore = valueScore,
                riskScore = 0.2 + Math.random() * 0.3,
                qualityScore = 0.7,
                consistency = 0.6 + Math.random() * 0.3,
                marketEfficiency = 0.85,
                scenarioScore = 0.5 + Math.random() * 0.4,
                finalScore = (50 + Math.random() * 30).toDouble(),
                kellyFraction = 0.05,
                recommendedStake = 2.5
            )
        }
    }

    private fun calculateProbs(h: Double, d: Double, a: Double): List<Pair<Int, Double>> {
        val impliedH = 1.0 / h
        val impliedD = 1.0 / d
        val impliedA = 1.0 / a
        val total = impliedH + impliedD + impliedA
        return listOf(
            0 to (impliedH / total),
            1 to (impliedD / total),
            2 to (impliedA / total)
        )
    }

    // ============================================
    // CURSOR MAPPERS
    // ============================================
    private fun cursorToBankroll(cursor: Cursor): Bankroll {
        return Bankroll(
            currentBalance = cursor.getDouble(cursor.getColumnIndexOrThrow("current_balance")),
            availableBalance = cursor.getDouble(cursor.getColumnIndexOrThrow("available_balance")),
            reservedBalance = cursor.getDouble(cursor.getColumnIndexOrThrow("reserved_balance")),
            totalStaked = cursor.getDouble(cursor.getColumnIndexOrThrow("total_staked")),
            totalWon = cursor.getDouble(cursor.getColumnIndexOrThrow("total_won")),
            totalLost = cursor.getDouble(cursor.getColumnIndexOrThrow("total_lost")),
            totalProfit = cursor.getDouble(cursor.getColumnIndexOrThrow("total_profit")),
            roi = cursor.getDouble(cursor.getColumnIndexOrThrow("roi")),
            maxDrawdown = cursor.getDouble(cursor.getColumnIndexOrThrow("max_drawdown")),
            peakBalance = cursor.getDouble(cursor.getColumnIndexOrThrow("peak_balance"))
        )
    }

    private fun cursorToMatch(cursor: Cursor): Match {
        return Match(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            sportId = cursor.getString(cursor.getColumnIndexOrThrow("sport_id")),
            leagueId = cursor.getString(cursor.getColumnIndexOrThrow("league_id")),
            homeTeamId = cursor.getString(cursor.getColumnIndexOrThrow("home_team_id")),
            awayTeamId = cursor.getString(cursor.getColumnIndexOrThrow("away_team_id")),
            homeTeam = cursor.getString(cursor.getColumnIndexOrThrow("home_team")) ?: "",
            awayTeam = cursor.getString(cursor.getColumnIndexOrThrow("away_team")) ?: "",
            league = cursor.getString(cursor.getColumnIndexOrThrow("league")) ?: "",
            matchDate = cursor.getString(cursor.getColumnIndexOrThrow("match_date")),
            kickoffTs = cursor.getLong(cursor.getColumnIndexOrThrow("kickoff_ts")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            homeScore = getNullableInt(cursor, "home_score"),
            awayScore = getNullableInt(cursor, "away_score")
        )
    }

    private fun cursorToMatches(cursor: Cursor): List<Match> {
        val list = mutableListOf<Match>()
        while (cursor.moveToNext()) {
            list.add(cursorToMatch(cursor))
        }
        return list
    }

    private fun cursorToOdds(cursor: Cursor): Odds {
        return Odds(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
            bookmaker = cursor.getString(cursor.getColumnIndexOrThrow("bookmaker")),
            marketType = cursor.getString(cursor.getColumnIndexOrThrow("market_type")),
            homeWin = cursor.getDouble(cursor.getColumnIndexOrThrow("home_win")),
            draw = cursor.getDouble(cursor.getColumnIndexOrThrow("draw")),
            awayWin = cursor.getDouble(cursor.getColumnIndexOrThrow("away_win")),
            over25 = cursor.getDouble(cursor.getColumnIndexOrThrow("over_25")),
            under25 = cursor.getDouble(cursor.getColumnIndexOrThrow("under_25")),
            bttsYes = cursor.getDouble(cursor.getColumnIndexOrThrow("btts_yes")),
            bttsNo = cursor.getDouble(cursor.getColumnIndexOrThrow("btts_no")),
            timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
            isClosing = cursor.getInt(cursor.getColumnIndexOrThrow("is_closing"))
        )
    }

    private fun cursorToPrediction(cursor: Cursor): Prediction {
        return Prediction(
            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
            matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
            modelId = cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            predictedOutcome = cursor.getString(cursor.getColumnIndexOrThrow("predicted_outcome")),
            marketType = cursor.getString(cursor.getColumnIndexOrThrow("market_type")),
            successProb = cursor.getDouble(cursor.getColumnIndexOrThrow("success_prob")),
            confidence = cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")),
            honesty = cursor.getDouble(cursor.getColumnIndexOrThrow("honesty")),
            reliability = cursor.getDouble(cursor.getColumnIndexOrThrow("reliability")),
            valueScore = cursor.getDouble(cursor.getColumnIndexOrThrow("value_score")),
            riskScore = cursor.getDouble(cursor.getColumnIndexOrThrow("risk_score")),
            qualityScore = cursor.getDouble(cursor.getColumnIndexOrThrow("quality_score")),
            consistency = cursor.getDouble(cursor.getColumnIndexOrThrow("consistency")),
            marketEfficiency = cursor.getDouble(cursor.getColumnIndexOrThrow("market_efficiency")),
            scenarioScore = cursor.getDouble(cursor.getColumnIndexOrThrow("scenario_score")),
            finalScore = cursor.getDouble(cursor.getColumnIndexOrThrow("final_score")),
            kellyFraction = cursor.getDouble(cursor.getColumnIndexOrThrow("kelly_fraction")),
            recommendedStake = cursor.getDouble(cursor.getColumnIndexOrThrow("recommended_stake"))
        )
    }

    private fun cursorToPredictions(cursor: Cursor): List<Prediction> {
        val list = mutableListOf<Prediction>()
        while (cursor.moveToNext()) {
            list.add(cursorToPrediction(cursor))
        }
        return list
    }

    private fun cursorToCoupon(cursor: Cursor): Coupon {
        return Coupon(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            couponType = cursor.getString(cursor.getColumnIndexOrThrow("coupon_type")),
            totalOdds = cursor.getDouble(cursor.getColumnIndexOrThrow("total_odds")),
            stake = cursor.getDouble(cursor.getColumnIndexOrThrow("stake")),
            potentialReturn = cursor.getDouble(cursor.getColumnIndexOrThrow("potential_return")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            totalLegs = cursor.getInt(cursor.getColumnIndexOrThrow("total_legs")),
            wonLegs = cursor.getInt(cursor.getColumnIndexOrThrow("won_legs")),
            strategyTag = cursor.getString(cursor.getColumnIndexOrThrow("strategy_tag")) ?: "BALANCED",
            createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun cursorToCoupons(cursor: Cursor): List<Coupon> {
        val list = mutableListOf<Coupon>()
        while (cursor.moveToNext()) {
            list.add(cursorToCoupon(cursor))
        }
        return list
    }

    private fun cursorToTransactions(cursor: Cursor): List<BankrollTransaction> {
        val list = mutableListOf<BankrollTransaction>()
        while (cursor.moveToNext()) {
            list.add(BankrollTransaction(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
                txnType = cursor.getString(cursor.getColumnIndexOrThrow("txn_type")),
                referenceType = cursor.getString(cursor.getColumnIndexOrThrow("reference_type")),
                referenceId = cursor.getString(cursor.getColumnIndexOrThrow("reference_id")),
                balanceBefore = cursor.getDouble(cursor.getColumnIndexOrThrow("balance_before")),
                balanceAfter = cursor.getDouble(cursor.getColumnIndexOrThrow("balance_after")),
                note = cursor.getString(cursor.getColumnIndexOrThrow("note")) ?: "",
                timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))
            ))
        }
        return list
    }

    private fun getNullableInt(cursor: Cursor, column: String): Int? {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getInt(idx) else null
    }
}

// ============================================
// DATABASE HELPER
// ============================================
class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "sports_betting.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // Enable WAL mode BEFORE any transaction starts
        db.execSQL("PRAGMA journal_mode = WAL;")
        db.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Foreign keys and WAL already enabled in onConfigure()
        
        // bankroll
        db.execSQL("""
            CREATE TABLE bankroll (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                current_balance REAL NOT NULL DEFAULT 1000.0,
                available_balance REAL NOT NULL DEFAULT 1000.0,
                reserved_balance REAL NOT NULL DEFAULT 0.0,
                total_staked REAL NOT NULL DEFAULT 0.0,
                total_won REAL NOT NULL DEFAULT 0.0,
                total_lost REAL NOT NULL DEFAULT 0.0,
                total_profit REAL NOT NULL DEFAULT 0.0,
                roi REAL NOT NULL DEFAULT 0.0,
                max_drawdown REAL NOT NULL DEFAULT 0.0,
                peak_balance REAL NOT NULL DEFAULT 1000.0,
                updated_at TEXT
            )
        """.trimIndent())

        db.execSQL("INSERT INTO bankroll (id, current_balance, available_balance, peak_balance) VALUES (1, 1000.0, 1000.0, 1000.0)")

        // bankroll_transactions
        db.execSQL("""
            CREATE TABLE bankroll_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                txn_type TEXT NOT NULL,
                reference_type TEXT,
                reference_id TEXT,
                balance_before REAL NOT NULL,
                balance_after REAL NOT NULL,
                note TEXT,
                timestamp TEXT NOT NULL
            )
        """.trimIndent())

        // sports
        db.execSQL("""
            CREATE TABLE sports (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("INSERT INTO sports VALUES ('football', 'football', 'Futbol', 1)")

        // leagues
        db.execSQL("""
            CREATE TABLE leagues (
                id TEXT PRIMARY KEY,
                sport_id TEXT NOT NULL,
                name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                country TEXT,
                is_active INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("INSERT INTO leagues VALUES ('bl1', 'football', 'bl1', 'Bundesliga', 'Germany', 1)")
        db.execSQL("INSERT INTO leagues VALUES ('bl2', 'football', 'bl2', '2. Bundesliga', 'Germany', 1)")

        // teams
        db.execSQL("""
            CREATE TABLE teams (
                id TEXT PRIMARY KEY,
                sport_id TEXT NOT NULL,
                name TEXT NOT NULL,
                display_name TEXT NOT NULL,
                is_active INTEGER NOT NULL DEFAULT 1,
                elo_rating REAL DEFAULT 1500.0
            )
        """.trimIndent())

        // matches
        db.execSQL("""
            CREATE TABLE matches (
                id TEXT PRIMARY KEY,
                sport_id TEXT NOT NULL,
                league_id TEXT NOT NULL,
                home_team_id TEXT NOT NULL,
                away_team_id TEXT NOT NULL,
                match_date TEXT NOT NULL,
                kickoff_ts INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'SCHEDULED',
                home_score INTEGER,
                away_score INTEGER,
                updated_at TEXT
            )
        """.trimIndent())

        // odds
        db.execSQL("""
            CREATE TABLE odds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                match_id TEXT NOT NULL,
                bookmaker TEXT NOT NULL,
                market_type TEXT NOT NULL DEFAULT '1X2',
                home_win REAL,
                draw REAL,
                away_win REAL,
                over_25 REAL,
                under_25 REAL,
                btts_yes REAL,
                btts_no REAL,
                timestamp TEXT NOT NULL,
                is_closing INTEGER DEFAULT 0
            )
        """.trimIndent())

        // predictions
        db.execSQL("""
            CREATE TABLE predictions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                match_id TEXT NOT NULL,
                model_id TEXT NOT NULL DEFAULT 'ensemble_v1',
                predicted_outcome TEXT NOT NULL,
                market_type TEXT NOT NULL DEFAULT '1X2',
                success_prob REAL NOT NULL,
                confidence REAL NOT NULL,
                honesty REAL NOT NULL,
                reliability REAL NOT NULL,
                value_score REAL NOT NULL,
                risk_score REAL NOT NULL,
                quality_score REAL NOT NULL,
                consistency REAL NOT NULL,
                market_efficiency REAL NOT NULL,
                scenario_score REAL NOT NULL,
                final_score REAL NOT NULL,
                kelly_fraction REAL DEFAULT 0.0,
                recommended_stake REAL DEFAULT 0.0
            )
        """.trimIndent())

        // coupons
        db.execSQL("""
            CREATE TABLE coupons (
                id TEXT PRIMARY KEY,
                coupon_type TEXT NOT NULL,
                total_odds REAL NOT NULL,
                stake REAL NOT NULL,
                potential_return REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                total_legs INTEGER NOT NULL,
                won_legs INTEGER DEFAULT 0,
                strategy_tag TEXT DEFAULT 'BALANCED',
                created_at TEXT NOT NULL
            )
        """.trimIndent())

        // coupon_legs
        db.execSQL("""
            CREATE TABLE coupon_legs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                coupon_id TEXT NOT NULL,
                match_id TEXT NOT NULL,
                prediction_id INTEGER NOT NULL,
                leg_order INTEGER NOT NULL,
                selection TEXT NOT NULL,
                market_type TEXT NOT NULL,
                odds REAL NOT NULL,
                stake_allocated REAL NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING'
            )
        """.trimIndent())

        // indexes
        db.execSQL("CREATE INDEX idx_matches_date ON matches(match_date)")
        db.execSQL("CREATE INDEX idx_matches_status ON matches(status)")
        db.execSQL("CREATE INDEX idx_odds_match ON odds(match_id)")
        db.execSQL("CREATE INDEX idx_predictions_match ON predictions(match_id)")
        db.execSQL("CREATE INDEX idx_predictions_score ON predictions(final_score DESC)")
        db.execSQL("CREATE INDEX idx_coupons_created ON coupons(created_at DESC)")
        db.execSQL("CREATE INDEX idx_txns_time ON bankroll_transactions(timestamp DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle migrations here if needed
    }
}