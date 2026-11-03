import uuid
import random
from database.db import get_db

COUPON_TYPES = [
    "NORMAL", "COMPOUND", "HIGH_ODDS", "SURPRISE", "SYSTEM", 
    "LIVE_VALUE", "HT_FT", "CORNER_CARD", "PLAYER_PROP", "PARLAY"
]

def generate_daily_coupons(stake_amount=10.0):
    """
    10 Farklı Kupon Türünde Günlük 100 Kupon Üretme Motoru
    Her türden 10'ar adet kupon üretir.
    """
    conn = get_db()
    cursor = conn.cursor()
    
    # Get available predictions/matches
    cursor.execute("""
        SELECT p.id as pred_id, p.match_id, p.predicted_outcome, p.success_prob, p.value_score,
               o.home_win, o.draw, o.away_win, m.home_team, m.away_team
        FROM predictions p
        JOIN odds o ON p.match_id = o.match_id
        JOIN matches m ON p.match_id = m.id
        LIMIT 50
    """)
    rows = cursor.fetchall()
    
    if not rows:
        conn.close()
        return {"status": "error", "message": "No predictions or matches found to generate coupons."}
    
    generated_count = 0
    
    for c_type in COUPON_TYPES:
        for _ in range(10): # 10 coupons per type = 100 total
            coupon_id = str(uuid.uuid4())[:8]
            # Pick 1 to 4 legs depending on coupon type
            leg_count = 3 if c_type in ["PARLAY", "SYSTEM", "COMPOUND"] else 1
            selected_rows = random.sample(rows, min(leg_count, len(rows)))
            
            total_odds = 1.0
            legs = []
            
            for row in selected_rows:
                odds_val = row['home_win'] if row['predicted_outcome'] == 'HOME_WIN' else row['away_win']
                if not odds_val or odds_val < 1.1:
                    odds_val = 1.75
                total_odds *= odds_val
                legs.append({
                    "match_id": row['match_id'],
                    "prediction_id": row['pred_id'],
                    "selection": row['predicted_outcome'],
                    "odds": round(odds_val, 2)
                })
            
            total_odds = round(total_odds, 2)
            potential_return = round(stake_amount * total_odds, 2)
            
            # Insert Coupon
            cursor.execute("""
                INSERT INTO coupons (id, coupon_type, total_odds, stake, potential_return, status, total_legs)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
            """, (coupon_id, c_type, total_odds, stake_amount, potential_return, len(legs)))
            
            # Insert Legs
            for leg in legs:
                cursor.execute("""
                    INSERT INTO coupon_legs (coupon_id, match_id, prediction_id, selection, odds, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                """, (coupon_id, leg['match_id'], leg['prediction_id'], leg['selection'], leg['odds']))
                
            generated_count += 1
            
    conn.commit()
    conn.close()
    return {"status": "success", "coupons_generated": generated_count}

if __name__ == "__main__":
    print(generate_daily_coupons())
