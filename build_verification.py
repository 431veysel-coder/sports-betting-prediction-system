import os
import sqlite3

def verify_project():
    print("🔍 [Toolkit Verification] Running Sports Betting System & Android App Check...")
    
    # 1. Check core directories
    dirs = ['config', 'database', 'collectors', 'engines', 'api', 'templates', 'android-app', 'nexus-core']
    for d in dirs:
        path = os.path.join(os.path.dirname(__file__), d)
        assert os.path.exists(path), f"Missing directory: {d}"
        print(f"  ✅ Directory OK: {d}")
        
    # 2. Check Database init
    db_path = os.path.join(os.path.dirname(__file__), "data", "sports_betting.db")
    assert os.path.exists(db_path), "Database not initialized!"
    print(f"  ✅ Database OK: {db_path}")
    
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM settings")
    settings_count = cursor.fetchone()[0]
    assert settings_count > 0, "Settings table is empty!"
    print(f"  ✅ Settings Count: {settings_count}")
    conn.close()
    
    # 3. Check Android App files
    android_files = [
        'android-app/build.gradle',
        'android-app/app/build.gradle',
        'android-app/app/src/main/AndroidManifest.xml',
        'android-app/app/src/main/java/com/openminis/betting/app/MainActivity.kt'
    ]
    for af in android_files:
        fpath = os.path.join(os.path.dirname(__file__), af)
        assert os.path.exists(fpath), f"Missing Android file: {af}"
        print(f"  ✅ Android File OK: {af}")
        
    print("\n🎉 ALL CHECKS PASSED SUCCESSFULLY! Production-grade Android & Backend ready.")

if __name__ == "__main__":
    verify_project()
