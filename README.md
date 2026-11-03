# ⚽ Autonomic Sports Betting Prediction & Bankroll Management System

Gelişmiş veri analizi, 10 metrikli tahmin puanlaması, otonom kupon üretimi ve dinamik kasa yönetimi sunan uçtan uca spor bahis analiz platformu.

## 🌟 Ana Özellikler

- **Çoklu Veri Kaynağı Toplayıcı:** SportMonks, API-Football, The Odds API, ESPN, LiveScore, TheSportsDB, Football-Data.org entegrasyonu.
- **10 Metrikli Tahmin Motoru:**
  1. **Güven Skoru (Confidence)**
  2. **Dürüstlük Skoru (Honesty)**
  3. **Güvenilirlik (Reliability)**
  4. **Hesaplanmış Başarı Olasılığı (Calculated Success Probability)**
  5. **Değer Skoru (Value Score)**
  6. **Risk Skoru (Risk Score)**
  7. **Kalite Skoru (Quality Score)**
  8. **Tutarlılık Skoru (Consistency)**
  9. **Piyasa Verimliliği (Market Efficiency)**
  10. **Senaryo Skoru (Scenario Score)**
- **10 Kupon Türü × Günlük 10 Kupon:** Normal, Yüksek Oran, Sürpriz, Sistem, Canlı Değer, İY/MS, Korner/Kart, Oyuncu Performansı, Çoklu Spor Parlay, vb.
- **Kasa & Risk Yönetimi:** Kelly Kriteri, CVaR, Günlük Stop-Loss ve Take-Profit koruması.
- **Sıfır Sabit Kod (Zero Hardcoding):** Merkezi `settings` tablosu ve arayüz üzerinden dinamik yapılandırma.
- **Web Dashboard & API:** FastAPI + HTMX ile hızlı, modern kullanıcı paneli ve RESTful API.

## 🚀 Hızlı Başlangıç

```bash
pip install -r requirements.txt
python3 main.py
```

Web Paneli: `http://localhost:8000`
