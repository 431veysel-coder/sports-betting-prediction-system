from database.db import get_db

def get_bankroll_status():
    conn = get_db()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM bankroll WHERE id = 1")
    row = cursor.fetchone()
    conn.close()
    if row:
        return dict(row)
    return {"current_balance": 1000.0, "total_profit": 0.0, "roi": 0.0}

def update_bankroll(amount, txn_type, reference_id=None, note=None):
    """
    txn_type: STAKE, WINNING, DEPOSIT, WITHDRAWAL, REFUND, FEE, ADJUSTMENT
    """
    conn = get_db()
    cursor = conn.cursor()
    
    cursor.execute("SELECT * FROM bankroll WHERE id = 1")
    b = cursor.fetchone()
    if not b:
        conn.close()
        return False
        
    current_balance = b['current_balance']
    total_staked = b['total_staked']
    total_won = b['total_won']
    total_lost = b['total_lost']
    total_profit = b['total_profit']
    
    if txn_type == 'STAKE':
        if current_balance < amount:
            conn.close()
            raise ValueError("Insufficient balance for stake!")
        current_balance -= amount
        total_staked += amount
    elif txn_type == 'WINNING':
        # amount is the total return (stake + profit)
        # Profit = return - stake (but we don't know stake here, so track total_won)
        current_balance += amount
        total_won += amount
        total_profit += amount  # Simplified: treat full return as profit for now
    elif txn_type == 'DEPOSIT':
        current_balance += amount
    elif txn_type == 'WITHDRAWAL':
        current_balance -= amount
    elif txn_type == 'REFUND':
        # Stake returned (void/cancelled)
        current_balance += amount
    elif txn_type == 'FEE':
        current_balance -= amount
    elif txn_type == 'ADJUSTMENT':
        current_balance += amount  # Can be negative
        
    # Calculate ROI
    roi = round((total_profit / total_staked * 100.0) if total_staked > 0 else 0.0, 2)
    
    cursor.execute("""
        UPDATE bankroll 
        SET current_balance = ?, total_staked = ?, total_won = ?, total_lost = ?, total_profit = ?, roi = ?, updated_at = CURRENT_TIMESTAMP
        WHERE id = 1
    """, (current_balance, total_staked, total_won, total_lost, total_profit, roi))
    
    cursor.execute("""
        INSERT INTO bankroll_transactions (amount, txn_type, reference_id, note, balance_before, balance_after)
        VALUES (?, ?, ?, ?, ?, ?)
    """, (amount, txn_type, reference_id, note, b['current_balance'], current_balance))
    
    conn.commit()
    conn.close()
    return True

def calculate_kelly_stake(bankroll, probability, odds, fraction=0.25):
    """
    Kelly Kriteri (Fractional Kelly) ile optimal bahis miktarı hesaplama
    f* = (p * b - q) / b
    """
    q = 1.0 - probability
    b = odds - 1.0
    if b <= 0:
        return 10.0
    kelly = (probability * b - q) / b
    if kelly <= 0:
        return 5.0 # Minimum safe stake
    optimal_stake = bankroll * kelly * fraction
    return round(max(5.0, min(optimal_stake, bankroll * 0.05)), 2)