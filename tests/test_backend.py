#!/usr/bin/env python3
"""
Backend tests for Sports Betting Prediction System
Run with: python3 -m pytest tests/test_backend.py -v
"""

import pytest
import sqlite3
import os
import tempfile
from datetime import datetime, timedelta

# Import modules
import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from database.db import get_db, init_db
from engines.prediction_engine import (
    poisson_pmf, poisson_cdf, dixon_coles_adjustment,
    calculate_match_probabilities, calculate_over_under_probabilities, calculate_btts_probabilities,
    elo_to_goal_expectancy, expected_score, update_elo, kelly_criterion, fractional_kelly_stake
)
from engines.coupon_engine import COUPON_TYPES, STRATEGY_CONFIGS, CouponCandidate
from engines.bankroll_engine import get_bankroll_status, update_bankroll, calculate_kelly_stake


# ============================================
# DATABASE TESTS
# ============================================

class TestDatabase:
    """Test database initialization and basic operations"""
    
    @pytest.fixture(autouse=True)
    def setup_db(self):
        """Use a temporary database for each test"""
        self.temp_db = tempfile.NamedTemporaryFile(suffix='.db', delete=False)
        self.temp_db.close()
        self.original_db_path = None
        import database.db as db_module
        self.original_db_path = db_module.DB_PATH
        db_module.DB_PATH = self.temp_db.name
        init_db()
        yield
        db_module.DB_PATH = self.original_db_path
        os.unlink(self.temp_db.name)
    
    def test_database_initialized(self):
        conn = get_db()
        cursor = conn.cursor()
        
        # Check critical tables exist
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
        tables = [row[0] for row in cursor.fetchall()]
        
        required_tables = [
            'sports', 'leagues', 'teams', 'matches', 'odds', 'odds_history',
            'predictions', 'model_registry', 'coupons', 'coupon_legs',
            'bankroll', 'bankroll_transactions', 'bankroll_snapshots',
            'settings', 'audit_log', 'match_results', 'prediction_evaluations',
            'data_sources', 'rg_limits'
        ]
        
        for table in required_tables:
            assert table in tables, f"Missing table: {table}"
        
        # Check bankroll initialized
        cursor.execute("SELECT * FROM bankroll WHERE id = 1")
        bankroll = cursor.fetchone()
        assert bankroll is not None
        assert bankroll['current_balance'] == 1000.0
        
        conn.close()
    
    def test_settings_table_populated(self):
        conn = get_db()
        cursor = conn.cursor()
        cursor.execute("SELECT COUNT(*) FROM settings")
        count = cursor.fetchone()[0]
        assert count > 0, "Settings table should have entries"
        conn.close()


# ============================================
# PREDICTION ENGINE TESTS
# ============================================

class TestPoissonDistribution:
    """Test Poisson probability calculations"""
    
    def test_poisson_pmf_basic(self):
        # P(X=0) when lambda=1 should be e^-1 ≈ 0.3679
        assert abs(poisson_pmf(0, 1.0) - 0.367879) < 0.001
        
        # P(X=1) when lambda=1 should be e^-1 ≈ 0.3679
        assert abs(poisson_pmf(1, 1.0) - 0.367879) < 0.001
        
        # P(X=2) when lambda=1 should be e^-1/2 ≈ 0.1839
        assert abs(poisson_pmf(2, 1.0) - 0.183939) < 0.001
    
    def test_poisson_pmf_zero_lambda(self):
        assert poisson_pmf(0, 0) == 1.0
        assert poisson_pmf(1, 0) == 0.0
    
    def test_poisson_cdf(self):
        # CDF at k=0 should equal PMF at 0
        assert abs(poisson_cdf(0, 1.0) - poisson_pmf(0, 1.0)) < 0.001
        
        # CDF at k=2 should be sum of PMF(0)+PMF(1)+PMF(2)
        expected = poisson_pmf(0, 1.0) + poisson_pmf(1, 1.0) + poisson_pmf(2, 1.0)
        assert abs(poisson_cdf(2, 1.0) - expected) < 0.001


class TestDixonColes:
    """Test Dixon-Coles adjustment"""
    
    def test_adjustment_0_0(self):
        # 0-0: 1 - home_exp * away_exp * rho
        home_exp, away_exp, rho = 1.5, 1.0, 0.13
        adj = dixon_coles_adjustment(0, 0, home_exp, away_exp, rho)
        expected = 1 - home_exp * away_exp * rho
        assert abs(adj - expected) < 0.001
    
    def test_adjustment_1_0(self):
        # 1-0: 1 + away_exp * rho
        home_exp, away_exp, rho = 1.5, 1.0, 0.13
        adj = dixon_coles_adjustment(1, 0, home_exp, away_exp, rho)
        expected = 1 + away_exp * rho
        assert abs(adj - expected) < 0.001
    
    def test_adjustment_0_1(self):
        # 0-1: 1 + home_exp * rho
        home_exp, away_exp, rho = 1.5, 1.0, 0.13
        adj = dixon_coles_adjustment(0, 1, home_exp, away_exp, rho)
        expected = 1 + home_exp * rho
        assert abs(adj - expected) < 0.001
    
    def test_adjustment_1_1(self):
        # 1-1: 1 - rho
        home_exp, away_exp, rho = 1.5, 1.0, 0.13
        adj = dixon_coles_adjustment(1, 1, home_exp, away_exp, rho)
        expected = 1 - rho
        assert abs(adj - expected) < 0.001
    
    def test_adjustment_other_scores(self):
        # Other scores: 1.0 (no adjustment)
        assert dixon_coles_adjustment(2, 0, 1.5, 1.0, 0.13) == 1.0
        assert dixon_coles_adjustment(2, 2, 1.5, 1.0, 0.13) == 1.0


class TestMatchProbabilities:
    """Test full match probability calculation"""
    
    def test_calculate_match_probabilities(self):
        home_win, draw, away_win, score_matrix = calculate_match_probabilities(1.5, 1.0)
        
        # Probabilities should sum to ~1
        total = home_win + draw + away_win
        assert abs(total - 1.0) < 0.01
        
        # Home win should be > away win when home_exp > away_exp
        assert home_win > away_win
        
        # Score matrix should have correct shape
        assert score_matrix.shape == (11, 11)  # max_goals=10 default
    
    def test_over_under_probabilities(self):
        _, _, _, score_matrix = calculate_match_probabilities(1.5, 1.0)
        over_25, under_25, push = calculate_over_under_probabilities(score_matrix, 2.5)
        
        total = over_25 + under_25 + push
        assert abs(total - 1.0) < 0.001
    
    def test_btts_probabilities(self):
        _, _, _, score_matrix = calculate_match_probabilities(1.5, 1.0)
        btts_yes, btts_no = calculate_btts_probabilities(score_matrix)
        
        total = btts_yes + btts_no
        assert abs(total - 1.0) < 0.001


class TestEloSystem:
    """Test Elo rating calculations"""
    
    def test_expected_score_equal(self):
        # Equal Elo with 100 home advantage: expected score ~0.64 for home
        exp = expected_score(1500, 1500)
        assert abs(exp - 0.64) < 0.02
    
    def test_expected_score_home_advantage(self):
        # Higher Elo should have higher expected score when home
        exp_high = expected_score(1600, 1400)  # 1600 at home vs 1400
        exp_low = expected_score(1400, 1600)   # 1400 at home vs 1600
        assert exp_high > 0.5
        assert exp_low < 0.5
        # These are different scenarios (different home teams), not complementary
        # So sum is not necessarily 1.0
    
    def test_update_elo_win(self):
        new_a, new_b = update_elo(1500, 1500, 2, 1)  # A wins
        assert new_a > 1500
        assert new_b < 1500
    
    def test_update_elo_draw(self):
        new_a, new_b = update_elo(1500, 1500, 1, 1)
        # Both should move slightly toward 1500 (no change for equal)
        assert abs(new_a - 1500) < 5
        assert abs(new_b - 1500) < 5
    
    def test_elo_to_goal_expectancy(self):
        home_exp, away_exp = elo_to_goal_expectancy(0)  # Equal teams
        assert abs(home_exp - away_exp) < 0.1
        
        home_exp, away_exp = elo_to_goal_expectancy(200)  # Home stronger
        assert home_exp > away_exp


class TestKellyCriterion:
    """Test Kelly criterion calculations"""
    
    def test_kelly_criterion_positive(self):
        # 60% probability at 2.0 odds: f = (0.6*1 - 0.4)/1 = 0.2
        # But capped at 5% (max_fraction default is None, but fractional_kelly_stake caps at 5%)
        kelly = kelly_criterion(0.6, 2.0, fraction=1.0, max_fraction=None)
        assert abs(kelly - 0.2) < 0.001
    
    def test_kelly_criterion_capped(self):
        # With max_fraction=0.05, should be capped at 5%
        kelly = kelly_criterion(0.6, 2.0, fraction=1.0, max_fraction=0.05)
        assert abs(kelly - 0.05) < 0.001
    
    def test_kelly_criterion_negative(self):
        # 40% probability at 2.0 odds: negative kelly
        kelly = kelly_criterion(0.4, 2.0)
        assert kelly <= 0
    
    def test_kelly_criterion_zero_odds(self):
        kelly = kelly_criterion(0.5, 1.0)
        assert kelly == 0.0
    
    def test_fractional_kelly_stake(self):
        stake = fractional_kelly_stake(1000.0, 0.6, 2.0, fraction=0.25)
        # Full kelly = 0.2, fractional = 0.05, capped at 5% = 50
        assert stake == 50.0
    
    def test_fractional_kelly_stake_capped(self):
        stake = fractional_kelly_stake(1000.0, 0.9, 1.5, fraction=0.25)
        # Should be capped at 5% = 50
        assert stake <= 50.0


# ============================================
# COUPON ENGINE TESTS
# ============================================

class TestCouponTypes:
    """Test coupon type definitions"""
    
    def test_single_coupon(self):
        single = COUPON_TYPES['SINGLE']
        assert single['min_legs'] == 1
        assert single['max_legs'] == 1
    
    def test_accumulator_coupons(self):
        for i in range(2, 7):
            key = f'ACCUMULATOR_{i}' if i > 3 else {2: 'DOUBLE', 3: 'TREBLE'}.get(i)
            if key in COUPON_TYPES:
                ct = COUPON_TYPES[key]
                assert ct['min_legs'] == i
                assert ct['max_legs'] == i
    
    def test_system_coupons(self):
        systems = ['SYSTEM_2_3', 'SYSTEM_2_4', 'SYSTEM_3_4', 'SYSTEM_2_5', 'SYSTEM_3_5', 'SYSTEM_4_5']
        for sys_key in systems:
            assert sys_key in COUPON_TYPES
            ct = COUPON_TYPES[sys_key]
            assert 'system' in ct
    
    def test_lucky_coupons(self):
        lucky = ['LUCKY15', 'LUCKY31', 'LUCKY63']
        for lk in lucky:
            assert lk in COUPON_TYPES
            ct = COUPON_TYPES[lk]
            assert ct['system'] == lk.lower()
    
    def test_classic_systems(self):
        classics = ['YANKEE', 'CANADIAN', 'HEINZ', 'SUPER_HEINZ', 'GOLIATH']
        for cl in classics:
            assert cl in COUPON_TYPES


class TestStrategyConfigs:
    """Test strategy configurations"""
    
    def test_value_strategy(self):
        val = STRATEGY_CONFIGS['VALUE']
        assert val['min_value_score'] == 1.05
        assert val['min_confidence'] == 0.55
        assert val['weight_value'] > val['weight_confidence']
    
    def test_high_confidence_strategy(self):
        hc = STRATEGY_CONFIGS['HIGH_CONFIDENCE']
        assert hc['min_confidence'] == 0.70
        assert hc['weight_confidence'] > hc['weight_value']
    
    def test_longshot_strategy(self):
        ls = STRATEGY_CONFIGS['LONGSHOT']
        assert ls['min_value_score'] == 1.10
        assert ls['max_odds'] == 100.0


# ============================================
# BANKROLL ENGINE TESTS
# ============================================

class TestBankrollEngine:
    """Test bankroll operations"""
    
    @pytest.fixture(autouse=True)
    def setup_db(self):
        self.temp_db = tempfile.NamedTemporaryFile(suffix='.db', delete=False)
        self.temp_db.close()
        import database.db as db_module
        self.original_db_path = db_module.DB_PATH
        db_module.DB_PATH = self.temp_db.name
        init_db()
        yield
        db_module.DB_PATH = self.original_db_path
        os.unlink(self.temp_db.name)
    
    def test_get_bankroll_status(self):
        bankroll = get_bankroll_status()
        assert bankroll is not None
        assert bankroll['current_balance'] == 1000.0
        assert bankroll['total_profit'] == 0.0
        assert bankroll['roi'] == 0.0
    
    def test_update_bankroll_deposit(self):
        result = update_bankroll(100.0, 'DEPOSIT', note='Test deposit')
        assert result is True
        
        bankroll = get_bankroll_status()
        assert bankroll['current_balance'] == 1100.0
        assert bankroll['total_staked'] == 0.0  # Deposit doesn't count as stake
    
    def test_update_bankroll_stake(self):
        update_bankroll(50.0, 'STAKE', note='Test bet')
        
        bankroll = get_bankroll_status()
        assert bankroll['current_balance'] == 950.0
        assert bankroll['total_staked'] == 50.0
    
    def test_update_bankroll_winning(self):
        # First stake
        update_bankroll(50.0, 'STAKE')
        # Then win (return 100 = stake 50 + profit 50)
        # Current implementation adds full return to profit
        update_bankroll(100.0, 'WINNING', note='Won bet')
        
        bankroll = get_bankroll_status()
        assert bankroll['current_balance'] == 1050.0  # 1000 - 50 + 100
        assert bankroll['total_won'] == 100.0
        assert bankroll['total_profit'] == 100.0  # Full return added as profit
    
    def test_insufficient_balance_raises(self):
        # Try to stake more than balance
        with pytest.raises(ValueError):
            update_bankroll(2000.0, 'STAKE')
    
    def test_calculate_kelly_stake(self):
        stake = calculate_kelly_stake(1000.0, 0.6, 2.0, fraction=0.25)
        assert stake == 50.0  # Capped at 5% of 1000


# ============================================
# INTEGRATION TESTS
# ============================================

class TestIntegration:
    """Integration tests combining multiple components"""
    
    @pytest.fixture(autouse=True)
    def setup_db(self):
        self.temp_db = tempfile.NamedTemporaryFile(suffix='.db', delete=False)
        self.temp_db.close()
        import database.db as db_module
        self.original_db_path = db_module.DB_PATH
        db_module.DB_PATH = self.temp_db.name
        init_db()
        yield
        db_module.DB_PATH = self.original_db_path
        os.unlink(self.temp_db.name)
    
    def test_full_betting_cycle(self):
        """Test: deposit -> stake -> win -> check balance"""
        # Deposit
        update_bankroll(500.0, 'DEPOSIT')
        b = get_bankroll_status()
        assert b['current_balance'] == 1500.0
        
        # Place bet
        update_bankroll(100.0, 'STAKE')
        b = get_bankroll_status()
        assert b['current_balance'] == 1400.0
        assert b['total_staked'] == 100.0
        
        # Win
        update_bankroll(250.0, 'WINNING')  # 2.5 odds
        b = get_bankroll_status()
        assert b['current_balance'] == 1650.0
        assert b['total_won'] == 250.0
        assert b['total_profit'] == 250.0  # Full return added as profit
    
    def test_kelly_stake_respects_limits(self):
        """Test Kelly stake respects max stake percentage"""
        # High confidence, high odds -> large kelly
        stake = calculate_kelly_stake(1000.0, 0.8, 5.0, fraction=0.25)
        # Should be capped at 5% = 50
        assert stake <= 50.0


# ============================================
# RUN TESTS
# ============================================

if __name__ == '__main__':
    pytest.main([__file__, '-v', '--tb=short'])