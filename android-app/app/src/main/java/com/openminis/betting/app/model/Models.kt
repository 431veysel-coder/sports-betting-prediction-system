package com.openminis.betting.app.model

import com.google.gson.annotations.SerializedName

data class Bankroll(
    @SerializedName("current_balance") val currentBalance: Double = 1000.0,
    @SerializedName("available_balance") val availableBalance: Double = 1000.0,
    @SerializedName("reserved_balance") val reservedBalance: Double = 0.0,
    @SerializedName("total_staked") val totalStaked: Double = 0.0,
    @SerializedName("total_won") val totalWon: Double = 0.0,
    @SerializedName("total_lost") val totalLost: Double = 0.0,
    @SerializedName("total_profit") val totalProfit: Double = 0.0,
    val roi: Double = 0.0,
    @SerializedName("max_drawdown") val maxDrawdown: Double = 0.0,
    @SerializedName("peak_balance") val peakBalance: Double = 1000.0
)

data class Match(
    val id: String,
    @SerializedName("sport_id") val sportId: String = "football",
    @SerializedName("league_id") val leagueId: String,
    @SerializedName("home_team_id") val homeTeamId: String,
    @SerializedName("away_team_id") val awayTeamId: String,
    @SerializedName("home_team") val homeTeam: String = "",
    @SerializedName("away_team") val awayTeam: String = "",
    @SerializedName("league") val league: String = "",
    @SerializedName("match_date") val matchDate: String = "",
    @SerializedName("kickoff_ts") val kickoffTs: Long = 0,
    val status: String = "SCHEDULED",
    @SerializedName("home_score") val homeScore: Int? = null,
    @SerializedName("away_score") val awayScore: Int? = null
)

data class Odds(
    val id: Long = 0,
    @SerializedName("match_id") val matchId: String,
    val bookmaker: String = "TestBook",
    @SerializedName("market_type") val marketType: String = "1X2",
    @SerializedName("home_win") val homeWin: Double = 2.0,
    val draw: Double = 3.4,
    @SerializedName("away_win") val awayWin: Double = 3.5,
    @SerializedName("over_25") val over25: Double = 1.75,
    @SerializedName("under_25") val under25: Double = 2.05,
    @SerializedName("btts_yes") val bttsYes: Double = 1.85,
    @SerializedName("btts_no") val bttsNo: Double = 1.95,
    val timestamp: String = "",
    @SerializedName("is_closing") val isClosing: Int = 0
)

data class Prediction(
    val id: Long = 0,
    @SerializedName("match_id") val matchId: String,
    @SerializedName("model_id") val modelId: String = "ensemble_v1",
    @SerializedName("predicted_outcome") val predictedOutcome: String,
    @SerializedName("market_type") val marketType: String = "1X2",
    @SerializedName("success_prob") val successProb: Double,
    val confidence: Double,
    val honesty: Double,
    val reliability: Double,
    @SerializedName("value_score") val valueScore: Double,
    @SerializedName("risk_score") val riskScore: Double,
    @SerializedName("quality_score") val qualityScore: Double,
    val consistency: Double,
    @SerializedName("market_efficiency") val marketEfficiency: Double,
    @SerializedName("scenario_score") val scenarioScore: Double,
    @SerializedName("final_score") val finalScore: Double,
    @SerializedName("kelly_fraction") val kellyFraction: Double = 0.0,
    @SerializedName("recommended_stake") val recommendedStake: Double = 0.0
)

data class Coupon(
    val id: String,
    @SerializedName("coupon_type") val couponType: String,
    @SerializedName("total_odds") val totalOdds: Double,
    val stake: Double,
    @SerializedName("potential_return") val potentialReturn: Double,
    val status: String = "PENDING",
    @SerializedName("total_legs") val totalLegs: Int,
    @SerializedName("won_legs") val wonLegs: Int = 0,
    @SerializedName("strategy_tag") val strategyTag: String = "BALANCED",
    @SerializedName("created_at") val createdAt: String = "",
    var legs: List<CouponLeg> = emptyList()
)

data class BankrollTransaction(
    val id: Long = 0,
    val amount: Double,
    @SerializedName("txn_type") val txnType: String,
    @SerializedName("reference_type") val referenceType: String? = null,
    @SerializedName("reference_id") val referenceId: String? = null,
    @SerializedName("balance_before") val balanceBefore: Double,
    @SerializedName("balance_after") val balanceAfter: Double,
    val note: String = "",
    val timestamp: String = ""
)

data class CouponLeg(
    val id: Long = 0,
    @SerializedName("coupon_id") val couponId: String,
    @SerializedName("match_id") val matchId: String,
    @SerializedName("prediction_id") val predictionId: Long,
    @SerializedName("leg_order") val legOrder: Int,
    val selection: String,
    @SerializedName("market_type") val marketType: String,
    val odds: Double,
    @SerializedName("stake_allocated") val stakeAllocated: Double,
    val status: String = "PENDING"
)

// Bridge data classes
data class DataBundle(
    val bankroll: Bankroll,
    val matches: List<Match>,
    val predictions: List<Prediction>,
    val coupons: List<Coupon>,
    val transactions: List<BankrollTransaction>
)

data class SyncResult(
    val success: Boolean = true,
    val count: Int = 0,
    val matches: List<Match> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
    val coupons: List<Coupon> = emptyList(),
    val bankroll: Bankroll? = null,
    val error: String? = null
)

data class Result(
    val success: Boolean = true,
    val count: Int = 0,
    val matches: List<Match> = emptyList(),
    val predictions: List<Prediction> = emptyList(),
    val coupons: List<Coupon> = emptyList(),
    val bankroll: Bankroll? = null,
    val error: String? = null
)