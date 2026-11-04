from fastapi import FastAPI, Request, Form
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
import os

from database.db import get_db, init_db
from collectors.live_collector import fetch_and_store_live_matches
from engines.coupon_engine import generate_daily_coupons
from engines.bankroll_engine import get_bankroll_status

app = FastAPI(title="Sports Betting OS Live Engine", version="1.1.0")

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
app.mount("/static", StaticFiles(directory=os.path.join(BASE_DIR, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(BASE_DIR, "templates"))

@app.on_event("startup")
def startup_event():
    init_db()
    # Fetch initial live matches on startup
    fetch_and_store_live_matches()

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

@app.post("/sync-live-data")
async def sync_live_data_endpoint():
    fetch_and_store_live_matches()
    generate_daily_coupons(stake_amount=10.0)
    return RedirectResponse(url="/", status_code=303)

@app.post("/generate-coupons")
async def generate_coupons_endpoint():
    generate_daily_coupons(stake_amount=10.0)
    return RedirectResponse(url="/", status_code=303)
