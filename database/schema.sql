-- Sports Betting Prediction System Database Schema

CREATE TABLE IF NOT EXISTS matches (
    id TEXT PRIMARY KEY,
    sport TEXT NOT NULL DEFAULT 'football',
    league TEXT NOT NULL,
    home_team TEXT NOT NULL,
    away_team TEXT NOT NULL,
    match_date DATETIME NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, LIVE, FINISHED, CANCELLED
    home_score INTEGER DEFAULT NULL,
    away_score INTEGER DEFAULT NULL,
    metadata JSON DEFAULT '{}',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS odds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL,
    bookmaker TEXT NOT NULL,
    market_type TEXT NOT NULL DEFAULT '1X2',
    home_win REAL,
    draw REAL,
    away_win REAL,
    over_25 REAL,
    under_25 REAL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(match_id) REFERENCES matches(id)
);

CREATE TABLE IF NOT EXISTS predictions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL,
    predicted_outcome TEXT NOT NULL, -- HOME_WIN, DRAW, AWAY_WIN, OVER_25, UNDER_25
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
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(match_id) REFERENCES matches(id)
);

CREATE TABLE IF NOT EXISTS coupons (
    id TEXT PRIMARY KEY,
    coupon_type TEXT NOT NULL, -- NORMAL, COMPOUND, HIGH_ODDS, SURPRISE, SYSTEM, LIVE_VALUE, HT_FT, CORNER_CARD, PLAYER_PROP, PARLAY
    total_odds REAL NOT NULL,
    stake REAL NOT NULL DEFAULT 10.0,
    potential_return REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, WON, LOST, CANCELLED
    total_legs INTEGER NOT NULL,
    won_legs INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    settled_at DATETIME DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS coupon_legs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coupon_id TEXT NOT NULL,
    match_id TEXT NOT NULL,
    prediction_id INTEGER NOT NULL,
    selection TEXT NOT NULL,
    odds REAL NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    FOREIGN KEY(coupon_id) REFERENCES coupons(id),
    FOREIGN KEY(match_id) REFERENCES matches(id),
    FOREIGN KEY(prediction_id) REFERENCES predictions(id)
);

CREATE TABLE IF NOT EXISTS bankroll (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    initial_balance REAL NOT NULL DEFAULT 1000.0,
    current_balance REAL NOT NULL DEFAULT 1000.0,
    total_staked REAL NOT NULL DEFAULT 0.0,
    total_profit REAL NOT NULL DEFAULT 0.0,
    roi REAL NOT NULL DEFAULT 0.0,
    stop_loss_limit REAL NOT NULL DEFAULT 200.0,
    take_profit_limit REAL NOT NULL DEFAULT 2000.0,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bankroll_txns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    amount REAL NOT NULL,
    txn_type TEXT NOT NULL, -- STAKE, WINNING, DEPOSIT, WITHDRAWAL
    reference_id TEXT DEFAULT NULL,
    note TEXT DEFAULT NULL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    category TEXT NOT NULL DEFAULT 'general',
    description TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    actor TEXT NOT NULL DEFAULT 'SYSTEM',
    details TEXT,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);
