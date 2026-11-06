from fastapi import FastAPI, Request, Form, HTTPException, Query, Body
from fastapi.responses import HTMLResponse, RedirectResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from datetime import datetime
import os
import json

from database.db import get_db, init_db
from collectors.live_collector import fetch_and_store_live_matches, CollectorOrchestrator
from engines.coupon_engine import generate_daily_coupons, CouponEngine
from engines.bankroll_engine import get_bankroll_status, update_bankroll, calculate_kelly_stake
from engines.prediction_engine import calculate_10_metrics, fetch_team_stats, fetch_market_data

app = FastAPI(
    title="Sports Betting OS API",
    version="2.0.0",
    description="Production-grade sports betting prediction & bankroll management API"
)

# CORS for Android WebView + local development
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))

# ============================================
# PYDANTIC MODELS
# ============================================

class MatchResponse(BaseModel):
    id: str
    sport_id: str
    league_id: str
    home_team_id: str
    away_team_id: str
    home_team: str
    away_team: str
    league: str
    match_date: str
    kickoff_ts: int
    status: str
    home_score: Optional[int] = None
    away_score: Optional[int] = None

class OddsResponse(BaseModel):
    id: int
    match_id: str
    bookmaker: str
    market_type: str
    home_win: Optional[float] = None
    draw: Optional[float] = None
    away_win: Optional[float] = None
    over_25: Optional[float] = None
    under_25: Optional[float] = None
    btts_yes: Optional[float] = None
    btts_no: Optional[float] = None
    timestamp: str

class PredictionResponse(BaseModel):
    id: int
    match_id: str
    model_id: str
    predicted_outcome: str
    market_type: str
    success_prob: float
    confidence: float
    honesty: float
    reliability: float
    value_score: float
    risk_score: float
    quality_score: float
    consistency: float
    market_efficiency: float
    scenario_score: float
    final_score: float
    kelly_fraction: float
    recommended_stake: float
    home_exp: Optional[float] = None
    away_exp: Optional[float] = None
    prob_1x2: Optional[Dict[str, float]] = None
    prob_over_25: Optional[float] = None
    prob_under_25: Optional[float] = None
    prob_btts_yes: Optional[float] = None
    prob_btts_no: Optional[float] = None

class CouponLegResponse(BaseModel):
    id: int
    coupon_id: str
    match_id: str
    prediction_id: int
    leg_order: int
    selection: str
    market_type: str
    odds: float
    stake_allocated: float
    status: str

class CouponResponse(BaseModel):
    id: str
    coupon_type: str
    total_odds: float
    stake: float
    potential_return: float
    strategy_tag: str
    status: str
    total_legs: int
    won_legs: int
    settled_legs: int
    created_at: str
    settled_at: Optional[str] = None
    legs: List[CouponLegResponse] = []

class BankrollResponse(BaseModel):
    id: int = 1
    initial_balance: float
    current_balance: float
    available_balance: float
    reserved_balance: float
    total_staked: float
    total_won: float
    total_lost: float
    total_profit: float
    roi: float
    max_drawdown: float
    peak_balance: float
    stop_loss_limit: float
    take_profit_limit: float
    daily_loss_limit: float
    max_single_stake_pct: float
    max_daily_stakes: int
    kelly_fraction: float
    min_value_threshold: float
    min_confidence_threshold: float

class BankrollTransactionResponse(BaseModel):
    id: int
    amount: float
    txn_type: str
    reference_type: Optional[str] = None
    reference_id: Optional[str] = None
    balance_before: float
    balance_after: float
    note: Optional[str] = None
    timestamp: str

class SettingsResponse(BaseModel):
    key: str
    value: str
    value_type: str
    category: str
    subcategory: Optional[str] = None
    description: Optional[str] = None
    is_sensitive: bool
    is_user_editable: bool

class SyncRequest(BaseModel):
    days_ahead: int = Field(default=7, ge=1, le=30)
    sources: Optional[List[str]] = None

class GenerateCouponsRequest(BaseModel):
    stake_per_coupon: float = Field(default=10.0, ge=1.0, le=1000.0)
    strategy: str = Field(default="BALANCED", pattern="^(VALUE|HIGH_CONFIDENCE|LONGSHOT|BALANCED)$")
    max_coupons: int = Field(default=20, ge=1, le=100)
    coupon_types: Optional[List[str]] = None

class PlaceBetRequest(BaseModel):
    coupon_id: str
    amount: float = Field(gt=0)

class PredictionRequest(BaseModel):
    match_id: str
    force_refresh: bool = False

# ============================================
# STARTUP / SHUTDOWN
# ============================================

@app.on_event("startup")
def startup_event():
    init_db()
    fetch_and_store_live_matches()

# ============================================
# HEALTH & INFO
# ============================================

@app.get("/health")
async def health_check():
    return {
        "status": "healthy",
        "version": "2.0.0",
        "timestamp": datetime.now().isoformat(),
        "service": "sports-betting-api"
    }

@app.get("/api/info")
async def api_info():
    return {
        "name": "Sports Betting OS API",
        "version": "2.0.0",
        "endpoints": {
            "matches": "/api/matches",
            "predictions": "/api/predictions",
            "coupons": "/api/coupons",
            "bankroll": "/api/bankroll",
            "settings": "/api/settings",
            "sync": "/api/sync",
            "generate": "/api/generate-coupons"
        }
    }

# ============================================
# MATCHES
# ============================================

@app.get("/api/matches", response_model=List[MatchResponse])
async def get_matches(
    status: Optional[str] = Query(None, pattern="^(SCHEDULED|LIVE|FINISHED|CANCELLED|POSTPONED)$"),
    league_id: Optional[str] = None,
    days_ahead: int = Query(7, ge=0, le=30),
    limit: int = Query(100, ge=1, le=500)
):
    conn = get_db()
    cursor = conn.cursor()
    
    where_clauses = []
    params = []
    
    if status:
        where_clauses.append("m.status = ?")
        params.append(status)
    else:
        where_clauses.append("m.status IN ('SCHEDULED', 'LIVE')")
    
    if league_id:
        where_clauses.append("m.league_id = ?")
        params.append(league_id)
    
    if days_ahead > 0:
        where_clauses.append("m.match_date <= datetime('now', ?)")
        params.append(f"+{days_ahead} days")
    
    where_sql = " AND ".join(where_clauses) if where_clauses else "1=1"
    
    cursor.execute(f"""
        SELECT m.*, t1.name as home_team, t2.name as away_team, l.name as league
        FROM matches m
        LEFT JOIN teams t1 ON m.home_team_id = t1.id
        LEFT JOIN teams t2 ON m.away_team_id = t2.id
        LEFT JOIN leagues l ON m.league_id = l.id
        WHERE {where_sql}
        ORDER BY m.match_date ASC
        LIMIT ?
    """, params + [limit])
    
    matches = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return matches

@app.get("/api/matches/{match_id}", response_model=MatchResponse)
async def get_match(match_id: str):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT m.*, t1.name as home_team, t2.name as away_team, l.name as league
        FROM matches m
        LEFT JOIN teams t1 ON m.home_team_id = t1.id
        LEFT JOIN teams t2 ON m.away_team_id = t2.id
        LEFT JOIN leagues l ON m.league_id = l.id
        WHERE m.id = ?
    """, (match_id,))
    row = cursor.fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "Match not found")
    return dict(row)

# ============================================
# ODDS
# ============================================

@app.get("/api/matches/{match_id}/odds", response_model=List[OddsResponse])
async def get_match_odds(match_id: str):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC
    """, (match_id,))
    odds = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return odds

@app.get("/api/matches/{match_id}/odds/latest", response_model=OddsResponse)
async def get_latest_odds(match_id: str):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1", (match_id,))
    row = cursor.fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "No odds found for match")
    return dict(row)

# ============================================
# PREDICTIONS
# ============================================

@app.get("/api/predictions", response_model=List[PredictionResponse])
async def get_predictions(
    match_id: Optional[str] = None,
    min_score: float = Query(0, ge=0, le=100),
    limit: int = Query(50, ge=1, le=200)
):
    conn = get_db()
    cursor = conn.cursor()
    
    if match_id:
        cursor.execute("""
            SELECT p.* FROM predictions p
            WHERE p.match_id = ? AND p.final_score >= ?
            ORDER BY p.final_score DESC LIMIT ?
        """, (match_id, min_score, limit))
    else:
        cursor.execute("""
            SELECT p.* FROM predictions p
            WHERE p.final_score >= ?
            ORDER BY p.final_score DESC LIMIT ?
        """, (min_score, limit))
    
    predictions = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return predictions

@app.get("/api/predictions/{prediction_id}", response_model=PredictionResponse)
async def get_prediction(prediction_id: int):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM predictions WHERE id = ?", (prediction_id,))
    row = cursor.fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "Prediction not found")
    return dict(row)

@app.post("/api/predictions/generate", response_model=PredictionResponse)
async def generate_prediction(req: PredictionRequest):
    """Generate prediction for a specific match using the 10-metric engine"""
    conn = get_db()
    cursor = conn.cursor()
    
    # Get match data
    cursor.execute("""
        SELECT m.*, t1.name as home_team, t2.name as away_team, l.name as league
        FROM matches m
        LEFT JOIN teams t1 ON m.home_team_id = t1.id
        LEFT JOIN teams t2 ON m.away_team_id = t2.id
        LEFT JOIN leagues l ON m.league_id = l.id
        WHERE m.id = ?
    """, (req.match_id,))
    match = cursor.fetchone()
    if not match:
        conn.close()
        raise HTTPException(404, "Match not found")
    
    match_dict = dict(match)
    
    # Get latest odds
    cursor.execute("SELECT * FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1", (req.match_id,))
    odds_row = cursor.fetchone()
    if not odds_row:
        conn.close()
        raise HTTPException(404, "No odds available for match")
    odds = dict(odds_row)
    
    # Fetch team stats
    team_stats = fetch_team_stats(match_dict['home_team_id'], match_dict['away_team_id'], match_dict['match_date'])
    
    # Fetch market data (simplified)
    market_data = {
        'league_model_accuracy': 0.75,
        'league_tier_score': 0.8,
        'odds_volatility': 0.1,
        'correlation_risk': 0.1,
        'match_importance': 0.5,
        'liquidity': 'high',
        'market_efficiency': 0.85,
        'scenario_simulations': 1000
    }
    
    # Calculate 10 metrics
    odds_data = {
        'home_win': odds.get('home_win', 2.0),
        'draw': odds.get('draw', 3.4),
        'away_win': odds.get('away_win', 3.5),
        'over_25': odds.get('over_25', 1.75),
        'under_25': odds.get('under_25', 2.05),
        'btts_yes': odds.get('btts_yes', 1.85),
        'btts_no': odds.get('btts_no', 1.95)
    }
    
    metrics = calculate_10_metrics(match_dict, odds_data, team_stats, market_data)
    
    # Save prediction
    cursor.execute("""
        INSERT INTO predictions (
            match_id, model_id, predicted_outcome, market_type,
            success_prob, confidence, honesty, reliability, value_score,
            risk_score, quality_score, consistency, market_efficiency, scenario_score,
            final_score, kelly_fraction, recommended_stake, features_json
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """, (
        req.match_id, "ensemble_v1", metrics['predicted_outcome'], metrics['market_type'],
        metrics['success_prob'], metrics['confidence'], metrics['honesty'], metrics['reliability'],
        metrics['value_score'], metrics['risk_score'], metrics['quality_score'], metrics['consistency'],
        metrics['market_efficiency'], metrics['scenario_score'], metrics['final_score'],
        metrics['kelly_fraction'], metrics['recommended_stake'], json.dumps({
            'home_exp': metrics['home_exp'], 'away_exp': metrics['away_exp'],
            'prob_1x2': metrics['prob_1x2'], 'prob_over_25': metrics['prob_over_25'],
            'prob_under_25': metrics['prob_under_25'], 'prob_btts_yes': metrics['prob_btts_yes'],
            'prob_btts_no': metrics['prob_btts_no']
        })
    ))
    prediction_id = cursor.lastrowid
    conn.commit()
    conn.close()
    
    # Return full prediction with extras
    metrics['id'] = prediction_id
    metrics['match_id'] = req.match_id
    metrics['model_id'] = "ensemble_v1"
    metrics['features_json'] = json.dumps({
        'home_exp': metrics['home_exp'], 'away_exp': metrics['away_exp'],
        'prob_1x2': metrics['prob_1x2'], 'prob_over_25': metrics['prob_over_25'],
        'prob_under_25': metrics['prob_under_25'], 'prob_btts_yes': metrics['prob_btts_yes'],
        'prob_btts_no': metrics['prob_btts_no']
    })
    return metrics

@app.post("/api/predictions/batch-generate")
async def batch_generate_predictions(match_ids: List[str] = Body(...)):
    """Generate predictions for multiple matches"""
    results = []
    for match_id in match_ids:
        try:
            pred = await generate_prediction(PredictionRequest(match_id=match_id))
            results.append({"match_id": match_id, "success": True, "prediction": pred})
        except HTTPException as e:
            results.append({"match_id": match_id, "success": False, "error": e.detail})
    return {"results": results}

# ============================================
# COUPONS
# ============================================

@app.get("/api/coupons", response_model=List[CouponResponse])
async def get_coupons(
    status: Optional[str] = Query(None, pattern="^(PENDING|PARTIAL_WON|WON|LOST|CANCELLED|VOIDED)$"),
    coupon_type: Optional[str] = None,
    limit: int = Query(50, ge=1, le=200)
):
    conn = get_db()
    cursor = conn.cursor()
    
    where_clauses = []
    params = []
    
    if status:
        where_clauses.append("c.status = ?")
        params.append(status)
    if coupon_type:
        where_clauses.append("c.coupon_type = ?")
        params.append(coupon_type)
    
    where_sql = " AND ".join(where_clauses) if where_clauses else "1=1"
    
    cursor.execute(f"""
        SELECT c.* FROM coupons c
        WHERE {where_sql}
        ORDER BY c.created_at DESC LIMIT ?
    """, params + [limit])
    
    coupons = [dict(row) for row in cursor.fetchall()]
    
    # Fetch legs for each coupon
    for coupon in coupons:
        cursor.execute("""
            SELECT cl.*, p.predicted_outcome, p.success_prob
            FROM coupon_legs cl
            LEFT JOIN predictions p ON cl.prediction_id = p.id
            WHERE cl.coupon_id = ?
            ORDER BY cl.leg_order
        """, (coupon['id'],))
        coupon['legs'] = [dict(row) for row in cursor.fetchall()]
    
    conn.close()
    return coupons

@app.get("/api/coupons/{coupon_id}", response_model=CouponResponse)
async def get_coupon(coupon_id: str):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM coupons WHERE id = ?", (coupon_id,))
    coupon = cursor.fetchone()
    if not coupon:
        conn.close()
        raise HTTPException(404, "Coupon not found")
    coupon = dict(coupon)
    
    cursor.execute("""
        SELECT cl.*, p.predicted_outcome, p.success_prob
        FROM coupon_legs cl
        LEFT JOIN predictions p ON cl.prediction_id = p.id
        WHERE cl.coupon_id = ?
        ORDER BY cl.leg_order
    """, (coupon_id,))
    coupon['legs'] = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return coupon

@app.post("/api/generate-coupons", response_model=List[CouponResponse])
async def generate_coupons(req: GenerateCouponsRequest):
    """Generate daily coupons using the coupon engine"""
    engine = CouponEngine()
    candidates = engine.get_daily_candidates(req.strategy)
    
    if not candidates:
        raise HTTPException(400, "No valid predictions found for coupon generation")
    
    # Limit candidates
    candidates = candidates[:req.max_coupons * 3]  # Buffer for variety
    
    new_coupons = []
    stake = req.stake_per_coupon
    
    # Determine which coupon types to generate
    types_to_generate = req.coupon_types or ['SINGLE', 'ACCUMULATOR_4', 'SYSTEM_2_3', 'LUCKY15']
    
    for ctype in types_to_generate:
        if ctype == 'SINGLE':
            coupons = engine.generate_single_coupons(candidates, stake, min(req.max_coupons, len(candidates)))
        elif ctype.startswith('ACCUMULATOR'):
            legs = int(ctype.split('_')[1]) if '_' in ctype else 4
            coupons = engine.generate_accumulator_coupons(candidates, stake, min(5, req.max_coupons), (legs, legs))
        elif ctype.startswith('SYSTEM_') or ctype in ['LUCKY15', 'LUCKY31', 'LUCKY63', 'YANKEE', 'CANADIAN', 'HEINZ', 'SUPER_HEINZ', 'GOLIATH']:
            coupons = engine.generate_system_coupons(candidates, stake, min(3, req.max_coupons), ctype)
        else:
            continue
        
        new_coupons.extend(coupons)
    
    # Save to database
    conn = get_db()
    cursor = conn.cursor()
    now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    
    for coupon in new_coupons:
        cursor.execute("""
            INSERT INTO coupons (id, coupon_type, total_odds, stake, potential_return, strategy_tag, status, total_legs, created_at)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
        """, (coupon['id'], coupon['coupon_type'], coupon['total_odds'], coupon['stake'],
              coupon['potential_return'], coupon['strategy_tag'], coupon['total_legs'], now))
        
        coupon_id = coupon['id']
        for i, leg in enumerate(coupon['legs']):
            cursor.execute("""
                INSERT INTO coupon_legs (coupon_id, match_id, prediction_id, leg_order, selection, market_type, odds, stake_allocated, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
            """, (coupon_id, leg['match_id'], leg['prediction_id'], i+1,
                  leg['selection'], leg['market_type'], leg['odds'], leg['stake_allocated']))
    
    conn.commit()
    conn.close()
    
    # Return saved coupons with legs
    return await get_coupons(limit=len(new_coupons))

@app.post("/api/coupons/{coupon_id}/settle")
async def settle_coupon(coupon_id: str, leg_results: List[Dict[str, Any]] = Body(...)):
    """Settle a coupon with leg results (WON/LOST/VOIDED)"""
    conn = get_db()
    cursor = conn.cursor()
    
    cursor.execute("SELECT * FROM coupons WHERE id = ?", (coupon_id,))
    coupon = cursor.fetchone()
    if not coupon:
        conn.close()
        raise HTTPException(404, "Coupon not found")
    
    coupon = dict(coupon)
    won_legs = 0
    settled_legs = 0
    
    for leg_result in leg_results:
        leg_id = leg_result.get('leg_id')
        result = leg_result.get('result')  # WON, LOST, VOIDED
        
        cursor.execute("""
            UPDATE coupon_legs SET status = ?, settled_at = ?
            WHERE id = ? AND coupon_id = ?
        """, (result, datetime.now().isoformat(), leg_id, coupon_id))
        
        if result == 'WON':
            won_legs += 1
        settled_legs += 1
    
    # Determine coupon status
    if won_legs == coupon['total_legs']:
        coupon_status = 'WON'
        profit = coupon['potential_return'] - coupon['stake']
        update_bankroll(profit, 'WINNING', coupon_id, f"Coupon {coupon_id} won")
    elif settled_legs == coupon['total_legs']:
        coupon_status = 'LOST'
        update_bankroll(-coupon['stake'], 'STAKE', coupon_id, f"Coupon {coupon_id} lost")
    else:
        coupon_status = 'PARTIAL_WON'
    
    cursor.execute("""
        UPDATE coupons SET status = ?, won_legs = ?, settled_legs = ?, settled_at = ?
        WHERE id = ?
    """, (coupon_status, won_legs, settled_legs, datetime.now().isoformat(), coupon_id))
    
    conn.commit()
    conn.close()
    
    return {"success": True, "coupon_id": coupon_id, "status": coupon_status, "won_legs": won_legs}

# ============================================
# BANKROLL
# ============================================

@app.get("/api/bankroll", response_model=BankrollResponse)
async def get_bankroll():
    bankroll = get_bankroll_status()
    if not bankroll:
        raise HTTPException(404, "Bankroll not initialized")
    return bankroll

@app.get("/api/bankroll/transactions", response_model=List[BankrollTransactionResponse])
async def get_transactions(limit: int = Query(50, ge=1, le=200)):
    from database.db import get_db
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM bankroll_transactions ORDER BY timestamp DESC LIMIT ?", (limit,))
    txns = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return txns

@app.post("/api/bankroll/deposit")
async def deposit(amount: float = Body(..., gt=0), note: str = Body(default="")):
    update_bankroll(amount, 'DEPOSIT', note=note)
    return await get_bankroll()

@app.post("/api/bankroll/withdraw")
async def withdraw(amount: float = Body(..., gt=0), note: str = Body(default="")):
    bankroll = get_bankroll_status()
    if bankroll['available_balance'] < amount:
        raise HTTPException(400, "Insufficient available balance")
    update_bankroll(-amount, 'WITHDRAWAL', note=note)
    return await get_bankroll()

@app.post("/api/bankroll/reset")
async def reset_bankroll(initial_balance: float = Body(default=1000.0, gt=0)):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("""
        UPDATE bankroll SET 
            initial_balance = ?, current_balance = ?, available_balance = ?, reserved_balance = 0,
            total_staked = 0, total_won = 0, total_lost = 0, total_profit = 0, roi = 0,
            max_drawdown = 0, peak_balance = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = 1
    """, (initial_balance, initial_balance, initial_balance, initial_balance))
    cursor.execute("DELETE FROM bankroll_transactions")
    cursor.execute("DELETE FROM bankroll_snapshots")
    conn.commit()
    conn.close()
    return await get_bankroll()

# ============================================
# SETTINGS
# ============================================

@app.get("/api/settings", response_model=List[SettingsResponse])
async def get_settings(category: Optional[str] = None):
    conn = get_db()
    cursor = conn.cursor()
    if category:
        cursor.execute("SELECT * FROM settings WHERE category = ? ORDER BY key", (category,))
    else:
        cursor.execute("SELECT * FROM settings ORDER BY category, key")
    settings = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return settings

@app.get("/api/settings/{key}", response_model=SettingsResponse)
async def get_setting(key: str):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM settings WHERE key = ?", (key,))
    row = cursor.fetchone()
    conn.close()
    if not row:
        raise HTTPException(404, "Setting not found")
    return dict(row)

@app.put("/api/settings/{key}")
async def update_setting(key: str, value: str = Body(..., embed=True)):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM settings WHERE key = ?", (key,))
    if not cursor.fetchone():
        conn.close()
        raise HTTPException(404, "Setting not found")
    
    cursor.execute("""
        UPDATE settings SET value = ?, updated_at = CURRENT_TIMESTAMP, updated_by = 'API'
        WHERE key = ?
    """, (value, key))
    conn.commit()
    conn.close()
    return {"success": True, "key": key, "value": value}

# ============================================
# SYNC / DATA COLLECTION
# ============================================

@app.post("/api/sync")
async def sync_data(req: SyncRequest):
    """Trigger live data collection from all configured sources"""
    orchestrator = CollectorOrchestrator()
    results = orchestrator.sync_all_sources(req.days_ahead)
    
    # Generate predictions for new matches
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("""
        SELECT m.id FROM matches m
        WHERE m.status = 'SCHEDULED' AND m.match_date >= datetime('now')
        AND m.match_date <= datetime('now', ?)
        AND NOT EXISTS (SELECT 1 FROM predictions p WHERE p.match_id = m.id)
    """, (f"+{req.days_ahead} days",))
    new_match_ids = [row['id'] for row in cursor.fetchall()]
    conn.close()
    
    prediction_results = []
    for match_id in new_match_ids[:50]:  # Limit to 50 new predictions per sync
        try:
            pred = await generate_prediction(PredictionRequest(match_id=match_id))
            prediction_results.append({"match_id": match_id, "success": True})
        except Exception as e:
            prediction_results.append({"match_id": match_id, "success": False, "error": str(e)})
    
    return {
        "sync_results": results,
        "new_matches_found": len(new_match_ids),
        "predictions_generated": len([r for r in prediction_results if r['success']]),
        "prediction_errors": len([r for r in prediction_results if not r['success']])
    }

@app.get("/api/sync/status")
async def sync_status():
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM data_sources ORDER BY name")
    sources = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return {"sources": sources}

# ============================================
# DASHBOARD (HTML)
# ============================================

@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    conn = get_db()
    cursor = conn.cursor()
    
    bankroll = get_bankroll_status()
    
    cursor.execute("SELECT COUNT(*) FROM matches")
    match_count = cursor.fetchone()[0]
    
    cursor.execute("SELECT COUNT(*) FROM coupons")
    coupon_count = cursor.fetchone()[0]
    
    cursor.execute("SELECT * FROM coupons ORDER BY created_at DESC LIMIT 10")
    recent_coupons = [dict(row) for row in cursor.fetchall()]
    
    cursor.execute("""
        SELECT p.*, m.home_team, m.away_team, m.league, m.match_date 
        FROM predictions p 
        JOIN matches m ON p.match_id = m.id 
        ORDER BY p.final_score DESC LIMIT 10
    """)
    top_predictions = [dict(row) for row in cursor.fetchall()]
    
    conn.close()
    
    return templates.TemplateResponse("dashboard.html", {
        "request": request,
        "bankroll": bankroll,
        "match_count": match_count,
        "coupon_count": coupon_count,
        "recent_coupons": recent_coupons,
        "top_predictions": top_predictions
    })

# ============================================
# ERROR HANDLERS
# ============================================

@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": exc.detail, "status_code": exc.status_code}
    )

@app.exception_handler(Exception)
async def general_exception_handler(request: Request, exc: Exception):
    return JSONResponse(
        status_code=500,
        content={"error": "Internal server error", "detail": str(exc)}
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)