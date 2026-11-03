import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(__file__), "..", "data", "sports_betting.db")

def get_db():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn

def init_db():
    conn = get_db()
    schema_path = os.path.join(os.path.dirname(__file__), "schema.sql")
    with open(schema_path, "r", encoding="utf-8") as f:
        conn.executescript(f.read())
    
    # Initialize default bankroll if not exists
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM bankroll")
    if cursor.fetchone()[0] == 0:
        cursor.execute("""
            INSERT INTO bankroll (id, initial_balance, current_balance, total_staked, total_profit, roi, stop_loss_limit, take_profit_limit)
            VALUES (1, 1000.0, 1000.0, 0.0, 0.0, 0.0, 200.0, 2000.0)
        """)
    
    # Seed default settings
    default_settings = [
        ('min_confidence_threshold', '0.65', 'prediction', 'Minimum güven oranı threshold'),
        ('min_value_score', '1.10', 'prediction', 'Minimum değer oranı threshold'),
        ('stake_per_coupon', '10.0', 'bankroll', 'Kupon başı sabit bahis miktarı (TL)'),
        ('daily_max_coupons', '100', 'coupon', 'Günlük maksimum üretilecek kupon sayısı'),
        ('telegram_bot_enabled', 'false', 'notification', 'Telegram bildirimleri aktif mi'),
        ('stop_loss_limit', '200.0', 'bankroll', 'Kasa stop-loss alt sınırı (TL)'),
        ('take_profit_limit', '2000.0', 'bankroll', 'Kasa take-profit üst sınırı (TL)'),
    ]
    
    for key, val, cat, desc in default_settings:
        cursor.execute("""
            INSERT OR IGNORE INTO settings (key, value, category, description)
            VALUES (?, ?, ?, ?)
        """, (key, val, cat, desc))
        
    conn.commit()
    conn.close()

if __name__ == "__main__":
    init_db()
    print("Database initialized successfully.")
