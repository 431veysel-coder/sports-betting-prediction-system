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

class DataRepository private constructor(context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    companion object {
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
            if (cursor.moveToFirst()) cursorToBankroll(cursor) else Bankroll()
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
        return try { cursorToTransactions(cursor) } finally { cursor.close() }
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
        return try { cursorToMatches(cursor) } finally { cursor.close() }
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
        } finally { db.endTransaction() }
    }

    fun getMatchById(id: String): Match? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM matches WHERE id = ?", arrayOf(id))
        return try { if (cursor.moveToFirst()) cursorToMatch(cursor) else null } finally { cursor.close() }
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
        } finally { db.endTransaction() }
    }

    fun getLatestOdds(matchId: String): Odds? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1", arrayOf(matchId))
        return try { if (cursor.moveToFirst()) cursorToOdds(cursor) else null } finally { cursor.close() }
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
        } finally { db.endTransaction() }
    }

    fun getAllPredictions(): List<Prediction> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM predictions ORDER BY final_score DESC", null)
        return try { cursorToPredictions(cursor) } finally { cursor.close() }
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
        } finally { db.endTransaction() }
    }

    fun getAllCoupons(): List<Coupon> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM coupons ORDER BY created_at DESC", null)
        val coupons = try { cursorToCoupons(cursor) } finally { cursor.close() }
        // Load legs for each coupon
        for (coupon in coupons) {
            val legCursor = db.rawQuery("SELECT * FROM coupon_legs WHERE coupon_id = ? ORDER BY leg_order", arrayOf(coupon.id))
            try { coupon.legs = cursorToCouponLegs(legCursor) } finally { legCursor.close() }
        }
        return coupons
    }

    // ============================================
    // SYNC & GENERATE OPERATIONS (Legacy - for CollectorOrchestrator)
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
            val newMatches = generateTestMatches()
            val newOdds = generateTestOdds(newMatches)
            upsertMatches(newMatches)
            upsertOdds(newOdds.flatten())
            val predictions = generatePredictions(newMatches)
            upsertPredictions(predictions)
            SyncResult(success = true, count = newMatches.size, matches = newMatches, predictions = predictions)
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
            val validPreds = predictions.filter { it.finalScore > 60 && it.valueScore > 1.02 && it.confidence > 0.55 }
                .sortedByDescending { it.finalScore }
            if (validPreds.isEmpty()) {
                return GenerateResult(success = false, error = "No valid predictions found")
            }
            val stakePerCoupon = 10.0
            val newCoupons = mutableListOf<Coupon>()
            val now = dateFormat.format(Date())
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
                    id = "SNG_${System.currentTimeMillis()}_$i",
                    couponType = "SINGLE", totalOdds = (oddVal * 100).roundToLong() / 100.0,
                    stake = stakePerCoupon,
                    potentialReturn = (stakePerCoupon * oddVal * 100).roundToLong() / 100.0,
                    totalLegs = 1, createdAt = now
                )
                newCoupons.add(coupon)
            }
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
                    id = "ACC_${System.currentTimeMillis()}_$i",
                    couponType = "ACCUMULATOR_4", totalOdds = (totalOdds * 100).roundToLong() / 100.0,
                    stake = stakePerCoupon,
                    potentialReturn = (stakePerCoupon * totalOdds * 100).roundToLong() / 100.0,
                    totalLegs = selected.size, createdAt = now
                )
                newCoupons.add(coupon)
            }
            upsertCoupons(newCoupons)
            GenerateResult(success = true, count = newCoupons.size, coupons = newCoupons, bankroll = bankroll)
        } catch (e: Exception) {
            GenerateResult(success = false, error = e.message)
        }
    }

    // ============================================
    // TEST DATA GENERATORS
    // ============================================
    private fun generateTestMatches(): List<Match> {
        val leagues = listOf("BL1", "PL", "PD", "SA", "FL1")
        val teams = listOf("Bayern Munich", "Borussia Dortmund", "Real Madrid", "Barcelona", 
                          "Manchester City", "Liverpool", "Juventus", "AC Milan", "PSG", "Lyon")
        val matches = mutableListOf<Match>()
        val now = System.currentTimeMillis()
        for (i in 0..10) {
            val home = teams[i % teams.size]
            val away = teams[(i + 5) % teams.size]
            matches.add(Match(
                id = "match_$i", sportId = "football", leagueId = leagues[i % leagues.size],
                homeTeamId = "team_${i}", awayTeamId = "team_${i+5}",
                homeTeam = home, awayTeam = away, league = leagues[i % leagues.size],
                matchDate = dateFormat.format(Date(now + (i * 86400000))),
                kickoffTs = (now / 1000) + (i * 86400), status = "SCHEDULED"
            ))
        }
        return matches
    }

    private fun generateTestOdds(matches: List<Match>): List<List<Odds>> {
        return matches.map { match ->
            listOf(Odds(
                matchId = match.id, bookmaker = "TestBook", marketType = "1X2",
                homeWin = 2.0, draw = 3.4, awayWin = 3.5,
                over25 = 1.75, under25 = 2.05, bttsYes = 1.85, bttsNo = 1.95,
                timestamp = dateFormat.format(Date()), isClosing = 0
            ))
        }
    }

    private fun generatePredictions(matches: List<Match>): List<Prediction> {
        return matches.map { match ->
            Prediction(
                matchId = match.id, modelId = "ensemble_v1", predictedOutcome = "HOME_WIN",
                marketType = "1X2", successProb = 0.65, confidence = 0.75, honesty = 0.85,
                reliability = 0.80, valueScore = 1.15, riskScore = 0.25, qualityScore = 0.90,
                consistency = 0.70, marketEfficiency = 0.85, scenarioScore = 0.75,
                finalScore = 78.5, kellyFraction = 0.032, recommendedStake = 32.0
            )
        }
    }

    // ============================================
    // DATABASE HELPER
    // ============================================
    private inner class DatabaseHelper(context: Context) : SQLiteOpenHelper(
        context, "sports_betting.db", null, 2
    ) {
        override fun onCreate(db: SQLiteDatabase) { createTables(db) }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN max_single_stake_pct REAL DEFAULT 0.05") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN min_value_threshold REAL DEFAULT 1.05") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN min_confidence_threshold REAL DEFAULT 0.60") } catch (e: Exception) {}
            }
        }
        private fun createTables(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS bankroll (
                id INTEGER PRIMARY KEY CHECK (id = 1), current_balance REAL DEFAULT 1000,
                available_balance REAL DEFAULT 1000, reserved_balance REAL DEFAULT 0,
                total_staked REAL DEFAULT 0, total_won REAL DEFAULT 0, total_lost REAL DEFAULT 0,
                total_profit REAL DEFAULT 0, roi REAL DEFAULT 0, max_drawdown REAL DEFAULT 0,
                peak_balance REAL DEFAULT 1000, updated_at TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS matches (
                id TEXT PRIMARY KEY, sport_id TEXT, league_id TEXT, home_team_id TEXT, away_team_id TEXT,
                match_date TEXT, kickoff_ts INTEGER, status TEXT, home_score INTEGER, away_score INTEGER,
                updated_at TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS odds (
                id INTEGER PRIMARY KEY AUTOINCREMENT, match_id TEXT, bookmaker TEXT, market_type TEXT,
                home_win REAL, draw REAL, away_win REAL, over_25 REAL, under_25 REAL,
                btts_yes REAL, btts_no REAL, timestamp TEXT, is_closing INTEGER DEFAULT 0)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS predictions (
                id INTEGER PRIMARY KEY AUTOINCREMENT, match_id TEXT, model_id TEXT,
                predicted_outcome TEXT, market_type TEXT, success_prob REAL, confidence REAL,
                honesty REAL, reliability REAL, value_score REAL, risk_score REAL,
                quality_score REAL, consistency REAL, market_efficiency REAL, scenario_score REAL,
                final_score REAL, kelly_fraction REAL, recommended_stake REAL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS coupons (
                id TEXT PRIMARY KEY, coupon_type TEXT, total_odds REAL, stake REAL,
                potential_return REAL, status TEXT, total_legs INTEGER, won_legs INTEGER DEFAULT 0,
                strategy_tag TEXT, created_at TEXT, settled_at TEXT)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS coupon_legs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, coupon_id TEXT, match_id TEXT,
                prediction_id INTEGER, leg_order INTEGER, selection TEXT, market_type TEXT,
                odds REAL, stake_allocated REAL, status TEXT DEFAULT 'PENDING')""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS bankroll_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL, txn_type TEXT,
                reference_type TEXT, reference_id TEXT, balance_before REAL, balance_after REAL,
                note TEXT, timestamp TEXT)""")
        }
    }

    // ============================================
    // CURSOR MAPPERS
    // ============================================
    private fun cursorToBankroll(cursor: Cursor): Bankroll = Bankroll(
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

    private fun cursorToMatch(cursor: Cursor): Match = Match(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        sportId = cursor.getString(cursor.getColumnIndexOrThrow("sport_id")),
        leagueId = cursor.getString(cursor.getColumnIndexOrThrow("league_id")),
        homeTeamId = cursor.getString(cursor.getColumnIndexOrThrow("home_team_id")),
        awayTeamId = cursor.getString(cursor.getColumnIndexOrThrow("away_team_id")),
        homeTeam = cursor.getString(cursor.getColumnIndexOrThrow("home_team")),
        awayTeam = cursor.getString(cursor.getColumnIndexOrThrow("away_team")),
        league = cursor.getString(cursor.getColumnIndexOrThrow("league")),
        matchDate = cursor.getString(cursor.getColumnIndexOrThrow("match_date")),
        kickoffTs = cursor.getLong(cursor.getColumnIndexOrThrow("kickoff_ts")),
        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
        homeScore = cursor.getInt(cursor.getColumnIndexOrThrow("home_score")),
        awayScore = cursor.getInt(cursor.getColumnIndexOrThrow("away_score"))
    )

    private fun cursorToOdds(cursor: Cursor): Odds = Odds(
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

    private fun cursorToPrediction(cursor: Cursor): Prediction = Prediction(
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

    private fun cursorToCoupon(cursor: Cursor): Coupon = Coupon(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        couponType = cursor.getString(cursor.getColumnIndexOrThrow("coupon_type")),
        totalOdds = cursor.getDouble(cursor.getColumnIndexOrThrow("total_odds")),
        stake = cursor.getDouble(cursor.getColumnIndexOrThrow("stake")),
        potentialReturn = cursor.getDouble(cursor.getColumnIndexOrThrow("potential_return")),
        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
        totalLegs = cursor.getInt(cursor.getColumnIndexOrThrow("total_legs")),
        wonLegs = cursor.getInt(cursor.getColumnIndexOrThrow("won_legs")),
        strategyTag = cursor.getString(cursor.getColumnIndexOrThrow("strategy_tag")),
        createdAt = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
    )

    private fun cursorToCouponLegs(cursor: Cursor): List<CouponLeg> {
        val legs = mutableListOf<CouponLeg>()
        while (cursor.moveToNext()) {
            legs.add(CouponLeg(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                couponId = cursor.getString(cursor.getColumnIndexOrThrow("coupon_id")),
                matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
                predictionId = cursor.getLong(cursor.getColumnIndexOrThrow("prediction_id")),
                legOrder = cursor.getInt(cursor.getColumnIndexOrThrow("leg_order")),
                selection = cursor.getString(cursor.getColumnIndexOrThrow("selection")),
                marketType = cursor.getString(cursor.getColumnIndexOrThrow("market_type")),
                odds = cursor.getDouble(cursor.getColumnIndexOrThrow("odds")),
                stakeAllocated = cursor.getDouble(cursor.getColumnIndexOrThrow("stake_allocated")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
            ))
        }
        return legs
    }

    private fun cursorToTransactions(cursor: Cursor): List<BankrollTransaction> {
        val txns = mutableListOf<BankrollTransaction>()
        while (cursor.moveToNext()) {
            txns.add(BankrollTransaction(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                amount = cursor.getDouble(cursor.getColumnIndexOrThrow("amount")),
                txnType = cursor.getString(cursor.getColumnIndexOrThrow("txn_type")),
                referenceType = cursor.getString(cursor.getColumnIndexOrThrow("reference_type")),
                referenceId = cursor.getString(cursor.getColumnIndexOrThrow("reference_id")),
                balanceBefore = cursor.getDouble(cursor.getColumnIndexOrThrow("balance_before")),
                balanceAfter = cursor.getDouble(cursor.getColumnIndexOrThrow("balance_after")),
                note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
                timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp"))
            ))
        }
        return txns
    }

    private fun cursorToMatches(cursor: Cursor): List<Match> {
        val matches = mutableListOf<Match>()
        while (cursor.moveToNext()) { matches.add(cursorToMatch(cursor)) }
        return matches
    }

    private fun cursorToPredictions(cursor: Cursor): List<Prediction> {
        val preds = mutableListOf<Prediction>()
        while (cursor.moveToNext()) { preds.add(cursorToPrediction(cursor)) }
        return preds
    }

    private fun cursorToCoupons(cursor: Cursor): List<Coupon> {
        val coupons = mutableListOf<Coupon>()
        while (cursor.moveToNext()) { coupons.add(cursorToCoupon(cursor)) }
        return coupons
    }
}