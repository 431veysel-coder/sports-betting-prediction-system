"""
Production-Grade Coupon Engine
Implements: Value-based selection, Kelly staking, Risk management, 
Multiple coupon types (Single, Accumulator, System, Lucky15, etc.)
"""

import uuid
import math
import json
from datetime import datetime
from typing import List, Dict, Any
from database.db import get_db
from engines.bankroll_engine import get_bankroll_status, calculate_kelly_stake

# ============================================
# COUPON TYPE DEFINITIONS
# ============================================

COUPON_TYPES = {
    'SINGLE': {'min_legs': 1, 'max_legs': 1, 'description': 'Tek bahis'},
    'DOUBLE': {'min_legs': 2, 'max_legs': 2, 'description': 'İkili akumülatör'},
    'TREBLE': {'min_legs': 3, 'max_legs': 3, 'description': 'Üçlü akumülatör'},
    'ACCUMULATOR_4': {'min_legs': 4, 'max_legs': 4, 'description': '4\'lü akumülatör'},
    'ACCUMULATOR_5': {'min_legs': 5, 'max_legs': 5, 'description': '5\'li akumülatör'},
    'ACCUMULATOR_6': {'min_legs': 6, 'max_legs': 6, 'description': '6\'lı akumülatör'},
    'SYSTEM_2_3': {'min_legs': 3, 'max_legs': 3, 'system': '2/3', 'description': 'Sistem 2/3 (3 çift)'},
    'SYSTEM_2_4': {'min_legs': 4, 'max_legs': 4, 'system': '2/4', 'description': 'Sistem 2/4 (6 çift)'},
    'SYSTEM_3_4': {'min_legs': 4, 'max_legs': 4, 'system': '3/4', 'description': 'Sistem 3/4 (4 üçlü)'},
    'SYSTEM_2_5': {'min_legs': 5, 'max_legs': 5, 'system': '2/5', 'description': 'Sistem 2/5 (10 çift)'},
    'SYSTEM_3_5': {'min_legs': 5, 'max_legs': 5, 'system': '3/5', 'description': 'Sistem 3/5 (10 üçlü)'},
    'SYSTEM_4_5': {'min_legs': 5, 'max_legs': 5, 'system': '4/5', 'description': 'Sistem 4/5 (5 dörtlü)'},
    'SYSTEM_2_6': {'min_legs': 6, 'max_legs': 6, 'system': '2/6', 'description': 'Sistem 2/6 (15 çift)'},
    'SYSTEM_3_6': {'min_legs': 6, 'max_legs': 6, 'system': '3/6', 'description': 'Sistem 3/6 (20 üçlü)'},
    'SYSTEM_4_6': {'min_legs': 6, 'max_legs': 6, 'system': '4/6', 'description': 'Sistem 4/6 (15 dörtlü)'},
    'SYSTEM_5_6': {'min_legs': 6, 'max_legs': 6, 'system': '5/6', 'description': 'Sistem 5/6 (6 beşli)'},
    'LUCKY15': {'min_legs': 4, 'max_legs': 4, 'system': 'lucky15', 'description': 'Lucky 15 (4 single, 6 double, 4 treble, 1 fourfold = 15 bahis)'},
    'LUCKY31': {'min_legs': 5, 'max_legs': 5, 'system': 'lucky31', 'description': 'Lucky 31 (5 single, 10 double, 10 treble, 5 fourfold, 1 fivefold = 31 bahis)'},
    'LUCKY63': {'min_legs': 6, 'max_legs': 6, 'system': 'lucky63', 'description': 'Lucky 63 (6 single, 15 double, 20 treble, 15 fourfold, 6 fivefold, 1 sixfold = 63 bahis)'},
    'YANKEE': {'min_legs': 4, 'max_legs': 4, 'system': 'yankee', 'description': 'Yankee (6 double, 4 treble, 1 fourfold = 11 bahis)'},
    'CANADIAN': {'min_legs': 5, 'max_legs': 5, 'system': 'canadian', 'description': 'Canadian/Super Yankee (10 double, 10 treble, 5 fourfold, 1 fivefold = 26 bahis)'},
    'HEINZ': {'min_legs': 6, 'max_legs': 6, 'system': 'heinz', 'description': 'Heinz (15 double, 20 treble, 15 fourfold, 6 fivefold, 1 sixfold = 57 bahis)'},
    'SUPER_HEINZ': {'min_legs': 7, 'max_legs': 7, 'system': 'super_heinz', 'description': 'Super Heinz (21 double, 35 treble, 35 fourfold, 21 fivefold, 7 sixfold, 1 sevenfold = 120 bahis)'},
    'GOLIATH': {'min_legs': 8, 'max_legs': 8, 'system': 'goliath', 'description': 'Goliath (28 double, 56 treble, 70 fourfold, 56 fivefold, 28 sixfold, 8 sevenfold, 1 eightfold = 247 bahis)'}
}

# Strategy configurations
STRATEGY_CONFIGS = {
    'VALUE': {
        'min_value_score': 1.05,
        'min_confidence': 0.55,
        'max_odds': 15.0,
        'prefer_markets': ['1X2', 'OVER_UNDER_25'],
        'weight_value': 0.7,
        'weight_confidence': 0.3
    },
    'HIGH_CONFIDENCE': {
        'min_value_score': 1.01,
        'min_confidence': 0.70,
        'max_odds': 5.0,
        'prefer_markets': ['1X2'],
        'weight_value': 0.3,
        'weight_confidence': 0.7
    },
    'LONGSHOT': {
        'min_value_score': 1.10,
        'min_confidence': 0.45,
        'max_odds': 100.0,
        'prefer_markets': ['1X2', 'HT_FT', 'CORRECT_SCORE'],
        'weight_value': 0.5,
        'weight_confidence': 0.5
    },
    'BALANCED': {
        'min_value_score': 1.02,
        'min_confidence': 0.55,
        'max_odds': 20.0,
        'prefer_markets': ['1X2', 'OVER_UNDER_25', 'BTTS'],
        'weight_value': 0.5,
        'weight_confidence': 0.5
    }
}

# ============================================
# CANDIDATE SELECTION
# ============================================

class CouponCandidate:
    def __init__(self, prediction: Dict, match: Dict, odds: Dict):
        self.prediction = prediction
        self.match = match
        self.odds = odds
        self.selection = prediction['predicted_outcome']
        self.market_type = prediction['market_type']
        self.probability = prediction['success_prob']
        self.odds_value = self._get_odds_for_selection()
        self.value_score = prediction['value_score']
        self.confidence = prediction['confidence']
        self.final_score = prediction['final_score']
        self.kelly_fraction = prediction['kelly_fraction']
        self.risk_score = prediction['risk_score']
        self.match_id = match['id']
        self.prediction_id = prediction.get('id')
    
    def _get_odds_for_selection(self) -> float:
        sel = self.selection
        if sel == 'HOME_WIN':
            return self.odds.get('home_win', 0)
        elif sel == 'AWAY_WIN':
            return self.odds.get('away_win', 0)
        elif sel == 'DRAW':
            return self.odds.get('draw', 0)
        elif sel == 'OVER_25':
            return self.odds.get('over_25', 0)
        elif sel == 'UNDER_25':
            return self.odds.get('under_25', 0)
        elif sel == 'BTTS_YES':
            return self.odds.get('btts_yes', 0)
        elif sel == 'BTTS_NO':
            return self.odds.get('btts_no', 0)
        return 0
    
    def passes_strategy(self, strategy: str) -> bool:
        config = STRATEGY_CONFIGS.get(strategy, STRATEGY_CONFIGS['BALANCED'])
        if self.value_score < config['min_value_score']:
            return False
        if self.confidence < config['min_confidence']:
            return False
        if self.odds_value > config['max_odds']:
            return False
        if self.market_type not in config['prefer_markets'] and config['prefer_markets']:
            return False
        return True
    
    def strategy_score(self, strategy: str) -> float:
        config = STRATEGY_CONFIGS.get(strategy, STRATEGY_CONFIGS['BALANCED'])
        return (config['weight_value'] * min(2.0, self.value_score) + 
                config['weight_confidence'] * self.confidence)

# ============================================
# MAIN COUPON ENGINE
# ============================================

class CouponEngine:
    def __init__(self):
        self.db = get_db()
        self.settings = self._load_settings()
    
    def _load_settings(self) -> Dict:
        cursor = self.db.cursor()
        cursor.execute("SELECT key, value FROM settings WHERE category = 'coupon'")
        return {row['key']: row['value'] for row in cursor.fetchall()}
    
    def get_daily_candidates(self, strategy: str = 'BALANCED') -> List[CouponCandidate]:
        """Fetch and filter prediction candidates for coupon generation"""
        cursor = self.db.cursor()
        
        cursor.execute("""
            SELECT p.*, m.home_team_id, m.away_team_id, m.league_id, m.match_date,
                   t1.name as home_name, t2.name as away_name, l.name as league_name
            FROM predictions p
            JOIN matches m ON p.match_id = m.id
            JOIN teams t1 ON m.home_team_id = t1.id
            JOIN teams t2 ON m.away_team_id = t2.id
            JOIN leagues l ON m.league_id = l.id
            WHERE m.status = 'SCHEDULED'
            AND m.match_date >= datetime('now')
            AND m.match_date <= datetime('now', '+3 days')
            ORDER BY p.final_score DESC
        """)
        predictions = cursor.fetchall()
        
        candidates = []
        for pred in predictions:
            # Get odds
            cursor.execute("""
                SELECT home_win, draw, away_win, over_25, under_25, btts_yes, btts_no
                FROM odds WHERE match_id = ? ORDER BY timestamp DESC LIMIT 1
            """, (pred['match_id'],))
            odds_row = cursor.fetchone()
            
            if not odds_row:
                continue
            
            match_data = {
                'id': pred['match_id'],
                'home_team': pred['home_name'],
                'away_team': pred['away_name'],
                'league': pred['league_name'],
                'match_date': pred['match_date']
            }
            
            candidate = CouponCandidate(dict(pred), match_data, dict(odds_row))
            
            if candidate.passes_strategy(strategy):
                candidates.append(candidate)
        
        # Sort by strategy score
        candidates.sort(key=lambda c: c.strategy_score(strategy), reverse=True)
        return candidates
    
    def generate_single_coupons(self, candidates: List[CouponCandidate], 
                                 stake_per_coupon: float, count: int) -> List[Dict]:
        """Generate single bet coupons (highest value) - allows candidate reuse"""
        coupons = []
        if not candidates:
            return coupons
        
        for i in range(count):
            # Cycle through candidates to allow reuse
            cand = candidates[i % len(candidates)]
            coupon_id = f"SNG_{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:6]}"
            coupons.append({
                'id': coupon_id,
                'coupon_type': 'SINGLE',
                'total_odds': round(cand.odds_value, 2),
                'stake': stake_per_coupon,
                'potential_return': round(stake_per_coupon * cand.odds_value, 2),
                'legs': [{
                    'match_id': cand.match_id,
                    'prediction_id': cand.prediction_id,
                    'selection': cand.selection,
                    'market_type': cand.market_type,
                    'odds': cand.odds_value,
                    'stake_allocated': stake_per_coupon
                }],
                'strategy_tag': 'VALUE' if cand.value_score > 1.2 else 'BALANCED'
            })
        return coupons
    
    def generate_accumulator_coupons(self, candidates: List[CouponCandidate],
                                      stake_per_coupon: float, count: int,
                                      legs_range: tuple = (2, 4)) -> List[Dict]:
        """Generate accumulator coupons with correlated risk management - allows candidate reuse"""
        coupons = []
        min_legs, max_legs = legs_range
        
        if len(candidates) < min_legs:
            return coupons
        
        for i in range(count):
            # Select legs with diversification (different leagues) - allow reuse
            selected = self._select_diversified_legs(candidates, min_legs, max_legs)
            if len(selected) < min_legs:
                continue
            
            total_odds = 1.0
            legs = []
            for cand in selected:
                total_odds *= cand.odds_value
                legs.append({
                    'match_id': cand.match_id,
                    'prediction_id': cand.prediction_id,
                    'selection': cand.selection,
                    'market_type': cand.market_type,
                    'odds': cand.odds_value,
                    'stake_allocated': stake_per_coupon
                })
            
            # Risk check: max total odds
            max_odds = float(self.settings.get('coupon.max_total_odds', 500))
            if total_odds > max_odds:
                continue
            
            coupon_id = f"ACC_{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:6]}"
            coupons.append({
                'id': coupon_id,
                'coupon_type': f'ACCUMULATOR_{len(selected)}',
                'total_odds': round(total_odds, 2),
                'stake': stake_per_coupon,
                'potential_return': round(stake_per_coupon * total_odds, 2),
                'legs': legs,
                'strategy_tag': 'BALANCED'
            })
        return coupons
    
    def generate_system_coupons(self, candidates: List[CouponCandidate],
                                 stake_per_coupon: float, count: int,
                                 system_type: str = 'SYSTEM_2_3') -> List[Dict]:
        """Generate system coupons (e.g., 2/3, 3/4) - allows candidate reuse"""
        coupons = []
        sys_config = COUPON_TYPES.get(system_type)
        if not sys_config:
            return coupons
        
        required_legs = sys_config['min_legs']
        
        if len(candidates) < required_legs:
            return coupons
        
        for i in range(count):
            # Cycle through candidates for system bets
            start_idx = (i * required_legs) % len(candidates)
            selected = []
            for j in range(required_legs):
                idx = (start_idx + j) % len(candidates)
                selected.append(candidates[idx])
            
            total_odds = 1.0
            legs = []
            combinations = self._get_system_combinations(system_type, required_legs)
            unit_stake = stake_per_coupon / combinations
            
            for cand in selected:
                total_odds *= cand.odds_value
                legs.append({
                    'match_id': cand.match_id,
                    'prediction_id': cand.prediction_id,
                    'selection': cand.selection,
                    'market_type': cand.market_type,
                    'odds': cand.odds_value,
                    'stake_allocated': unit_stake
                })
            
            coupon_id = f"SYS_{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:6]}"
            coupons.append({
                'id': coupon_id,
                'coupon_type': system_type,
                'total_odds': round(total_odds, 2),
                'stake': stake_per_coupon,
                'potential_return': round(stake_per_coupon * total_odds, 2),
                'legs': legs,
                'strategy_tag': 'SYSTEM'
            })
        return coupons
    
    def _get_system_combinations(self, system_type: str, n: int) -> int:
        """Calculate number of combinations in a system bet"""
        st_lower = system_type.lower()
        if 'lucky' in st_lower:
            # Lucky 15: 4 singles + 6 doubles + 4 trebles + 1 fourfold = 15
            k = int(st_lower.replace('lucky', ''))
            if k == 15: return 15
            if k == 31: return 31
            if k == 63: return 63
        if system_type == 'yankee': return 11
        if system_type == 'canadian': return 26
        if system_type == 'heinz': return 57
        if system_type == 'super_heinz': return 120
        if system_type == 'goliath': return 247
        if system_type.startswith('SYSTEM_'):
            parts = system_type.split('_')
            if len(parts) == 3:
                k, n = int(parts[1]), int(parts[2])
                from math import comb
                return comb(n, k)
        return 1
    
    def _select_diversified_legs(self, candidates: List[CouponCandidate], 
                                  min_legs: int, max_legs: int) -> List[CouponCandidate]:
        """Select legs ensuring league/market diversification"""
        selected = []
        used_leagues = set()
        used_markets = set()
        
        for cand in candidates:
            if len(selected) >= max_legs:
                break
            
            league = cand.match.get('league', '')
            market = cand.market_type
            
            # Diversification logic
            league_penalty = 0.3 if league in used_leagues else 0
            market_penalty = 0.2 if market in used_markets else 0
            
            effective_score = cand.final_score / 100 * (1 - league_penalty - market_penalty)
            
            if effective_score > 0.5 or len(selected) < min_legs:
                selected.append(cand)
                used_leagues.add(league)
                used_markets.add(market)
        
        return selected[:max_legs]
    
    def generate_lucky_coupons(self, candidates: List[CouponCandidate],
                                stake_per_coupon: float, count: int,
                                lucky_type: str = 'LUCKY15') -> List[Dict]:
        """Generate Lucky 15/31/63 coupons - allows candidate reuse"""
        coupons = []
        sys_config = COUPON_TYPES.get(lucky_type)
        if not sys_config:
            return coupons
        
        required_legs = sys_config['min_legs']
        combinations = self._get_system_combinations(lucky_type, required_legs)
        unit_stake = stake_per_coupon / combinations
        
        if len(candidates) < required_legs:
            return coupons
        
        for i in range(count):
            # Cycle through candidates
            start_idx = (i * required_legs) % len(candidates)
            selected = []
            for j in range(required_legs):
                idx = (start_idx + j) % len(candidates)
                selected.append(candidates[idx])
            
            legs = []
            for cand in selected:
                legs.append({
                    'match_id': cand.match_id,
                    'prediction_id': cand.prediction_id,
                    'selection': cand.selection,
                    'market_type': cand.market_type,
                    'odds': cand.odds_value,
                    'stake_allocated': unit_stake
                })
            
            # Calculate potential return for all-win scenario
            total_odds = 1.0
            for cand in selected:
                total_odds *= cand.odds_value
            
            coupon_id = f"LKY_{datetime.now().strftime('%Y%m%d')}_{uuid.uuid4().hex[:6]}"
            coupons.append({
                'id': coupon_id,
                'coupon_type': lucky_type,
                'total_odds': round(total_odds, 2),
                'stake': stake_per_coupon,
                'potential_return': round(stake_per_coupon * total_odds, 2),
                'legs': legs,
                'strategy_tag': 'LUCKY'
            })
        return coupons

# ============================================
# DAILY GENERATION ORCHESTRATOR
# ============================================

def generate_daily_coupons(stake_amount: float = 10.0) -> Dict:
    """
    Main entry point - generates coupons for the day
    Distributes across types based on configuration
    """
    engine = CouponEngine()
    
    # Load settings
    daily_target = int(engine.settings.get('coupon.daily_target_count', '100'))
    enabled_types_json = engine.settings.get('coupon.types_enabled', '["SINGLE","ACCUMULATOR_2","ACCUMULATOR_3","SYSTEM_2_3"]')
    import json
    enabled_types = json.loads(enabled_types_json)
    
    # Get candidates for different strategies
    value_candidates = engine.get_daily_candidates('VALUE')
    balanced_candidates = engine.get_daily_candidates('BALANCED')
    confidence_candidates = engine.get_daily_candidates('HIGH_CONFIDENCE')
    
    all_coupons = []
    
    # Distribution logic based on enabled types
    type_counts = _calculate_type_distribution(daily_target, enabled_types)
    
    for c_type, count in type_counts.items():
        if count <= 0:
            continue
        
        if c_type == 'SINGLE':
            coupons = engine.generate_single_coupons(value_candidates, stake_amount, count)
        elif c_type.startswith('ACCUMULATOR'):
            legs = int(c_type.split('_')[1]) if '_' in c_type else 3
            coupons = engine.generate_accumulator_coupons(balanced_candidates, stake_amount, count, (legs, legs))
        elif c_type.startswith('SYSTEM'):
            coupons = engine.generate_system_coupons(balanced_candidates, stake_amount, count, c_type)
        elif c_type.startswith('LUCKY'):
            coupons = engine.generate_lucky_coupons(confidence_candidates, stake_amount, count, c_type)
        else:
            coupons = []
        
        # Save to database
        for coupon in coupons:
            _save_coupon(coupon)
            all_coupons.append(coupon)
    
    # Calculate by_type correctly
    by_type = {}
    for coupon in all_coupons:
        ctype = coupon['coupon_type']
        by_type[ctype] = by_type.get(ctype, 0) + 1
    
    return {
        'status': 'success',
        'coupons_generated': len(all_coupons),
        'by_type': by_type,
        'total_stake': round(len(all_coupons) * stake_amount, 2)
    }

def _calculate_type_distribution(total: int, enabled_types: List[str]) -> Dict[str, int]:
    """Calculate how many coupons per type"""
    if not enabled_types:
        return {}
    
    # Priority weights
    weights = {
        'SINGLE': 0.25,
        'DOUBLE': 0.10,
        'TREBLE': 0.10,
        'ACCUMULATOR_4': 0.08,
        'ACCUMULATOR_5': 0.07,
        'ACCUMULATOR_6': 0.05,
        'SYSTEM_2_3': 0.08,
        'SYSTEM_2_4': 0.05,
        'SYSTEM_3_4': 0.05,
        'SYSTEM_2_5': 0.03,
        'SYSTEM_3_5': 0.03,
        'SYSTEM_4_5': 0.02,
        'LUCKY15': 0.05,
        'LUCKY31': 0.02,
        'YANKEE': 0.03,
        'CANADIAN': 0.02,
    }
    
    # Filter to enabled
    enabled_weights = {k: v for k, v in weights.items() if k in enabled_types}
    total_weight = sum(enabled_weights.values())
    
    if total_weight == 0:
        return {t: total // len(enabled_types) for t in enabled_types}
    
    distribution = {}
    remaining = total
    for c_type, weight in enabled_weights.items():
        count = int(total * weight / total_weight)
        distribution[c_type] = count
        remaining -= count
    
    # Distribute remainder
    for c_type in enabled_weights:
        if remaining <= 0:
            break
        distribution[c_type] += 1
        remaining -= 1
    
    return distribution

def _save_coupon(coupon: Dict):
    """Save coupon and legs to database"""
    conn = get_db()
    cursor = conn.cursor()
    
    cursor.execute("""
        INSERT INTO coupons 
        (id, coupon_type, total_odds, stake, potential_return, status, total_legs, strategy_tag)
        VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
    """, (coupon['id'], coupon['coupon_type'], coupon['total_odds'],
          coupon['stake'], coupon['potential_return'], len(coupon['legs']),
          coupon.get('strategy_tag', 'BALANCED')))
    
    for i, leg in enumerate(coupon['legs']):
        cursor.execute("""
            INSERT INTO coupon_legs 
            (coupon_id, match_id, prediction_id, leg_order, selection, market_type, odds, stake_allocated)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (coupon['id'], leg['match_id'], leg['prediction_id'], i + 1,
              leg['selection'], leg['market_type'], leg['odds'], leg['stake_allocated']))
    
    conn.commit()
    conn.close()

if __name__ == "__main__":
    result = generate_daily_coupons(10.0)
    print(json.dumps(result, indent=2))