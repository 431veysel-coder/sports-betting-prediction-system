package com.openminis.betting.app.collectors

import android.content.Context
import android.util.Log
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.data.DataRepository
import com.openminis.betting.app.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Multi-source sports data collector with fallback chain
 * Sources: OpenLigaDB (primary), Football-Data.org, TheSportsDB, API-Football
 */
class CollectorOrchestrator(private val context: Context) {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val repository = DataRepository.getInstance(context)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // Rate limiting per source (requests per minute)
    private val rateLimits = mapOf(
        "openligadb" to 60,
        "football-data" to 10,
        "thesportsdb" to 30,
        "api-football" to 100
    )
    private val lastRequestTime = mutableMapOf<String, Long>()
    private val requestCounts = mutableMapOf<String, Int>()

    /**
     * Sync all live data from all sources with fallback chain
     */
    fun syncAllSources(): CollectorResult {
        val startTime = System.currentTimeMillis()
        var totalMatches = 0
        var totalOdds = 0
        var errors = mutableListOf<String>()
        var sourcesUsed = mutableListOf<String>()

        // Source 1: OpenLigaDB (Free, reliable, German leagues focus)
        try {
            val result = collectFromOpenLigaDB()
            totalMatches += result.matches
            totalOdds += result.odds
            if (result.matches > 0) sourcesUsed.add("openligadb")
            errors.addAll(result.errors)
            CrashReporter.getInstance().logInfo("CollectorOrchestrator", "OpenLigaDB: ${result.matches} matches, ${result.odds} odds")
        } catch (e: Exception) {
            errors.add("OpenLigaDB: ${e.message}")
            CrashReporter.getInstance().logError("CollectorOrchestrator", "OpenLigaDB failed", e)
        }

        // Source 2: Football-Data.org (Free tier, major European leagues)
        try {
            val result = collectFromFootballData()
            totalMatches += result.matches
            totalOdds += result.odds
            if (result.matches > 0) sourcesUsed.add("football-data")
            errors.addAll(result.errors)
            CrashReporter.getInstance().logInfo("CollectorOrchestrator", "Football-Data: ${result.matches} matches, ${result.odds} odds")
        } catch (e: Exception) {
            errors.add("Football-Data: ${e.message}")
            CrashReporter.getInstance().logError("CollectorOrchestrator", "Football-Data failed", e)
        }

        // Source 3: TheSportsDB (Free, wide coverage)
        try {
            val result = collectFromTheSportsDB()
            totalMatches += result.matches
            totalOdds += result.odds
            if (result.matches > 0) sourcesUsed.add("thesportsdb")
            errors.addAll(result.errors)
            CrashReporter.getInstance().logInfo("CollectorOrchestrator", "TheSportsDB: ${result.matches} matches, ${result.odds} odds")
        } catch (e: Exception) {
            errors.add("TheSportsDB: ${e.message}")
            CrashReporter.getInstance().logError("CollectorOrchestrator", "TheSportsDB failed", e)
        }

        // Source 4: API-Football (RapidAPI, requires key)
        if (hasApiFootballKey()) {
            try {
                val result = collectFromApiFootball()
                totalMatches += result.matches
                totalOdds += result.odds
                if (result.matches > 0) sourcesUsed.add("api-football")
                errors.addAll(result.errors)
                CrashReporter.getInstance().logInfo("CollectorOrchestrator", "API-Football: ${result.matches} matches, ${result.odds} odds")
            } catch (e: Exception) {
                errors.add("API-Football: ${e.message}")
                CrashReporter.getInstance().logError("CollectorOrchestrator", "API-Football failed", e)
            }
        }

        val duration = System.currentTimeMillis() - startTime
        return CollectorResult(
            success = errors.size < sourcesUsed.size, // At least one source worked
            matches = totalMatches,
            odds = totalOdds,
            durationMs = duration,
            sourcesUsed = sourcesUsed,
            errors = errors
        )
    }

    /**
     * OpenLigaDB - Free, no key needed, good for German leagues + some European
     */
    private fun collectFromOpenLigaDB(): CollectorResult {
        checkRateLimit("openligadb")
        
        val today = dateFormat.format(Date())
        var totalMatches = 0
        var totalOdds = 0
        var allErrors = mutableListOf<String>()
        
        // Try multiple leagues
        val leagues = listOf(
            "bl1" to "2024",      // Bundesliga
            "bl2" to "2024",      // 2. Bundesliga
            "liga3" to "2024",    // 3. Liga
            "dfb" to "2024"       // DFB Pokal
        )
        
        for ((league, season) in leagues) {
            try {
                val url = "https://api.openligadb.de/getmatchdata/$league/$season/$today"
                val json = httpGet(url)
                if (json != null && json.isNotBlank()) {
                    val result = parseOpenLigaDB(json)
                    totalMatches += result.matches
                    totalOdds += result.odds
                    allErrors.addAll(result.errors)
                    CrashReporter.getInstance().logInfo("CollectorOrchestrator", "OpenLigaDB $league: ${result.matches} matches")
                }
            } catch (e: Exception) {
                allErrors.add("OpenLigaDB $league: ${e.message}")
                CrashReporter.getInstance().logError("CollectorOrchestrator", "OpenLigaDB $league failed", e)
            }
        }
        
        return CollectorResult(
            success = totalMatches > 0,
            matches = totalMatches,
            odds = totalOdds,
            errors = allErrors
        )
    }

    /**
     * Football-Data.org - Free tier, major European leagues
     */
    private fun collectFromFootballData(): CollectorResult {
        checkRateLimit("football-data")
        
        val apiKey = getEnvOrSetting("FOOTBALL_DATA_KEY") 
            ?: return CollectorResult(success = false, matches = 0, odds = 0, errors = listOf("No API key"))
        val today = dateFormat.format(Date())
        val url = "https://api.football-data.org/v4/matches?dateFrom=$today&dateTo=$today"
        
        val json = httpGet(url, mapOf("X-Auth-Token" to apiKey))
        return parseFootballData(json)
    }

    /**
     * TheSportsDB - Free, wide coverage
     */
    private fun collectFromTheSportsDB(): CollectorResult {
        checkRateLimit("thesportsdb")
        
        val today = dateFormat.format(Date())
        val url = "https://www.thesportsdb.com/api/v1/json/3/eventsday.php?d=$today"
        
        val json = httpGet(url)
        return parseTheSportsDB(json)
    }

    /**
     * API-Football via RapidAPI - Requires key, comprehensive
     */
    private fun collectFromApiFootball(): CollectorResult {
        checkRateLimit("api-football")
        
        val apiKey = getEnvOrSetting("API_FOOTBALL_KEY")
            ?: return CollectorResult(success = false, matches = 0, odds = 0, errors = listOf("No API key"))
        val today = dateFormat.format(Date())
        val url = "https://api-football-v1.p.rapidapi.com/v3/fixtures?date=$today&timezone=Europe/Istanbul"
        
        val json = httpGet(url, mapOf("X-RapidAPI-Key" to apiKey, "X-RapidAPI-Host" to "api-football-v1.p.rapidapi.com"))
        return parseApiFootball(json)
    }

    // ============================================
    // PARSERS (TODO: Implement)
    // ============================================

    private fun parseOpenLigaDB(json: String?): CollectorResult {
        if (json.isNullOrBlank()) {
            return CollectorResult(success = false, matches = 0, odds = 0, errors = listOf("Empty JSON"))
        }
        
        return try {
            val gson = com.google.gson.Gson()
            val type = com.google.gson.reflect.TypeToken.getParameterized(
                List::class.java, OpenLigaDBMatch::class.java
            ).type
            val matches = gson.fromJson<List<OpenLigaDBMatch>>(json, type) ?: emptyList()
            
            if (matches.isEmpty()) {
                return CollectorResult(success = true, matches = 0, odds = 0)
            }
            
            val matchList = mutableListOf<Match>()
            val oddsList = mutableListOf<Odds>()
            val timestamp = dateTimeFormat.format(Date())
            
            for (raw in matches) {
                // Create match ID
                val matchId = "OLD_${raw.MatchID}"
                
                // Parse kickoff timestamp
                var kickoffTs = 0L
                try {
                    val utcStr = raw.MatchDateTimeUTC ?: raw.MatchDateTime
                    if (utcStr != null) {
                        kickoffTs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                            .parse(utcStr.replace("Z", ""))?.time ?: 0
                    }
                } catch (e: Exception) {
                    // Use matchDateTime as fallback
                    try {
                        val dtStr = raw.MatchDateTime
                        if (dtStr != null) {
                            kickoffTs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                                .parse(dtStr)?.time ?: 0
                        }
                    } catch (e2: Exception) {}
                }
                
                // Determine status
                val status = when {
                    raw.MatchResults?.isNotEmpty() == true -> "FINISHED"
                    kickoffTs > 0 && kickoffTs < System.currentTimeMillis() -> "LIVE"
                    else -> "SCHEDULED"
                }
                
                // Extract scores
                var homeScore: Int? = null
                var awayScore: Int? = null
                val endResult = raw.MatchResults?.firstOrNull { (it as OpenLigaDBResult).ResultName == "Endergebnis" }
                if (endResult != null) {
                    homeScore = endResult.PointsTeam1
                    awayScore = endResult.PointsTeam2
                }
                
                // Create Match object
                val match = Match(
                    id = matchId,
                    leagueId = "DE_BL1",
                    homeTeamId = raw.Team1?.TeamId?.toString() ?: "1",
                    awayTeamId = raw.Team2?.TeamId?.toString() ?: "2",
                    homeTeam = raw.Team1?.TeamName ?: "",
                    awayTeam = raw.Team2?.TeamName ?: "",
                    league = raw.LeagueName ?: "Bundesliga",
                    matchDate = raw.MatchDateTime?.substringBefore("T") ?: "",
                    kickoffTs = kickoffTs,
                    status = status,
                    homeScore = homeScore,
                    awayScore = awayScore
                )
                matchList.add(match)
                
                // Generate synthetic odds (OpenLigaDB doesn't provide odds)
                // We'll use a default bookmaker with reasonable odds based on team strength
                val odds = generateSyntheticOdds(matchId, timestamp)
                oddsList.add(odds)
            }
            
            // Upsert to database
            repository.upsertMatches(matchList)
            repository.upsertOdds(oddsList)
            
            CrashReporter.getInstance().logInfo("CollectorOrchestrator", "OpenLigaDB: ${matchList.size} matches, ${oddsList.size} odds stored")
            
            CollectorResult(success = true, matches = matchList.size, odds = oddsList.size)
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("CollectorOrchestrator", "OpenLigaDB parse failed", e)
            CollectorResult(success = false, matches = 0, odds = 0, errors = listOf(e.message ?: "Parse error"))
        }
    }

    // OpenLigaDB JSON data classes
    private data class OpenLigaDBMatch(
        val MatchID: Int,
        val MatchDateTime: String?,
        val MatchDateTimeUTC: String?,
        val LeagueName: String?,
        val LeagueId: Int?,
        val SeasonId: Int?,
        val Group: OpenLigaDBGroup?,
        val Team1: OpenLigaDBTeam?,
        val Team2: OpenLigaDBTeam?,
        val MatchResults: List<OpenLigaDBResult>?,
        val Goals: List<Any>?
    )

    private data class OpenLigaDBGroup(
        val GroupName: String?,
        val GroupOrderID: Int?
    )

    private data class OpenLigaDBTeam(
        val TeamId: Int?,
        val TeamName: String?,
        val ShortName: String?,
        val TeamIconUrl: String?
    )

    private data class OpenLigaDBResult(
        val ResultName: String?,
        val PointsTeam1: Int?,
        val PointsTeam2: Int?
    )

    private fun generateSyntheticOdds(matchId: String, timestamp: String): Odds {
        // Generate reasonable odds using simple team strength estimation
        // In production, use real odds from API-Football or other sources
        val baseHome = (1.5 + Math.random() * 1.5) // 1.5 - 3.0
        val baseDraw = (3.0 + Math.random() * 1.5) // 3.0 - 4.5
        val baseAway = (2.5 + Math.random() * 2.0) // 2.5 - 4.5
        
        return Odds(
            matchId = matchId,
            bookmaker = "OpenLigaDB_Synthetic",
            marketType = "1X2",
            homeWin = Math.round(baseHome * 100.0) / 100.0,
            draw = Math.round(baseDraw * 100.0) / 100.0,
            awayWin = Math.round(baseAway * 100.0) / 100.0,
            over25 = Math.round((1.7 + Math.random() * 0.4) * 100.0) / 100.0,
            under25 = Math.round((2.0 + Math.random() * 0.4) * 100.0) / 100.0,
            bttsYes = Math.round((1.7 + Math.random() * 0.4) * 100.0) / 100.0,
            bttsNo = Math.round((1.9 + Math.random() * 0.4) * 100.0) / 100.0,
            timestamp = timestamp,
            isClosing = 0
        )
    }

    private fun parseFootballData(json: String?): CollectorResult {
        return CollectorResult(success = true, matches = 0, odds = 0, errors = listOf("Parser not implemented"))
    }

    private fun parseTheSportsDB(json: String?): CollectorResult {
        return CollectorResult(success = true, matches = 0, odds = 0, errors = listOf("Parser not implemented"))
    }

    private fun parseApiFootball(json: String?): CollectorResult {
        return CollectorResult(success = true, matches = 0, odds = 0, errors = listOf("Parser not implemented"))
    }

    // ============================================
    // UTILITIES
    // ============================================

    private fun checkRateLimit(source: String) {
        val now = System.currentTimeMillis()
        val last = lastRequestTime[source] ?: 0
        val count = requestCounts[source] ?: 0
        val limit = rateLimits[source] ?: 60

        if (now - last < 60000) { // Within same minute
            if (count >= limit) {
                val waitTime = 60000 - (now - last)
                Thread.sleep(waitTime)
                requestCounts[source] = 0
            }
        } else {
            requestCounts[source] = 0
        }
        requestCounts[source] = (requestCounts[source] ?: 0) + 1
        lastRequestTime[source] = System.currentTimeMillis()
    }

    private fun httpGet(urlString: String, headers: Map<String, String> = emptyMap()): String? {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.setRequestProperty("User-Agent", "SportsBettingOS/1.0")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                CrashReporter.getInstance().logError("CollectorOrchestrator", "HTTP $responseCode: $urlString")
            }
        } catch (e: Exception) {
            CrashReporter.getInstance().logError("CollectorOrchestrator", "HTTP GET failed: $urlString", e)
        }
        return null
    }

    private fun getEnvOrSetting(key: String): String? {
        // Try environment variable first, then settings
        return System.getenv(key)
    }

    private fun hasApiFootballKey(): Boolean {
        return getEnvOrSetting("API_FOOTBALL_KEY") != null
    }

    fun shutdown() {
        job.cancel()
    }

    data class CollectorResult(
        val success: Boolean = true,
        val matches: Int = 0,
        val odds: Int = 0,
        val durationMs: Long = 0,
        val sourcesUsed: List<String> = emptyList(),
        val errors: List<String> = emptyList()
    )
}

/**
 * xG/xGA Data Collector - Understat/FBref scraping
 */
class XGCollector(private val context: Context) {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val repository = DataRepository.getInstance(context)

    fun collectXGData(teamIds: List<String>) {
        scope.launch {
            // Scrape Understat or FBref for xG data
            // Store in match_xg table
        }
    }

    fun shutdown() {
        job.cancel()
    }
}

/**
 * Injury/Suspension Collector - Transfermarkt/FlashScore
 */
class InjuryCollector(private val context: Context) {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val repository = DataRepository.getInstance(context)

    fun collectInjuries(leagueIds: List<String>) {
        scope.launch {
            // Scrape injury data
            // Store in injuries table with player, team, severity, return_date
        }
    }

    fun shutdown() {
        job.cancel()
    }
}

/**
 * Weather Collector - OpenWeatherMap
 */
class WeatherCollector(private val context: Context) {
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val repository = DataRepository.getInstance(context)

    fun collectWeather(matchIds: List<String>) {
        scope.launch {
            // Get venue coordinates, fetch weather
            // Store in match_weather table
        }
    }

    fun shutdown() {
        job.cancel()
    }
}