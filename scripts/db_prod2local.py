#!/usr/bin/env python3
"""
db_prod2local.py - Spring Boot Database Migration

Migrates database with remote data import.
- Backs up current database
- Drops all tables
- Creates fresh schema via Spring Boot
- Imports data from remote database

Usage:
    scripts/db_prod2local.py                # Import from remote (default)
    FORCE_YES=true scripts/db_prod2local.py # Skip confirmation
"""

import os
import sys
import shutil
import json
import sqlite3
import requests
from datetime import datetime
from pathlib import Path

from migration_utils import (
    MigrationError,
    SkipModelInitFlag,
    SpringBootSchemaRunner,
    SqliteDatabaseFiles,
    ensure_port_not_in_use,
    print_header,
)

# Configuration
PROJECT_ROOT = Path(__file__).parent.parent
DB_FILE = PROJECT_ROOT / "volumes" / "sqlite.db"
BACKUP_DIR = PROJECT_ROOT / "volumes" / "backups"
JSON_DATA_FILE = PROJECT_ROOT / "volumes" / "data.json"
SKIP_FLAG_FILE = PROJECT_ROOT / "volumes" / ".skip-modelinit"
SPRING_PORT = 8585
LOG_FILE = "/tmp/db_migrate.log"

# Remote database configuration
PROD_URL = "https://spring.opencodingsociety.com"
PROD_LOGIN_URL = f"{PROD_URL}/login"
DATA_URL = f"{PROD_URL}/api/exports/getAll"

# Credentials
ADMIN_UID = "toby"


def coerce_value(value):
    """Convert dict/list values to JSON strings for SQLite storage"""
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return value


# --- Credential loading ---

def load_admin_password():
    """Load ADMIN_PASSWORD from .env file"""
    env_file = PROJECT_ROOT / ".env"
    if not env_file.exists():
        print(f"  Error: .env file not found at {env_file}")
        sys.exit(1)

    with open(env_file, "r") as f:
        for line in f:
            line = line.strip()
            if line.startswith("ADMIN_PASSWORD="):
                password = line.split("=", 1)[1].strip().strip('"').strip("'")
                return password

    print("  Error: ADMIN_PASSWORD not found in .env file")
    sys.exit(1)


# --- Remote authentication & fetch ---

def authenticate_to_production(password):
    """Authenticate to production via form login; return session cookies"""
    print("Authenticating to production server...")
    auth_data = {
        "username": ADMIN_UID,
        "password": password,
    }

    try:
        session = requests.Session()
        response = session.post(PROD_LOGIN_URL, data=auth_data, timeout=10, allow_redirects=False)

        if response.status_code == 302 and "sess_java_spring" in response.cookies:
            print(f"  Authenticated as '{ADMIN_UID}'")
            return session.cookies
        else:
            print(f"  Authentication failed: HTTP {response.status_code}")
            print(f"  Response: {response.text[:200]}")
            sys.exit(1)

    except requests.exceptions.HTTPError as e:
        print(f"  Authentication failed: HTTP {e.response.status_code}")
        print(f"  Response: {e.response.text}")
        sys.exit(1)
    except requests.exceptions.RequestException as e:
        print(f"  Error connecting to production server: {e}")
        sys.exit(1)


def fetch_remote_export(cookies):
    """Fetch the export JSON from the authenticated production session"""
    headers = {"Content-Type": "application/json"}
    response = requests.get(DATA_URL, headers=headers, cookies=cookies, timeout=30)
    response.raise_for_status()
    return response.json()


def fetch_remote_data():
    """Load credentials, authenticate, and return the remote export data"""
    print(f"\nFetching remote data from: {DATA_URL}")

    password = load_admin_password()
    cookies = authenticate_to_production(password)

    try:
        data = fetch_remote_export(cookies)
        print(f"  Data fetched successfully — tables found: {len(data)}")
        return data
    except requests.RequestException as e:
        print(f"  Failed to fetch remote data: {e}")
        return None


# --- Local JSON persistence ---

def save_data_to_json(data):
    """Save remote data to the local JSON file, backing up any existing copy"""
    if JSON_DATA_FILE.exists():
        timestamp = datetime.now().strftime('%Y%m%d%H%M%S')
        backup_file = Path(str(JSON_DATA_FILE) + f".{timestamp}.bak")
        shutil.copy2(JSON_DATA_FILE, backup_file)
        print(f"  Existing JSON backed up to {backup_file}")

    with open(JSON_DATA_FILE, 'w') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"Remote data saved to {JSON_DATA_FILE}")


def load_local_json():
    """Return data from the local JSON file, or None if unavailable"""
    if JSON_DATA_FILE.exists():
        try:
            with open(JSON_DATA_FILE, 'r') as f:
                return json.load(f)
        except Exception as e:
            print(f"  Failed to read local JSON: {e}")
    return None


# --- Schema recreation via Spring Boot ---


def recreate_schema():
    """Start Spring Boot temporarily to recreate schema, then stop it"""
    print("\nStarting Spring Boot to recreate schema...")
    print("(ModelInit will be skipped to avoid conflicts)")
    print("(This will take a few seconds...)")

    runner = SpringBootSchemaRunner(PROJECT_ROOT, LOG_FILE, SPRING_PORT)
    skip_flag = SkipModelInitFlag(SKIP_FLAG_FILE)

    skip_flag.create()
    try:
        runner.run(timeout_seconds=180, settle_seconds=5)
    finally:
        skip_flag.remove()


# --- SQLite import ---

def get_existing_columns(cursor, table_name):
    """Return the set of column names for an existing table"""
    cursor.execute(f'PRAGMA table_info("{table_name}")')
    return {row[1] for row in cursor.fetchall()}


def build_insert_statement(table_name, columns):
    """Return a parameterised INSERT OR REPLACE SQL string"""
    placeholders = ','.join(['?' for _ in columns])
    column_names = ','.join([f'"{col}"' for col in columns])
    return f'INSERT OR REPLACE INTO "{table_name}" ({column_names}) VALUES ({placeholders})'


def import_table(cursor, conn, table_name, records, existing_tables):
    """
    Import all records for one table into SQLite.
    Returns 'imported', 'skipped', or 'error'.
    """
    if table_name not in existing_tables:
        print(f"  Skipping {table_name}: table doesn't exist in schema")
        return 'skipped'

    table_columns = get_existing_columns(cursor, table_name)
    if not table_columns:
        print(f"  Skipping {table_name}: no columns discovered via PRAGMA")
        return 'skipped'

    columns = [col for col in records[0].keys() if col in table_columns]
    if not columns:
        print(f"  Skipping {table_name}: no matching columns between JSON and DB")
        return 'skipped'

    try:
        insert_sql = build_insert_statement(table_name, columns)
        batch = [[coerce_value(record.get(col)) for col in columns] for record in records]
        cursor.executemany(insert_sql, batch)
        conn.commit()
        print(f"  {table_name}: {len(records)} records imported")
        return 'imported'
    except Exception as e:
        print(f"  {table_name}: Error - {str(e)[:120]}")
        conn.rollback()
        return 'error'


def resolve_allowlist():
    """Return the set of allowed table names from IMPORT_TABLES env var, or None for all"""
    env_allow = os.getenv("IMPORT_TABLES")
    if env_allow is None:
        return None
    token = env_allow.strip()
    if not token or token.upper() in {"*", "ALL"}:
        return None
    return {t.strip() for t in token.split(',') if t.strip()}


def import_data_to_sqlite(data):
    """Import data from JSON into the SQLite database; return True on success"""
    print_header("Importing Data to SQLite")

    if not DB_FILE.exists():
        print(f"Error: Database file not found: {DB_FILE}")
        return False

    print(f"Connecting to database: {DB_FILE}")
    try:
        conn = sqlite3.connect(str(DB_FILE))
        cursor = conn.cursor()
        print("Connected successfully\n")
    except Exception as e:
        print(f"Error connecting to database: {e}")
        return False

    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
    existing_tables = {row[0] for row in cursor.fetchall()}
    print(f"Existing tables in database: {len(existing_tables)}")

    allowlist = resolve_allowlist()
    print("Allowlist: ALL tables" if allowlist is None else f"Allowlist: {', '.join(sorted(allowlist))}")
    print("\nImporting data...\n")

    counts = {'imported': 0, 'skipped': 0, 'error': 0}

    for table_name, records in data.items():
        if allowlist is not None and table_name not in allowlist:
            continue
        if not records:
            continue
        result = import_table(cursor, conn, table_name, records, existing_tables)
        counts[result] += 1

    conn.close()

    print_header("Import Summary")
    print(f"  Imported: {counts['imported']} tables")
    print(f"  Skipped:  {counts['skipped']} tables (not in schema/allowlist)")
    print(f"  Errors:   {counts['error']} tables\n")

    if counts['imported'] > 0:
        print("Import completed successfully!")
        return True

    print("No data was imported")
    return False


# --- Pre-flight checks & confirmation ---

def check_spring_boot_not_running():
    """Exit with an error if Spring Boot is already running on SPRING_PORT"""
    ensure_port_not_in_use(SPRING_PORT, "Please stop it first: pkill -f 'spring-boot:run'")


def get_user_confirmation():
    """Return True if the user agrees to proceed (or FORCE_YES is set)"""
    if not DB_FILE.exists():
        return True

    if os.getenv('FORCE_YES') == 'true':
        print("FORCE_YES detected, proceeding automatically...")
        return True

    print("WARNING: You are about to lose all data in your local sqlite database!")
    print("WARNING: This operation will:")
    print("   - Backup the current database")
    print("   - Drop all existing tables")
    print("   - Recreate schema from scratch")
    print("   - Import data from remote database\n")
    response = input("Do you want to continue? (y/n): ").strip().lower()
    return response in ('y', 'yes')


# --- Entry point ---

def main():
    print_header("DATABASE MIGRATION WITH REMOTE IMPORT")

    check_spring_boot_not_running()

    if not get_user_confirmation():
        print("Exiting without making changes.")
        sys.exit(0)

    remote_data = fetch_remote_data()
    if remote_data:
        save_data_to_json(remote_data)
    else:
        print("WARNING: Could not fetch remote data, will use local JSON if available")
        remote_data = load_local_json()
        if not remote_data:
            print("No remote or local data available for import")
            sys.exit(1)

    db_files = SqliteDatabaseFiles(DB_FILE, BACKUP_DIR)
    db_files.backup()
    db_files.remove()
    recreate_schema()
    import_data_to_sqlite(remote_data)

    print_header("DATABASE MIGRATION COMPLETE")
    print("Database migrated with:")
    print("  Fresh schema (created by Hibernate)")
    print("  Remote data imported (ModelInit was skipped)")
    print("\nYou can now start your application:")
    print("  ./mvnw spring-boot:run\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")
        SkipModelInitFlag(SKIP_FLAG_FILE).remove()
        sys.exit(1)
    except MigrationError as e:
        print(f"\nMigration error: {e}", file=sys.stderr)
        SkipModelInitFlag(SKIP_FLAG_FILE).remove()
        sys.exit(1)
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        SkipModelInitFlag(SKIP_FLAG_FILE).remove()
        import traceback
        traceback.print_exc()
        sys.exit(1)
