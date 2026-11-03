import uvicorn
from database.db import init_db
from api.app import app

if __name__ == "__main__":
    print("🚀 Initializing Sports Betting Prediction System Database...")
    init_db()
    print("✨ Database Ready. Starting Web Server on http://0.0.0.0:8000 ...")
    uvicorn.run(app, host="0.0.0.0", port=8000)
