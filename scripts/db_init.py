#!/usr/bin/env python3
"""
db_init.py - Spring Boot Database Initialization

Resets the database to original state with default data.
- Backs up the current database
- Drops all existing tables
- Creates fresh schema via Spring Boot
- Loads default data via ModelInit

Usage:
    cd scripts && ./db_init.py
    or
    scripts/db_init.py
    or with force flag:
    FORCE_YES=true scripts/db_init.py
"""

import os
import sys
from pathlib import Path

from migration_utils import (
    MigrationError,
    SpringBootSchemaRunner,
    SqliteDatabaseFiles,
    ensure_port_not_in_use,
    print_header,
)

# Configuration
PROJECT_ROOT = Path(__file__).parent.parent
DB_FILE = PROJECT_ROOT / "volumes" / "sqlite.db"
BACKUP_DIR = PROJECT_ROOT / "volumes" / "backups"
SPRING_PORT = 8585
LOG_FILE = "/tmp/db_init.log"
ENV_FILE = PROJECT_ROOT / ".env"


def load_env_file():
    """Load KEY=VALUE pairs from .env when present."""
    values = {}
    if not ENV_FILE.exists():
        return values

    with open(ENV_FILE, "r", encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def resolve_setting(key, env_values):
    """Resolve setting from process env first, then .env file values."""
    return os.getenv(key) or env_values.get(key)


def detect_target_db(env_values):
    """Detect whether db_init should target sqlite or mysql."""
    db_url = resolve_setting("DB_URL", env_values)
    db_driver = resolve_setting("DB_DRIVER", env_values)

    if db_url and db_url.lower().startswith("jdbc:mysql:"):
        return "mysql"
    if db_driver and "mysql" in db_driver.lower():
        return "mysql"
    return "sqlite"


def print_target_summary(target_db, env_values):
    """Print a clear summary of which database target will be used."""
    print_header("Target Database")
    if target_db == "mysql":
        print("Mode: MYSQL")
        print(f"  DB_URL: {resolve_setting('DB_URL', env_values)}")
        print(f"  DB_USERNAME: {resolve_setting('DB_USERNAME', env_values)}")
        print(f"  DB_DRIVER: {resolve_setting('DB_DRIVER', env_values)}")
        print(f"  DB_DIALECT: {resolve_setting('DB_DIALECT', env_values)}")
    else:
        print("Mode: SQLITE (local)")
        print(f"  File: {DB_FILE}")
        print("  DB_URL: jdbc:sqlite:volumes/sqlite.db?journal_mode=WAL")


def apply_mysql_defaults_and_validate(env_values):
    """Set safe MySQL defaults if omitted and verify required credentials exist."""
    missing = []
    if not resolve_setting("DB_URL", env_values):
        missing.append("DB_URL")
    if not resolve_setting("DB_USERNAME", env_values):
        missing.append("DB_USERNAME")
    if not resolve_setting("DB_PASSWORD", env_values):
        missing.append("DB_PASSWORD")

    if missing:
        raise MigrationError(
            "MySQL target detected, but required settings are missing: "
            + ", ".join(missing)
        )


def run_sqlite_source_init(force: bool) -> None:
    """Rebuild the local SQLite database with a fresh schema and default data."""
    db_files = SqliteDatabaseFiles(DB_FILE, BACKUP_DIR)

    if DB_FILE.exists():
        # Use the online backup API rather than a file copy: this database runs
        # in WAL mode, so a plain copy can miss whatever is still in the -wal
        # file. backup_sqlite() also verifies row counts and raises if the
        # backup came up short, which is what makes it safe to delete the
        # original on the next line.
        import sqlite_migrate
        sqlite_migrate.backup_sqlite(DB_FILE, BACKUP_DIR)
    else:
        print("No existing database file to backup")

    db_files.remove()

    print("\nStarting Spring Boot to recreate LOCAL SQLite schema and load default data...")
    print("(This will take a few seconds...)")
    runner = SpringBootSchemaRunner(
        PROJECT_ROOT,
        LOG_FILE,
        SPRING_PORT,
        additional_run_args=[
            "--spring.datasource.url=jdbc:sqlite:volumes/sqlite.db?journal_mode=WAL",
            "--spring.datasource.username=admin",
            "--spring.datasource.password=admin",
            "--spring.datasource.driver-class-name=org.sqlite.JDBC",
            "--spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        ],
    )
    runner.run(timeout_seconds=180, settle_seconds=5)


def mysql_connection_settings(env_values):
    """Resolve (host, port, database, username, password) for the MySQL target."""
    from mysqlrestore import parse_jdbc_url

    db_url = resolve_setting("DB_URL", env_values)
    if not db_url:
        raise MigrationError("DB_URL is required for a MySQL target")

    try:
        host, port, database = parse_jdbc_url(db_url)
    except SystemExit as exc:
        raise MigrationError(f"Invalid DB_URL for MySQL target: {db_url}") from exc

    username = resolve_setting("DB_USERNAME", env_values)
    password = resolve_setting("DB_PASSWORD", env_values)

    if not host or not database or not username or not password:
        raise MigrationError("Missing MySQL settings (DB_URL, DB_USERNAME, DB_PASSWORD)")

    return host, port, database, username, password


def run_mysql_schema_init(env_values):
    """Build the MySQL schema with Hibernate itself.

    Hibernate emits native MySQL DDL for the current entities, so the schema is
    correct by construction. Translating a SQLite schema into MySQL instead
    (the old path) downgraded every VARCHAR/DATETIME/JSON column to LONGTEXT and
    dropped every index, UNIQUE key and foreign key.
    """
    host, port, database, username, password = mysql_connection_settings(env_values)
    db_url = resolve_setting("DB_URL", env_values)
    driver = resolve_setting("DB_DRIVER", env_values) or "com.mysql.cj.jdbc.Driver"
    dialect = resolve_setting("DB_DIALECT", env_values) or "org.hibernate.dialect.MySQLDialect"

    print(f"\nGenerating the MySQL schema with Hibernate on {host}:{port}/{database}...")
    print("(This drops and recreates every table Hibernate manages.)")

    runner = SpringBootSchemaRunner(
        PROJECT_ROOT,
        LOG_FILE,
        SPRING_PORT,
        additional_run_args=[
            f"--spring.datasource.url={db_url}",
            f"--spring.datasource.username={username}",
            f"--spring.datasource.password={password}",
            f"--spring.datasource.driver-class-name={driver}",
            f"--spring.jpa.database-platform={dialect}",
        ],
    )
    runner.run(timeout_seconds=300, settle_seconds=10)

def check_spring_boot_running():
    """Check if Spring Boot is already running"""
    ensure_port_not_in_use(SPRING_PORT, "Please stop it first: pkill -f 'spring-boot:run'")


def get_user_confirmation(target_db):
    """Get user confirmation before proceeding"""
    if target_db == "sqlite" and not DB_FILE.exists():
        return True
    
    # Check for FORCE_YES environment variable
    if os.getenv('FORCE_YES') == 'true':
        print("FORCE_YES detected, proceeding automatically...")
        return True
    
    if target_db == "mysql":
        print("WARNING: You are about to reset the configured MySQL database!")
        print("This will recreate schema and default data on the MySQL target")
        print("All current MySQL data may be lost\n")
    else:
        print("WARNING: You are about to lose all data in the local SQLite database!")
        print("This will reset SQLite to original state with default data")
        print("All current SQLite data will be lost (after backup)\n")
    
    response = input("Continue? (y/n): ").strip().lower()
    return response in ('y', 'yes')


def main():
    """Main initialization process"""
    env_values = load_env_file()
    target_db = detect_target_db(env_values)
    print_target_summary(target_db, env_values)

    print_header(f"Database Reset to Original State ({target_db.upper()})")
    
    # Step 1: Check if Spring Boot is running
    check_spring_boot_running()
    
    # Step 2: Get user confirmation
    if not get_user_confirmation(target_db):
        print("Cancelled.")
        sys.exit(0)

    # Step 3: Build the schema on the target with Hibernate, which emits DDL
    # native to that database. No cross-dialect translation is involved.
    if target_db == "mysql":
        apply_mysql_defaults_and_validate(env_values)
        run_mysql_schema_init(env_values)
    else:
        run_sqlite_source_init(force=os.getenv('FORCE_YES') == 'true')

    # Success message
    print_header("DATABASE RESET COMPLETE")
    print(f"The {target_db.upper()} database has been reset to original state with:")
    print("  Fresh schema created by Hibernate (native DDL for this database)")
    print("  Default data loaded (Person.init(), QuizScore.init(), etc.)")
    if target_db == "mysql":
        print("\nThe MySQL database now holds the NEW schema and seed data only.")
        print("Load your real data on top of it with:")
        print("  python3 scripts/mysqlrestore.py --keep-target-schema \\")
        print("      --backup-file volumes/backups/<your_backup>.db")
    print("\nYou can now start your application normally:")
    print("  ./mvnw spring-boot:run\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)