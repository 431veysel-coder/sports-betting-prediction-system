# MİNİS NEXUS ULTRA — SÜPER GÜÇ VE YETENEK ŞARTNAMESİ (V3.0)

**Tarih:** 2026-11-02  
**Konum:** `apps/minis-nexus/`  
**Sürüm:** 3.0.0 (Extended Skill, MCP Gateway & Hybrid Web Engine Architecture)

---

## 🏛️ 1. GENİŞLETİLMİŞ MİMARİ VE YETENEK KATMANLARI

```
┌────────────────────────────────────────────────────────────────────────┐
│                   1. MİNİS NEXUS CONTROL & OVERLAY UI                  │
│       (Floating Assistant, Quick Settings Tile, Live Screen Vision)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                2. EMBEDDED NATIVE MCP GATEWAY SERVER                   │
│   (MCP Protocol Engine: HTTP/SSE + STDIO Bridge + Tool Auto-Discovery) │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│           3. HYBRID WEB ENGINE & MULTI-SEARCH ORCHESTRATOR             │
│  (API: Tavily/Exa/Perplexity | Headless: Jina/Firecrawl | Web-Search)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│              4. AI MODEL MATRIX & LOCAL INFERENCE ENGINE               │
│  (EVREN API + OpenRouter + Local Llama.cpp/Gemma + NPU/GPU Acceleration)│
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                5. DEEP SYSTEM AUTOMATION & DRIVER HUB                  │
│   (Shizuku ADB Driver + Accessibility Node Inspector + Alarm Scheduler) │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🌐 2. HYBRID WEB MOTORLARI VE ARAMA SİSTEMLERİ (WEB ENGINE SUITE)

Minis Nexus, internetten bilgi toplama ve web siteleriyle etkileşime girme konusunda 4 kademeli **Hibrit Web Motoru (Hybrid Web Engine)** kullanır:

### 🔍 2.1 Çoklu Arama Motoru Zinciri (Multi-Search Chain)
* **Birincil Arama (API Katmanı):** Tavily API, Exa MCP, Perplexity API.
* **İkincil Arama (Scraping Katmanı):** Metaso AI, Brave Search, DuckDuckGo HTML.
* **Akıllı Dönüştürücü:** Web sayfalarını anında temiz Markdown formatına çeviren **Jina Reader** (`r.jina.ai`) ve **Firecrawl** entegrasyonu.

### 🌐 2.2 İnteraktif Web Tarayıcı ve WebView Otomasyonu
* **Android Native WebView Engine:** Arka planda başsız (headless) veya kullanıcı önünde çerez/oturum korumalı tarayıcı oturumu açma.
* **Otomatik DOM Parse & Backbone:** Web sayfasının basitleştirilmiş DOM ağacını çıkarıp yapay zekaya tıklanabilir eleman haritası sunma (`get_backbone`, `find_elements`).

---

## 🔌 3. GÖMÜLÜ MCP (MODEL CONTEXT PROTOCOL) GATEWAY

Minis Nexus, cihaz üzerinde çalışan **yerleşik bir MCP Sunucusu (Embedded MCP Gateway)** barındırır:

1. **Araç Otomatik Keşfi (Tool Auto-Discovery):** Cihazdaki tüm Android yeteneklerini (Kamera, Konum, Kişiler, SMS, Ekran Okuma, Bildirimler) doğrudan birer **MCP Tool** olarak dışarıya açar.
2. **Çoklu Taşıma Protokolü (Transport Layer):**
   * `HTTP / SSE (Server-Sent Events)`: Minis PRoot ve harici servisler için.
   * `STDIO Bridge`: Local CLI ve Termux araçları için.
3. **OpenAI & Claude MCP Uyumlu:** Anthropic Claude Desktop veya OpenMinis konfigürasyonlarına tek tıkla `minis-mcp-cli` üzerinden eklenebilir.

---

## 🤖 4. ÇOKLU MODEL (AI MATRIX) VE YEREL ÇIKARIM

* **Milli Yapay Zeka EVREN Entegrasyonu:** `https://api.ssyz.org.tr/api/v1` ile yüksek performanslı Türkçe LLM çıkarımı.
* **Yerel C++ Llama Engine (Ollama/Llama.cpp NDK):** İnternetsiz ortamda cihazın NPU/GPU gücünü kullanarak 1B - 3B boyutundaki modelleri (Gemma-2B, Qwen-1.5B, Phi-3) çalıştırma.
* **Görsel ve İşitsel AI (Vision & Speech):**
  * *On-Device Voice:* Whisper TFLite / Android System Speech Recognizer.
  * *Vision Engine:* Ekran görüntüsünü alıp anında OCR (Metin Okuma) ve Nesne Algılama yapabilen yerel görüş katmanı.

---

## ⚡ 5. DERİN SİSTEM OTOMASYONU VE ZAMANLAYICI (SCHEDULER)

* **Accessibility Node Inspector:** Ekrandaki tüm uygulamaların kullanıcı arayüzü ağacını (UI Tree) anlık okur ve öğelere tıklar.
* **Shizuku ADB Shell Engine:** Root gerektirmeden `adb shell` seviyesinde komutlar çalıştırır (`input tap`, `pm install`, `am start`).
* **Sistem Alarm ve Görev Zamanlayıcı (Android AlarmManager + WorkManager):** Cihaz kapalı olsa bile belirlenen saatte (örneğin sabah 08:00'de) uyanıp otonom görevleri koşturur.

---

## 🛠️ 6. KOD YAPISI (PACKAGE MATRIX)

```
com.openminis.app.nexus/
├── MainActivity.kt                  # Ana Kontrol Paneli & İzin Yöneticisi
├── core/
│   ├── mcp/
│   │   ├── McpServer.kt             # Embedded MCP Protocol Gateway
│   │   └── AndroidMcpTools.kt       # Android Donanım & Sistem MCP Araçları
│   ├── web/
│   │   ├── HybridWebEngine.kt       # Multi-search & Jina/Firecrawl Reader
│   │   └── HeadlessWebView.kt       # Background DOM & Cookie Manager
│   ├── automation/
│   │   ├── AccessibilityDriver.kt   # UI Tree Reader & Auto-Clicker
│   │   └── ShizukuBridge.kt         # ADB Shell Executer
│   ├── ai/
│   │   ├── EvrenApiBridge.kt        # EVREN API Client
│   │   └── LocalInferenceEngine.kt  # On-device Llama.cpp / Vision
│   └── service/
│       ├── ForegroundDaemon.kt      # 7/24 Kesintisiz Arka Plan Servisi
│       └── AlarmScheduler.kt        # AlarmManager & WorkManager
```
