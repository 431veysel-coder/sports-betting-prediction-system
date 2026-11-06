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
import com.openminis.betting.app.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid Data Repository - API First with Local SQLite Fallback
 * - Online: Fetches from Python FastAPI backend, caches locally
 * - Offline: Serves from local SQLite
 * - Background: Syncs local changes to server
 */
class DataRepository private constructor(context: Context) {

    private val dbHelper = DatabaseHelper(context)
    private val apiClient = ApiClient(context)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val isSyncing = AtomicBoolean(false)
    private val lastSyncTime = AtomicBoolean(false)

    companion object {
        @Volatile private var instance: DataRepository? = null
        fun getInstance(context: Context): DataRepository = instance ?: synchronized(this) {
            instance ?: DataRepository(context.applicationContext).also { instance = it }
        }
    }

    // ============================================
    // LIFECYCLE
    // ============================================
    fun shutdown() {
        job.cancel()
    }

    // Expose database for debugging
    fun getDatabase(): SQLiteDatabase? = try { dbHelper.writableDatabase } catch (e: Exception) { null }

    // ============================================
    // BANKROLL OPERATIONS
    // ============================================
    suspend fun getBankroll(): Bankroll = withContext(Dispatchers.IO) {
        // Try API first
        val apiResult = apiClient.getBankroll()
        if (apiResult.isSuccess) {
            val bankroll = apiResult.getOrNull()!!
            cacheBankroll(bankroll)
            return@withContext bankroll
        }
        
        // Fallback to local
        Log.d("DataRepository", "API failed, using local bankroll: ${apiResult.exceptionOrNull()?.message}")
        getLocalBankroll()
    }

    private fun getLocalBankroll(): Bankroll {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM bankroll WHERE id = 1", null)
        return try {
            if (cursor.moveToFirst()) cursorToBankroll(cursor) else Bankroll()
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("DataRepository", "getLocalBankroll failed", e)
            Bankroll()
        } finally {
            cursor.close()
        }
    }

    private fun cacheBankroll(bankroll: Bankroll) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("id", 1)
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
        db.insertWithOnConflict("bankroll", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun deposit(amount: Double, note: String = ""): Bankroll = withContext(Dispatchers.IO) {
        val result = apiClient.deposit(amount, note)
        if (result.isSuccess) {
            cacheBankroll(result.getOrNull()!!)
            return@withContext result.getOrNull()!!
        }
        throw result.exceptionOrNull() ?: Exception("Deposit failed")
    }

    suspend fun withdraw(amount: Double, note: String = ""): Bankroll = withContext(Dispatchers.IO) {
        val result = apiClient.withdraw(amount, note)
        if (result.isSuccess) {
            cacheBankroll(result.getOrNull()!!)
            return@withContext result.getOrNull()!!
        }
        throw result.exceptionOrNull() ?: Exception("Withdraw failed")
    }

    suspend fun resetBankroll(initialBalance: Double = 1000.0): Bankroll = withContext(Dispatchers.IO) {
        val result = apiClient.resetBankroll(initialBalance)
        if (result.isSuccess) {
            cacheBankroll(result.getOrNull()!!)
            return@withContext result.getOrNull()!!
        }
        throw result.exceptionOrNull() ?: Exception("Reset failed")
    }

    suspend fun getTransactions(limit: Int = 50): List<BankrollTransaction> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getTransactions(limit)
        if (apiResult.isSuccess) return@withContext apiResult.getOrNull()!!
        return@withContext getLocalTransactions(limit)
    }

    private fun getLocalTransactions(limit: Int): List<BankrollTransaction> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM bankroll_transactions ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
        return try { cursorToTransactions(cursor) } finally { cursor.close() }
    }

    // ============================================
    // MATCH OPERATIONS
    // ============================================
    suspend fun getUpcomingMatches(daysAhead: Int = 7, forceRefresh: Boolean = false): List<Match> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val local = getLocalUpcomingMatches(daysAhead)
            if (local.isNotEmpty()) return@withContext local
        }
        
        val apiResult = apiClient.getMatches(daysAhead = daysAhead)
        if (apiResult.isSuccess) {
            val matches = apiResult.getOrNull()!!
            cacheMatches(matches)
            return@withContext matches
        }
        
        Log.d("DataRepository", "API failed, using local matches")
        getLocalUpcomingMatches(daysAhead)
    }

    private fun getLocalUpcomingMatches(daysAhead: Int): List<Match> {
        val db = dbHelper.readableDatabase
        val now = dateFormat.format(Date())
        val cursor = db.rawQuery("""
            SELECT m.*, t1.name as home_team, t2.name as away_team, l.name as league
            FROM matches m
            LEFT JOIN teams t1 ON m.home_team_id = t1.id
            LEFT JOIN teams t2 ON m.away_team_id = t2.id
            LEFT JOIN leagues l ON m.league_id = l.id
            WHERE m.status = 'SCHEDULED' AND m.match_date >= ?
            AND m.match_date <= datetime(?, ?)
            ORDER BY m.match_date ASC
        """.trimIndent(), arrayOf(now, now, "+$daysAhead days"))
        return try { cursorToMatches(cursor) } finally { cursor.close() }
    }

    suspend fun getMatchById(id: String): Match? = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getMatch(id)
        if (apiResult.isSuccess) return@withContext apiResult.getOrNull()
        
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM matches WHERE id = ?", arrayOf(id))
        return@withContext try {
            if (cursor.moveToFirst()) cursorToMatch(cursor) else null
        } finally { cursor.close() }
    }

    private fun cacheMatches(matches: List<Match>) {
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

    // ============================================
    // ODDS OPERATIONS
    // ============================================
    suspend fun getLatestOdds(matchId: String): Odds? = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getLatestOdds(matchId)
        if (apiResult.isSuccess) {
            val odds = apiResult.getOrNull()!!
            cacheOdds(listOf(odds))
            return@withContext odds
        }
        getLocalLatestOdds(matchId)
    }

    private fun getLocalLatestOdds(matchId: String): Odds? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1", arrayOf(matchId))
        return try { if (cursor.moveToFirst()) cursorToOdds(cursor) else null } finally { cursor.close() }
    }

    private fun cacheOdds(oddsList: List<Odds>) {
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

    // ============================================
    // PREDICTION OPERATIONS
    // ============================================
    suspend fun getPredictions(minScore: Double = 0.0, limit: Int = 50): List<Prediction> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getPredictions(minScore = minScore, limit = limit)
        if (apiResult.isSuccess) {
            val preds = apiResult.getOrNull()!!
            cachePredictions(preds)
            return@withContext preds
        }
        getLocalPredictions(minScore, limit)
    }

    private fun getLocalPredictions(minScore: Double, limit: Int): List<Prediction> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM predictions WHERE final_score >= ? ORDER BY final_score DESC LIMIT ?", arrayOf(minScore.toString(), limit.toString()))
        return try { cursorToPredictions(cursor) } finally { cursor.close() }
    }

    suspend fun generatePrediction(matchId: String, forceRefresh: Boolean = false): Prediction = withContext(Dispatchers.IO) {
        val apiResult = apiClient.generatePrediction(matchId, forceRefresh)
        if (apiResult.isSuccess) {
            val pred = apiResult.getOrNull()!!
            cachePredictions(listOf(pred))
            return@withContext pred
        }
        throw apiResult.exceptionOrNull() ?: Exception("Prediction generation failed")
    }

    suspend fun batchGeneratePredictions(matchIds: List<String>): List<Prediction> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.batchGeneratePredictions(matchIds)
        if (apiResult.isSuccess) {
            val response = apiResult.getOrNull()!!
            val successful = response.results.filter { it.success }.map { it.prediction!! }
            cachePredictions(successful)
            return@withContext successful
        }
        throw apiResult.exceptionOrNull() ?: Exception("Batch prediction failed")
    }

    private fun cachePredictions(predictions: List<Prediction>) {
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

    // ============================================
    // COUPON OPERATIONS
    // ============================================
    suspend fun getCoupons(status: String? = null, limit: Int = 50): List<Coupon> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getCoupons(status = status, limit = limit)
        if (apiResult.isSuccess) {
            val coupons = apiResult.getOrNull()!!
            cacheCoupons(coupons)
            return@withContext coupons
        }
        getLocalCoupons(status, limit)
    }

    private fun getLocalCoupons(status: String?, limit: Int): List<Coupon> {
        val db = dbHelper.readableDatabase
        val where = if (status != null) "WHERE status = ?" else ""
        val params = if (status != null) arrayOf(status, limit.toString()) else arrayOf(limit.toString())
        val cursor = db.rawQuery("SELECT * FROM coupons $where ORDER BY created_at DESC LIMIT ?", params)
        val coupons = try { cursorToCoupons(cursor) } finally { cursor.close() }
        
        // Load legs for each coupon
        for (coupon in coupons) {
            val legCursor = db.rawQuery("SELECT * FROM coupon_legs WHERE coupon_id = ? ORDER BY leg_order", arrayOf(coupon.id))
            try {
                coupon.legs = cursorToCouponLegs(legCursor)
            } finally { legCursor.close() }
        }
        return coupons
    }

    suspend fun getCoupon(couponId: String): Coupon? = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getCoupon(couponId)
        if (apiResult.isSuccess) return@withContext apiResult.getOrNull()
        
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM coupons WHERE id = ?", arrayOf(couponId))
        val coupon = try {
            if (cursor.moveToFirst()) cursorToCoupon(cursor) else null
        } finally { cursor.close() }
        
        coupon?.let {
            val legCursor = db.rawQuery("SELECT * FROM coupon_legs WHERE coupon_id = ? ORDER BY leg_order", arrayOf(couponId))
            try { it.legs = cursorToCouponLegs(legCursor) } finally { legCursor.close() }
        }
        return@withContext coupon
    }

    suspend fun generateCoupons(request: ApiClient.GenerateCouponsRequest): List<Coupon> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.generateCoupons(request)
        if (apiResult.isSuccess) {
            val coupons = apiResult.getOrNull()!!
            cacheCoupons(coupons)
            return@withContext coupons
        }
        throw apiResult.exceptionOrNull() ?: Exception("Coupon generation failed")
    }

    suspend fun settleCoupon(couponId: String, legResults: List<ApiClient.LegResult>): ApiClient.SettleResponse = withContext(Dispatchers.IO) {
        val apiResult = apiClient.settleCoupon(couponId, legResults)
        if (apiResult.isSuccess) {
            val response = apiResult.getOrNull()!!
            // Update local cache
            updateLocalCouponStatus(couponId, response.status, response.won_legs)
            return@withContext response
        }
        throw apiResult.exceptionOrNull() ?: Exception("Settle failed")
    }

    private fun updateLocalCouponStatus(couponId: String, status: String, wonLegs: Int) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("won_legs", wonLegs)
            put("settled_at", dateFormat.format(Date()))
        }
        db.update("coupons", values, "id = ?", arrayOf(couponId))
    }

    private fun cacheCoupons(coupons: List<Coupon>) {
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
                
                // Cache legs
                if (c.legs != null) {
                    for (leg in c.legs!!) {
                        val legValues = ContentValues().apply {
                            put("coupon_id", leg.couponId)
                            put("match_id", leg.matchId)
                            put("prediction_id", leg.predictionId)
                            put("leg_order", leg.legOrder)
                            put("selection", leg.selection)
                            put("market_type", leg.marketType)
                            put("odds", leg.odds)
                            put("stake_allocated", leg.stakeAllocated)
                            put("status", leg.status)
                        }
                        db.insertWithOnConflict("coupon_legs", null, legValues, SQLiteDatabase.CONFLICT_REPLACE)
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    // ============================================
    // SETTINGS
    // ============================================
    suspend fun getSettings(category: String? = null): List<ApiClient.ApiSetting> = withContext(Dispatchers.IO) {
        val apiResult = apiClient.getSettings(category)
        if (apiResult.isSuccess) return@withContext apiResult.getOrNull()!!
        emptyList()
    }

    suspend fun updateSetting(key: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val apiResult = apiClient.updateSetting(key, value)
        apiResult.isSuccess
    }

    // ============================================
    // SYNC OPERATIONS
    // ============================================
    data class SyncResult(
        val success: Boolean = true,
        val count: Int = 0,
        val matches: List<Match> = emptyList(),
        val predictions: List<Prediction> = emptyList(),
        val coupons: List<Coupon> = emptyList(),
        val error: String? = null
    )

    suspend fun syncAll(forceRefresh: Boolean = false): SyncResult = withContext(Dispatchers.IO) {
        if (isSyncing.getAndSet(true)) {
            return@withContext SyncResult(success = false, error = "Sync already in progress")
        }
        
        return@withContext try {
            // 1. Sync matches & odds from API
            val syncResult = apiClient.syncData(daysAhead = 7)
            if (!syncResult.isSuccess) {
                SyncResult(success = false, error = syncResult.exceptionOrNull()?.message)
            } else {
                val response = syncResult.getOrNull()!!
                
                // 2. Get fresh predictions (they were generated by API sync)
                val predictions = apiClient.getPredictions(minScore = 50.0, limit = 200)
                val preds = if (predictions.isSuccess) predictions.getOrNull()!! else emptyList()
                
                // 3. Get fresh coupons
                val couponsResult = apiClient.getCoupons(limit = 50)
                val coupons = if (couponsResult.isSuccess) couponsResult.getOrNull()!! else emptyList()
                
                SyncResult(
                    success = true,
                    count = response.new_matches_found,
                    matches = emptyList(), // Not returned by sync endpoint
                    predictions = preds,
                    coupons = coupons
                )
            }
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("DataRepository", "syncAll failed", e)
            SyncResult(success = false, error = e.message)
        } finally {
            isSyncing.set(false)
            lastSyncTime.set(true)
        }
    }

    suspend fun getSyncStatus(): ApiClient.SyncStatusResponse? = withContext(Dispatchers.IO) {
        apiClient.getSyncStatus().getOrNull()
    }

    // ============================================
    // DATABASE HELPER
    // ============================================
    private inner class DatabaseHelper(context: Context) : SQLiteOpenHelper(
        context, "sports_betting.db", null, 2
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            // Tables will be created by init_db.py equivalent or first sync
            createTables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Add new columns for v2
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN max_single_stake_pct REAL DEFAULT 0.05") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN min_value_threshold REAL DEFAULT 1.05") } catch (e: Exception) {}
                try { db.execSQL("ALTER TABLE bankroll ADD COLUMN min_confidence_threshold REAL DEFAULT 0.60") } catch (e: Exception) {}
            }
        }

        private fun createTables(db: SQLiteDatabase) {
            // Core tables - simplified for local cache
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bankroll (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    current_balance REAL DEFAULT 1000,
                    available_balance REAL DEFAULT 1000,
                    reserved_balance REAL DEFAULT 0,
                    total_staked REAL DEFAULT 0,
                    total_won REAL DEFAULT 0,
                    total_lost REAL DEFAULT 0,
                    total_profit REAL DEFAULT 0,
                    roi REAL DEFAULT 0,
                    max_drawdown REAL DEFAULT 0,
                    peak_balance REAL DEFAULT 1000,
                    updated_at TEXT
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS matches (
                    id TEXT PRIMARY KEY,
                    sport_id TEXT, league_id TEXT, home_team_id TEXT, away_team_id TEXT,
                    match_date TEXT, kickoff_ts INTEGER, status TEXT,
                    home_score INTEGER, away_score INTEGER,
                    updated_at TEXT
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS odds (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    match_id TEXT, bookmaker TEXT, market_type TEXT,
                    home_win REAL, draw REAL, away_win REAL,
                    over_25 REAL, under_25 REAL, btts_yes REAL, btts_no REAL,
                    timestamp TEXT, is_closing INTEGER DEFAULT 0
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS predictions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    match_id TEXT, model_id TEXT, predicted_outcome TEXT, market_type TEXT,
                    success_prob REAL, confidence REAL, honesty REAL, reliability REAL,
                    value_score REAL, risk_score REAL, quality_score REAL, consistency REAL,
                    market_efficiency REAL, scenario_score REAL, final_score REAL,
                    kelly_fraction REAL, recommended_stake REAL
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS coupons (
                    id TEXT PRIMARY KEY,
                    coupon_type TEXT, total_odds REAL, stake REAL, potential_return REAL,
                    status TEXT, total_legs INTEGER, won_legs INTEGER DEFAULT 0,
                    strategy_tag TEXT, created_at TEXT, settled_at TEXT
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS coupon_legs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    coupon_id TEXT, match_id TEXT, prediction_id INTEGER,
                    leg_order INTEGER, selection TEXT, market_type TEXT,
                    odds REAL, stake_allocated REAL, status TEXT DEFAULT 'PENDING'
                )
            """)
            
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS bankroll_transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    amount REAL, txn_type TEXT, reference_type TEXT, reference_id TEXT,
                    balance_before REAL, balance_after REAL, note TEXT, timestamp TEXT
                )
            """)
        }
    }

    // ============================================
    // CURSOR MAPPERS
    // ============================================
    private fun cursorToBankroll(cursor: Cursor): Bankroll = Bankroll(
        currentBalance = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("current_balance")) ?: 1000.0,
        availableBalance = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("available_balance")) ?: 1000.0,
        reservedBalance = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("reserved_balance")) ?: 0.0,
        totalStaked = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("total_staked")) ?: 0.0,
        totalWon = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("total_won")) ?: 0.0,
        totalLost = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("total_lost")) ?: 0.0,
        totalProfit = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("total_profit")) ?: 0.0,
        roi = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("roi")) ?: 0.0,
        maxDrawdown = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("max_drawdown")) ?: 0.0,
        peakBalance = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("peak_balance")) ?: 1000.0
    )

    private fun cursorToMatch(cursor: Cursor): Match = Match(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        sportId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("sport_id")) ?: "football",
        leagueId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("league_id")) ?: "",
        homeTeamId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("home_team_id")) ?: "",
        awayTeamId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("away_team_id")) ?: "",
        homeTeam = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("home_team")) ?: "",
        awayTeam = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("away_team")) ?: "",
        league = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("league")) ?: "",
        matchDate = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("match_date")) ?: "",
        kickoffTs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("kickoff_ts")) ?: 0,
        status = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("status")) ?: "SCHEDULED",
        homeScore = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("home_score")),
        awayScore = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("away_score"))
    )

    private fun cursorToOdds(cursor: Cursor): Odds = Odds(
        id = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("id")) ?: 0,
        matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
        bookmaker = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("bookmaker")) ?: "TestBook",
        marketType = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("market_type")) ?: "1X2",
        homeWin = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("home_win")) ?: 2.0,
        draw = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("draw")) ?: 3.4,
        awayWin = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("away_win")) ?: 3.5,
        over25 = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("over_25")) ?: 1.75,
        under25 = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("under_25")) ?: 2.05,
        bttsYes = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("btts_yes")) ?: 1.85,
        bttsNo = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("btts_no")) ?: 1.95,
        timestamp = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("timestamp")) ?: "",
        isClosing = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("is_closing")) ?: 0
    )

    private fun cursorToPrediction(cursor: Cursor): Prediction = Prediction(
        id = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("id")) ?: 0,
        matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
        modelId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("model_id")) ?: "ensemble_v1",
        predictedOutcome = cursor.getString(cursor.getColumnIndexOrThrow("predicted_outcome")),
        marketType = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("market_type")) ?: "1X2",
        successProb = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("success_prob")) ?: 0.0,
        confidence = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("confidence")) ?: 0.0,
        honesty = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("honesty")) ?: 0.0,
        reliability = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("reliability")) ?: 0.0,
        valueScore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("value_score")) ?: 0.0,
        riskScore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("risk_score")) ?: 0.0,
        qualityScore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("quality_score")) ?: 0.0,
        consistency = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("consistency")) ?: 0.0,
        marketEfficiency = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("market_efficiency")) ?: 0.0,
        scenarioScore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("scenario_score")) ?: 0.0,
        finalScore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("final_score")) ?: 0.0,
        kellyFraction = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("kelly_fraction")) ?: 0.0,
        recommendedStake = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("recommended_stake")) ?: 0.0
    )

    private fun cursorToCoupon(cursor: Cursor): Coupon = Coupon(
        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
        couponType = cursor.getString(cursor.getColumnIndexOrThrow("coupon_type")),
        totalOdds = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("total_odds")) ?: 0.0,
        stake = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("stake")) ?: 0.0,
        potentialReturn = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("potential_return")) ?: 0.0,
        status = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("status")) ?: "PENDING",
        totalLegs = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("total_legs")) ?: 0,
        wonLegs = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("won_legs")) ?: 0,
        strategyTag = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("strategy_tag")) ?: "BALANCED",
        createdAt = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("created_at")) ?: ""
    )

    private fun cursorToCouponLegs(cursor: Cursor): List<CouponLeg> {
        val legs = mutableListOf<CouponLeg>()
        while (cursor.moveToNext()) {
            legs.add(CouponLeg(
                id = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("id")) ?: 0,
                couponId = cursor.getString(cursor.getColumnIndexOrThrow("coupon_id")),
                matchId = cursor.getString(cursor.getColumnIndexOrThrow("match_id")),
                predictionId = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("prediction_id")) ?: 0,
                legOrder = cursor.getIntOrNull(cursor.getColumnIndexOrThrow("leg_order")) ?: 0,
                selection = cursor.getString(cursor.getColumnIndexOrThrow("selection")),
                marketType = cursor.getString(cursor.getColumnIndexOrThrow("market_type")),
                odds = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("odds")) ?: 0.0,
                stakeAllocated = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("stake_allocated")) ?: 0.0,
                status = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("status")) ?: "PENDING"
            ))
        }
        return legs
    }

    private fun cursorToTransactions(cursor: Cursor): List<BankrollTransaction> {
        val txns = mutableListOf<BankrollTransaction>()
        while (cursor.moveToNext()) {
            txns.add(BankrollTransaction(
                id = cursor.getLongOrNull(cursor.getColumnIndexOrThrow("id")) ?: 0,
                amount = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("amount")) ?: 0.0,
                txnType = cursor.getString(cursor.getColumnIndexOrThrow("txn_type")),
                referenceType = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("reference_type")),
                referenceId = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("reference_id")),
                balanceBefore = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("balance_before")) ?: 0.0,
                balanceAfter = cursor.getDoubleOrNull(cursor.getColumnIndexOrThrow("balance_after")) ?: 0.0,
                note = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("note")),
                timestamp = cursor.getStringOrNull(cursor.getColumnIndexOrThrow("timestamp")) ?: ""
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

// ============================================
// EXTENSION HELPERS
// ============================================
private fun Cursor.getStringOrNull(index: Int): String? {
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.getIntOrNull(index: Int): Int? {
    return if (isNull(index)) null else getInt(index)
}

private fun Cursor.getLongOrNull(index: Int): Long? {
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.getDoubleOrNull(index: Int): Double? {
    return if (isNull(index)) null else getDouble(index)
}

private fun ApiClient.Result<*>.exceptionOrNull(): Exception? {
    return if (this is ApiClient.Result.Failure) exception else null
}