from fastapi import FastAPI, Request, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
import os
import sqlite3

from database.db import get_db, init_db
from engines.prediction_engine import calculate_10_metrics
from engines.coupon_engine import generate_daily_coupons
from engines.bankroll_engine import get_bankroll_status, update_bankroll

app = FastAPI(title="Sports Betting Prediction & Bankroll System", version="1.0.0")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))

@app.on_event("startup")
def startup_event():
    init_db()
    # Seed mock matches if empty
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM matches")
    if cursor.fetchone()[0] == 0:
        mock_matches = [
            ("m1", "football", "Süper Lig", "Galatasaray", "Fenerbahçe", "2026-11-05 19:00:00"),
            ("m2", "football", "Premier League", "Arsenal", "Chelsea", "2026-11-05 20:30:00"),
            ("m3", "football", "La Liga", "Real Madrid", "Barcelona", "2026-11-06 21:00:00"),
            ("m4", "football", "Serie A", "Inter", "AC Milan", "2026-11-06 22:45:00"),
            ("m5", "football", "Bundesliga", "Bayern Munich", "Dortmund", "2026-11-07 18:30:00"),
        ]
        for m in mock_matches:
            cursor.execute("INSERT OR IGNORE INTO matches (id, sport, league, home_team, away_team, match_date) VALUES (?, ?, ?, ?, ?, ?)", m)
            cursor.execute("INSERT OR IGNORE INTO odds (match_id, bookmaker, market_type, home_win, draw, away_win, over_25, under_25) VALUES (?, 'Bet365', '1X2', 1.95, 3.40, 3.80, 1.70, 2.10)", (m[0],))
            
            # Calculate predictions
            metrics = calculate_10_metrics({"home_team": m[3], "away_team": m[4]}, {"home_win": 1.95, "draw": 3.40, "away_win": 3.80})
            cursor.execute("""
                INSERT INTO predictions (match_id, predicted_outcome, success_prob, confidence, honesty, reliability, value_score, risk_score, quality_score, consistency, market_efficiency, scenario_score, final_score)
                VALUES (?, 'HOME_WIN', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, (m[0], metrics['success_prob'], metrics['confidence'], metrics['honesty'], metrics['reliability'], metrics['value_score'], metrics['risk_score'], metrics['quality_score'], metrics['consistency'], metrics['market_efficiency'], metrics['scenario_score'], metrics['final_score']))
        conn.commit()
    conn.close()

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
        SELECT p.*, m.home_team, m.away_team, m.league 
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

@app.post("/generate-coupons")
async def generate_coupons_endpoint():
    generate_daily_coupons(stake_amount=10.0)
    return RedirectResponse(url="/", status_code=303)

@app.get("/settings", response_class=HTMLResponse)
async def settings_page(request: Request):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM settings")
    settings = [dict(row) for row in cursor.fetchall()]
    conn.close()
    return templates.TemplateResponse("settings.html", {"request": request, "settings": settings})

@app.post("/settings/update")
async def update_setting(key: str = Form(...), value: str = Form(...)):
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("UPDATE settings SET value = ?, updated_at = CURRENT_TIMESTAMP WHERE key = ?", (value, key))
    conn.commit()
    conn.close()
    return RedirectResponse(url="/settings", status_code=303)
