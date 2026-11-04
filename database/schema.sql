-- Sports Betting Prediction System - Production Database Schema v2.0
-- SQLite/libSQL compatible with full indexing, constraints, and audit trail

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;
PRAGMA synchronous = NORMAL;

-- ============================================
-- CORE ENTITY TABLES
-- ============================================

CREATE TABLE IF NOT EXISTS sports (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS leagues (
    id TEXT PRIMARY KEY,
    sport_id TEXT NOT NULL REFERENCES sports(id),
    name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    country TEXT,
    tier INTEGER DEFAULT 1,
    season TEXT,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_leagues_sport ON leagues(sport_id);
CREATE INDEX IF NOT EXISTS idx_leagues_active ON leagues(is_active);

CREATE TABLE IF NOT EXISTS teams (
    id TEXT PRIMARY KEY,
    sport_id TEXT NOT NULL REFERENCES sports(id),
    league_id TEXT REFERENCES leagues(id),
    name TEXT NOT NULL,
    short_name TEXT,
    display_name TEXT NOT NULL,
    country TEXT,
    founded_year INTEGER,
    venue TEXT,
    is_active INTEGER NOT NULL DEFAULT 1,
    elo_rating REAL DEFAULT 1500.0,
    form_json JSON DEFAULT '[]',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_teams_league ON teams(league_id);
CREATE INDEX IF NOT EXISTS idx_teams_sport ON teams(sport_id);
CREATE INDEX IF NOT EXISTS idx_teams_elo ON teams(elo_rating DESC);

-- ============================================
-- MATCH & ODDS TABLES
-- ============================================

CREATE TABLE IF NOT EXISTS matches (
    id TEXT PRIMARY KEY,
    sport_id TEXT NOT NULL REFERENCES sports(id),
    league_id TEXT NOT NULL REFERENCES leagues(id),
    home_team_id TEXT NOT NULL REFERENCES teams(id),
    away_team_id TEXT NOT NULL REFERENCES teams(id),
    match_date DATETIME NOT NULL,
    kickoff_ts INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'SCHEDULED', -- SCHEDULED, LIVE, HALFTIME, FINISHED, CANCELLED, POSTPONED
    home_score INTEGER DEFAULT NULL,
    away_score INTEGER DEFAULT NULL,
    ht_home_score INTEGER DEFAULT NULL,
    ht_away_score INTEGER DEFAULT NULL,
    referee TEXT,
    venue TEXT,
    weather_json JSON DEFAULT '{}',
    metadata_json JSON DEFAULT '{}',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_matches_date ON matches(match_date);
CREATE INDEX IF NOT EXISTS idx_matches_status ON matches(status);
CREATE INDEX IF NOT EXISTS idx_matches_league_date ON matches(league_id, match_date);
CREATE INDEX IF NOT EXISTS idx_matches_teams ON matches(home_team_id, away_team_id);
CREATE INDEX IF NOT EXISTS idx_matches_kickoff ON matches(kickoff_ts);

CREATE TABLE IF NOT EXISTS odds (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    bookmaker TEXT NOT NULL,
    bookmaker_id TEXT,
    market_type TEXT NOT NULL DEFAULT '1X2', -- 1X2, OVER_UNDER_25, BTTS, HT_FT, CORNERS, CARDS, PLAYER_PROPS
    home_win REAL,
    draw REAL,
    away_win REAL,
    over_25 REAL,
    under_25 REAL,
    btts_yes REAL,
    btts_no REAL,
    ht_home_win REAL,
    ht_draw REAL,
    ht_away_win REAL,
    implied_margin REAL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    is_closing INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_odds_match ON odds(match_id);
CREATE INDEX IF NOT EXISTS idx_odds_bookmaker_time ON odds(bookmaker, timestamp);
CREATE INDEX IF NOT EXISTS idx_odds_market ON odds(market_type);

CREATE TABLE IF NOT EXISTS odds_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    bookmaker TEXT NOT NULL,
    market_type TEXT NOT NULL,
    odds_json JSON NOT NULL,
    movement_direction TEXT, -- UP, DOWN, STABLE
    movement_pct REAL,
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_odds_hist_match_time ON odds_history(match_id, timestamp);

-- ============================================
-- PREDICTION & MODEL TABLES
-- ============================================

CREATE TABLE IF NOT EXISTS model_registry (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    version TEXT NOT NULL,
    model_type TEXT NOT NULL, -- POISSON_DC, XGBOOST, ENSEMBLE, NEURAL
    parameters_json JSON NOT NULL DEFAULT '{}',
    features_json JSON NOT NULL DEFAULT '[]',
    training_date DATETIME NOT NULL,
    validation_metrics_json JSON DEFAULT '{}',
    is_active INTEGER NOT NULL DEFAULT 0,
    is_production INTEGER NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS predictions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    model_id TEXT NOT NULL REFERENCES model_registry(id),
    predicted_outcome TEXT NOT NULL, -- HOME_WIN, DRAW, AWAY_WIN, OVER_25, UNDER_25, BTTS_YES, BTTS_NO
    market_type TEXT NOT NULL DEFAULT '1X2',
    -- 10 Core Metrics
    success_prob REAL NOT NULL CHECK (success_prob >= 0 AND success_prob <= 1),
    confidence REAL NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    honesty REAL NOT NULL CHECK (honesty >= 0 AND honesty <= 1),
    reliability REAL NOT NULL CHECK (reliability >= 0 AND reliability <= 1),
    value_score REAL NOT NULL,
    risk_score REAL NOT NULL CHECK (risk_score >= 0 AND risk_score <= 1),
    quality_score REAL NOT NULL CHECK (quality_score >= 0 AND quality_score <= 1),
    consistency REAL NOT NULL CHECK (consistency >= 0 AND consistency <= 1),
    market_efficiency REAL NOT NULL CHECK (market_efficiency >= 0 AND market_efficiency <= 1),
    scenario_score REAL NOT NULL CHECK (scenario_score >= 0 AND scenario_score <= 1),
    -- Composite
    final_score REAL NOT NULL,
    -- Kelly Stake
    kelly_fraction REAL DEFAULT 0.0,
    recommended_stake REAL DEFAULT 0.0,
    -- Feature Snapshot
    features_json JSON DEFAULT '{}',
    -- Metadata
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_predictions_match ON predictions(match_id);
CREATE INDEX IF NOT EXISTS idx_predictions_model ON predictions(model_id);
CREATE INDEX IF NOT EXISTS idx_predictions_score ON predictions(final_score DESC);
CREATE INDEX IF NOT EXISTS idx_predictions_created ON predictions(created_at);

-- ============================================
-- COUPON & BETTING TABLES
-- ============================================

CREATE TABLE IF NOT EXISTS coupons (
    id TEXT PRIMARY KEY,
    coupon_type TEXT NOT NULL, -- SINGLE, ACCUMULATOR, SYSTEM, CHAIN, LUCKY15, YANKEE, CANADIAN, HEINZ, SUPER_HEINZ, GOLIATH
    total_odds REAL NOT NULL CHECK (total_odds > 1.0),
    stake REAL NOT NULL CHECK (stake > 0),
    potential_return REAL NOT NULL,
    strategy_tag TEXT, -- VALUE, HIGH_CONFIDENCE, LONGSHOT, HEDGE
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, PARTIAL_WON, WON, LOST, CANCELLED, VOIDED
    total_legs INTEGER NOT NULL CHECK (total_legs > 0),
    won_legs INTEGER DEFAULT 0,
    settled_legs INTEGER DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    settled_at DATETIME DEFAULT NULL,
    bankroll_snapshot_id INTEGER REFERENCES bankroll_snapshots(id)
);
CREATE INDEX IF NOT EXISTS idx_coupons_status ON coupons(status);
CREATE INDEX IF NOT EXISTS idx_coupons_created ON coupons(created_at);
CREATE INDEX IF NOT EXISTS idx_coupons_type ON coupons(coupon_type);

CREATE TABLE IF NOT EXISTS coupon_legs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    coupon_id TEXT NOT NULL REFERENCES coupons(id) ON DELETE CASCADE,
    match_id TEXT NOT NULL REFERENCES matches(id),
    prediction_id INTEGER NOT NULL REFERENCES predictions(id),
    leg_order INTEGER NOT NULL,
    selection TEXT NOT NULL,
    market_type TEXT NOT NULL,
    odds REAL NOT NULL CHECK (odds > 1.0),
    stake_allocated REAL NOT NULL CHECK (stake_allocated > 0),
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, WON, LOST, VOIDED
    result_value REAL DEFAULT NULL,
    settled_at DATETIME DEFAULT NULL
);
CREATE INDEX IF NOT EXISTS idx_coupon_legs_coupon ON coupon_legs(coupon_id);
CREATE INDEX IF NOT EXISTS idx_coupon_legs_match ON coupon_legs(match_id);

-- ============================================
-- BANKROLL & RISK MANAGEMENT
-- ============================================

CREATE TABLE IF NOT EXISTS bankroll (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    initial_balance REAL NOT NULL DEFAULT 1000.0,
    current_balance REAL NOT NULL DEFAULT 1000.0,
    available_balance REAL NOT NULL DEFAULT 1000.0,
    reserved_balance REAL NOT NULL DEFAULT 0.0,
    total_staked REAL NOT NULL DEFAULT 0.0,
    total_won REAL NOT NULL DEFAULT 0.0,
    total_lost REAL NOT NULL DEFAULT 0.0,
    total_profit REAL NOT NULL DEFAULT 0.0,
    roi REAL NOT NULL DEFAULT 0.0,
    max_drawdown REAL NOT NULL DEFAULT 0.0,
    peak_balance REAL NOT NULL DEFAULT 1000.0,
    -- Risk Limits
    stop_loss_limit REAL NOT NULL DEFAULT 200.0,
    take_profit_limit REAL NOT NULL DEFAULT 2000.0,
    daily_loss_limit REAL NOT NULL DEFAULT 100.0,
    max_single_stake_pct REAL NOT NULL DEFAULT 0.05,
    max_daily_stakes INTEGER NOT NULL DEFAULT 20,
    kelly_fraction REAL NOT NULL DEFAULT 0.25,
    min_value_threshold REAL NOT NULL DEFAULT 1.05,
    min_confidence_threshold REAL NOT NULL DEFAULT 0.60,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bankroll_snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    balance REAL NOT NULL,
    available REAL NOT NULL,
    reserved REAL NOT NULL,
    total_staked REAL NOT NULL,
    total_profit REAL NOT NULL,
    roi REAL NOT NULL,
    snapshot_type TEXT NOT NULL, -- DAILY, PRE_BET, POST_SETTLEMENT, MANUAL
    note TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bankroll_snapshots_date ON bankroll_snapshots(created_at);

CREATE TABLE IF NOT EXISTS bankroll_transactions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    amount REAL NOT NULL,
    txn_type TEXT NOT NULL, -- STAKE, WINNING, REFUND, DEPOSIT, WITHDRAWAL, FEE, ADJUSTMENT
    reference_type TEXT, -- COUPON, MATCH, MANUAL
    reference_id TEXT,
    balance_before REAL NOT NULL,
    balance_after REAL NOT NULL,
    note TEXT,
    metadata_json JSON DEFAULT '{}',
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_bankroll_txns_time ON bankroll_transactions(timestamp);
CREATE INDEX IF NOT EXISTS idx_bankroll_txns_ref ON bankroll_transactions(reference_type, reference_id);

-- ============================================
-- SETTLEMENT & RESULTS
-- ============================================

CREATE TABLE IF NOT EXISTS match_results (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    match_id TEXT NOT NULL UNIQUE REFERENCES matches(id) ON DELETE CASCADE,
    home_score INTEGER NOT NULL,
    away_score INTEGER NOT NULL,
    ht_home_score INTEGER,
    ht_away_score INTEGER,
    result_1x2 TEXT NOT NULL, -- HOME_WIN, DRAW, AWAY_WIN
    result_over_25 TEXT, -- OVER, UNDER
    result_btts TEXT, -- YES, NO
    result_ht_ft TEXT, -- HOME_HOME, DRAW_DRAW, etc.
    corners_home INTEGER,
    corners_away INTEGER,
    cards_home INTEGER,
    cards_away INTEGER,
    xg_home REAL,
    xg_away REAL,
    source TEXT NOT NULL, -- API_SPORTS, API_FOOTBALL, MANUAL, SCRAPED
    verified INTEGER NOT NULL DEFAULT 0,
    settled_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_match_results_verified ON match_results(verified);

CREATE TABLE IF NOT EXISTS prediction_evaluations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    prediction_id INTEGER NOT NULL REFERENCES predictions(id) ON DELETE CASCADE,
    match_result_id INTEGER NOT NULL REFERENCES match_results(id),
    actual_outcome TEXT NOT NULL,
    is_correct INTEGER NOT NULL, -- 1 or 0
    profit_loss REAL NOT NULL,
    clv REAL, -- Closing Line Value
    brier_score REAL,
    log_loss REAL,
    evaluated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_pred_eval_prediction ON prediction_evaluations(prediction_id);

-- ============================================
-- SETTINGS & CONFIGURATION
-- ============================================

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    value_type TEXT NOT NULL DEFAULT 'string', -- string, number, boolean, json, encrypted
    category TEXT NOT NULL DEFAULT 'general',
    subcategory TEXT,
    description TEXT,
    is_sensitive INTEGER NOT NULL DEFAULT 0,
    is_user_editable INTEGER NOT NULL DEFAULT 1,
    validation_regex TEXT,
    default_value TEXT,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_by TEXT DEFAULT 'SYSTEM'
);
CREATE INDEX IF NOT EXISTS idx_settings_category ON settings(category, subcategory);

-- ============================================
-- AUDIT & COMPLIANCE
-- ============================================

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    entity_type TEXT, -- MATCH, ODDS, PREDICTION, COUPON, BANKROLL, SETTING, USER
    entity_id TEXT,
    actor TEXT NOT NULL DEFAULT 'SYSTEM', -- SYSTEM, USER, API, SCHEDULER, ADMIN
    actor_ip TEXT,
    old_value_json JSON,
    new_value_json JSON,
    metadata_json JSON DEFAULT '{}',
    severity TEXT DEFAULT 'INFO', -- DEBUG, INFO, WARNING, ERROR, CRITICAL
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor);

CREATE TABLE IF NOT EXISTS rg_limits (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    daily_deposit_limit REAL DEFAULT 0,
    daily_loss_limit REAL DEFAULT 100.0,
    daily_wager_limit REAL DEFAULT 0,
    session_time_limit_minutes INTEGER DEFAULT 60,
    self_exclusion_until DATETIME DEFAULT NULL,
    cooloff_until DATETIME DEFAULT NULL,
    reality_check_interval_minutes INTEGER DEFAULT 30,
    last_reality_check DATETIME DEFAULT NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- ============================================
-- DATA SOURCE HEALTH & MONITORING
-- ============================================

CREATE TABLE IF NOT EXISTS data_sources (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL, -- API, SCRAPER, MANUAL, FEED
    base_url TEXT,
    api_key_env TEXT,
    rate_limit_per_min INTEGER DEFAULT 60,
    rate_limit_per_day INTEGER DEFAULT 1000,
    is_active INTEGER NOT NULL DEFAULT 1,
    priority INTEGER DEFAULT 10,
    last_success DATETIME,
    last_failure DATETIME,
    consecutive_failures INTEGER DEFAULT 0,
    health_score REAL DEFAULT 1.0,
    config_json JSON DEFAULT '{}',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS collection_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_id TEXT NOT NULL REFERENCES data_sources(id),
    run_type TEXT NOT NULL, -- FULL, INCREMENTAL, LIVE, ODDS_ONLY
    status TEXT NOT NULL, -- STARTED, SUCCESS, PARTIAL, FAILED
    matches_fetched INTEGER DEFAULT 0,
    odds_fetched INTEGER DEFAULT 0,
    errors_json JSON DEFAULT '[]',
    duration_ms INTEGER,
    started_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_collection_runs_source_time ON collection_runs(source_id, started_at);

-- ============================================
-- FEATURE STORE & ML
-- ============================================

CREATE TABLE IF NOT EXISTS feature_store (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type TEXT NOT NULL, -- TEAM, MATCH, LEAGUE, PLAYER
    entity_id TEXT NOT NULL,
    feature_set TEXT NOT NULL, -- FORM, ELO, XG, H2H, INJURY, WEATHER, MARKET
    features_json JSON NOT NULL,
    valid_from DATETIME NOT NULL,
    valid_until DATETIME,
    version INTEGER NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_feature_store_unique ON feature_store(entity_type, entity_id, feature_set, valid_from);
CREATE INDEX IF NOT EXISTS idx_feature_store_entity ON feature_store(entity_type, entity_id);

CREATE TABLE IF NOT EXISTS drift_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_id TEXT NOT NULL REFERENCES model_registry(id),
    metric_name TEXT NOT NULL,
    reference_value REAL NOT NULL,
    current_value REAL NOT NULL,
    drift_score REAL NOT NULL,
    threshold REAL NOT NULL,
    is_alert INTEGER NOT NULL DEFAULT 0,
    details_json JSON DEFAULT '{}',
    detected_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_drift_log_model_time ON drift_log(model_id, detected_at);

-- ============================================
-- NOTIFICATIONS & ALERTS
-- ============================================

CREATE TABLE IF NOT EXISTS notification_rules (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    trigger_type TEXT NOT NULL, -- VALUE_BET, HIGH_CONFIDENCE, COUPON_WON, COUPON_LOST, STOP_LOSS, DAILY_SUMMARY
    conditions_json JSON NOT NULL DEFAULT '{}',
    channels_json JSON NOT NULL DEFAULT '["push"]', -- push, telegram, email, webhook
    template TEXT,
    is_active INTEGER NOT NULL DEFAULT 1,
    cooldown_minutes INTEGER DEFAULT 60,
    last_triggered DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS notifications (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    rule_id TEXT REFERENCES notification_rules(id),
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    payload_json JSON DEFAULT '{}',
    channel TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING', -- PENDING, SENT, FAILED, DELIVERED
    sent_at DATETIME,
    delivered_at DATETIME,
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_notifications_status ON notifications(status);
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at);

-- ============================================
-- SEED DATA
-- ============================================

-- Sports
INSERT OR IGNORE INTO sports (id, name, display_name) VALUES 
    ('football', 'football', 'Futbol'),
    ('basketball', 'basketball', 'Basketbol'),
    ('tennis', 'tennis', 'Tenis'),
    ('ice_hockey', 'ice_hockey', 'Buz Hokeyi'),
    ('volleyball', 'volleyball', 'Voleybol');

-- Default Settings (Zero Hardcoding)
INSERT OR IGNORE INTO settings (key, value, value_type, category, subcategory, description, default_value) VALUES
    -- Data Collection
    ('collection.enabled', '1', 'boolean', 'collection', 'general', 'Veri toplama aktif mi', '1'),
    ('collection.interval_minutes', '15', 'number', 'collection', 'scheduler', 'Toplama aralığı (dakika)', '15'),
    ('collection.live_interval_seconds', '60', 'number', 'collection', 'scheduler', 'Canlı maç aralığı (saniye)', '60'),
    ('collection.lookahead_days', '7', 'number', 'collection', 'scope', 'İleri gün sayısı', '7'),
    ('collection.max_matches_per_run', '200', 'number', 'collection', 'limits', 'Run başına max maç', '200'),
    -- Data Sources
    ('source.football_data_org.enabled', '1', 'boolean', 'sources', 'football_data_org', 'Football-Data.org aktif', '1'),
    ('source.the_sports_db.enabled', '1', 'boolean', 'sources', 'the_sports_db', 'TheSportsDB aktif', '1'),
    ('source.open_liga_db.enabled', '1', 'boolean', 'sources', 'open_liga_db', 'OpenLigaDB aktif', '1'),
    ('source.api_football.enabled', '0', 'boolean', 'sources', 'api_football', 'API-Football aktif (key gerekli)', '0'),
    ('source.sportmonks.enabled', '0', 'boolean', 'sources', 'sportmonks', 'SportMonks aktif (key gerekli)', '0'),
    -- Odds
    ('odds.min_bookmakers', '3', 'number', 'odds', 'quality', 'Minimum büro sayısı', '3'),
    ('odds.max_age_hours', '24', 'number', 'odds', 'freshness', 'Oran maksimum yaşı (saat)', '24'),
    ('odds.markets', '["1X2","OVER_UNDER_25","BTTS"]', 'json', 'odds', 'markets', 'Takip edilecek piyasalar', '["1X2","OVER_UNDER_25","BTTS"]'),
    -- Prediction
    ('prediction.min_confidence', '0.60', 'number', 'prediction', 'thresholds', 'Minimum güven eşiği', '0.60'),
    ('prediction.min_value_score', '1.05', 'number', 'prediction', 'thresholds', 'Minimum değer skoru', '1.05'),
    ('prediction.min_honesty', '0.80', 'number', 'prediction', 'thresholds', 'Minimum dürüstlük', '0.80'),
    ('prediction.kelly_fraction', '0.25', 'number', 'prediction', 'staking', 'Kelly kesir', '0.25'),
    ('prediction.max_kelly_stake_pct', '0.05', 'number', 'prediction', 'staking', 'Max Kelly stake %', '0.05'),
    ('prediction.ensemble_weights', '{"poisson_dc":0.4,"xgboost":0.35,"market":0.25}', 'json', 'prediction', 'ensemble', 'Ensemble ağırlıkları', '{"poisson_dc":0.4,"xgboost":0.35,"market":0.25}'),
    -- Coupon
    ('coupon.daily_target_count', '100', 'number', 'coupon', 'volume', 'Günlük hedef kupon', '100'),
    ('coupon.max_legs_accumulator', '6', 'number', 'coupon', 'limits', 'Akumülatör max bacak', '6'),
    ('coupon.max_legs_system', '8', 'number', 'coupon', 'limits', 'Sistem max bacak', '8'),
    ('coupon.min_total_odds', '1.50', 'number', 'coupon', 'thresholds', 'Minimum toplam oran', '1.50'),
    ('coupon.max_total_odds', '500.0', 'number', 'coupon', 'thresholds', 'Maksimum toplam oran', '500.0'),
    ('coupon.types_enabled', '["SINGLE","ACCUMULATOR","SYSTEM_2_3","SYSTEM_3_4","LUCKY15"]', 'json', 'coupon', 'types', 'Aktif kupon türleri', '["SINGLE","ACCUMULATOR","SYSTEM_2_3","SYSTEM_3_4","LUCKY15"]'),
    -- Bankroll
    ('bankroll.initial_balance', '1000.0', 'number', 'bankroll', 'setup', 'Başlangıç bakiyesi', '1000.0'),
    ('bankroll.stop_loss_limit', '200.0', 'number', 'bankroll', 'risk', 'Stop-loss limiti', '200.0'),
    ('bankroll.take_profit_limit', '2000.0', 'number', 'bankroll', 'risk', 'Take-profit limiti', '2000.0'),
    ('bankroll.daily_loss_limit', '100.0', 'number', 'bankroll', 'risk', 'Günlük kayıp limiti', '100.0'),
    ('bankroll.max_single_stake_pct', '0.05', 'number', 'bankroll', 'risk', 'Tek kupon max %', '0.05'),
    ('bankroll.kelly_fraction', '0.25', 'number', 'bankroll', 'staking', 'Kelly kesir', '0.25'),
    -- Risk
    ('risk.max_correlation', '0.7', 'number', 'risk', 'portfolio', 'Max korelasyon', '0.7'),
    ('risk.max_league_exposure_pct', '0.3', 'number', 'risk', 'portfolio', 'Liga bazlı max maruziyet', '0.3'),
    ('risk.cvar_confidence', '0.95', 'number', 'risk', 'metrics', 'CVaR güven seviyesi', '0.95'),
    -- Notifications
    ('notifications.push.enabled', '1', 'boolean', 'notifications', 'push', 'Push bildirimler', '1'),
    ('notifications.telegram.enabled', '0', 'boolean', 'notifications', 'telegram', 'Telegram bot', '0'),
    ('notifications.telegram.bot_token', '', 'encrypted', 'notifications', 'telegram', 'Bot token', ''),
    ('notifications.telegram.chat_id', '', 'string', 'notifications', 'telegram', 'Chat ID', ''),
    ('notifications.value_bet_threshold', '1.15', 'number', 'notifications', 'alerts', 'Değer bahis eşiği', '1.15'),
    -- Responsible Gambling
    ('rg.daily_loss_limit', '100.0', 'number', 'rg', 'limits', 'Günlük kayıp limiti', '100.0'),
    ('rg.session_limit_minutes', '60', 'number', 'rg', 'limits', 'Oturum süresi limiti', '60'),
    ('rg.reality_check_minutes', '30', 'number', 'rg', 'reality_check', 'Gerçeklik kontrolü aralığı', '30'),
    -- UI
    ('ui.theme', 'dark', 'string', 'ui', 'appearance', 'Tema', 'dark'),
    ('ui.language', 'tr', 'string', 'ui', 'appearance', 'Dil', 'tr'),
    ('ui.odds_format', 'decimal', 'string', 'ui', 'appearance', 'Oran formatı', 'decimal'),
    -- Compliance
    ('compliance.jurisdiction', 'TR', 'string', 'compliance', 'legal', 'Yargı alanı', 'TR'),
    ('compliance.age_verification', '1', 'boolean', 'compliance', 'legal', 'Yaş doğrulama', '1'),
    -- System
    ('system.maintenance_mode', '0', 'boolean', 'system', 'ops', 'Bakım modu', '0'),
    ('system.log_level', 'INFO', 'string', 'system', 'ops', 'Log seviyesi', 'INFO'),
    ('system.retention_days_hot', '730', 'number', 'system', 'retention', 'Sıcak veri tutma (gün)', '730'),
    ('system.retention_days_archive', '2555', 'number', 'system', 'retention', 'Arşiv veri tutma (gün)', '2555');

-- Default Bankroll
INSERT OR IGNORE INTO bankroll (id, initial_balance, current_balance, available_balance, peak_balance) 
VALUES (1, 1000.0, 1000.0, 1000.0, 1000.0);

-- Default RG Limits
INSERT OR IGNORE INTO rg_limits (id, daily_loss_limit, session_time_limit_minutes, reality_check_interval_minutes) 
VALUES (1, 100.0, 60, 30);

-- Default Model Registry Entry
INSERT OR IGNORE INTO model_registry (id, name, version, model_type, parameters_json, features_json, training_date, validation_metrics_json, is_active, is_production)
VALUES ('ensemble_v1', 'Ensemble v1.0', '1.0.0', 'ENSEMBLE', 
    '{"poisson_dc":{},"xgboost":{},"market_implied":{}}', 
    '["form_elo","h2h","xg","injuries","market_movement","rest_days","weather"]',
    datetime('now'), '{"brier":0.18,"logloss":0.52,"roi":0.08}', 1, 1);