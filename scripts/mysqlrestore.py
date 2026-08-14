#!/usr/bin/env python3
"""
mysqlrestore.py - SQLite to MySQL Restore

Restores data from SQLite backup file to MySQL server, replacing all existing data.
Reads MySQL connection details from .env file.
Uses the most recent backup file if no file is specified.

Usage:
    python3 scripts/mysqlrestore.py
    python3 scripts/mysqlrestore.py --backup-file volumes/backups/mysql_backup_20240101_120000.db
    cd scripts && python3 mysqlrestore.py
"""

import os
import sys
import re
import argparse
import sqlite3
from pathlib import Path
from datetime import datetime

# Configuration, connection handling and the META_TABLE name are shared with
# mysqlbackup.py and db_init.py so all three always resolve the same target.
from mysql_common import (
    MySQLError as Error,
    BACKUP_DIR,
    ENV_FILE,
    META_TABLE,
    PROJECT_ROOT,
    get_mysql_config,
    get_mysql_connection,
    load_env_file,
    parse_jdbc_url,
    print_header,
)


def find_latest_backup():
    """Find the most recent MySQL backup file"""
    if not BACKUP_DIR.exists():
        print(f"Error: Backup directory not found: {BACKUP_DIR}")
        sys.exit(1)
    
    # Find all mysql backup files
    backup_files = list(BACKUP_DIR.glob("mysql_backup_*.db"))
    
    if not backup_files:
        print(f"Error: No MySQL backup files found in {BACKUP_DIR}")
        sys.exit(1)
    
    # Sort by modification time, most recent first
    backup_files.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    
    return backup_files[0]


def get_sqlite_tables(sqlite_conn):
    """Get all restorable table names from the SQLite backup.

    Only sqlite internal tables and this script's own bookkeeping table are
    excluded. Hibernate Envers audit tables (HT_*/HTE_*) ARE restored: the app
    runs with spring.jpa.hibernate.ddl-auto=none, so Hibernate will not recreate
    them, and dropping them breaks every write to an audited entity.
    """
    cursor = sqlite_conn.cursor()
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
    tables = [row[0] for row in cursor.fetchall()]
    cursor.close()

    return [t for t in tables if t != META_TABLE]


def load_backup_metadata(sqlite_conn):
    """Return {table_name: (mysql_ddl, source_rows)} recorded at backup time.

    Empty when restoring a backup taken before mysqlbackup.py started recording
    metadata (or a plain local sqlite.db), in which case the schema is rebuilt
    from SQLite PRAGMA metadata instead.
    """
    cursor = sqlite_conn.cursor()
    try:
        cursor.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?", (META_TABLE,)
        )
        if not cursor.fetchone():
            return {}
        cursor.execute(f"SELECT table_name, mysql_ddl, source_rows FROM `{META_TABLE}`")
        return {row[0]: (row[1], row[2]) for row in cursor.fetchall()}
    except sqlite3.Error:
        return {}
    finally:
        cursor.close()


def normalize_recorded_ddl(mysql_ddl, table_name):
    """Make a recorded SHOW CREATE TABLE statement safe to replay.

    The DDL came straight from the source MySQL server, so types, indexes,
    UNIQUE keys, foreign keys, charsets and AUTO_INCREMENT all survive intact.
    Only the baked-in AUTO_INCREMENT counter is dropped, since InnoDB derives it
    from the restored rows.
    """
    ddl = re.sub(r'\s*AUTO_INCREMENT=\d+', '', mysql_ddl, flags=re.IGNORECASE)
    ddl = re.sub(
        r'(?is)^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?[^\s(`]+`?\s*\(',
        f'CREATE TABLE `{table_name}` (',
        ddl,
        count=1,
    )
    return ddl.rstrip().rstrip(';')


def get_sqlite_table_schema(sqlite_conn, table_name):
    """Get CREATE TABLE statement for a SQLite table"""
    cursor = sqlite_conn.cursor()
    cursor.execute(f"SELECT sql FROM sqlite_master WHERE type='table' AND name='{table_name}'")
    result = cursor.fetchone()
    cursor.close()
    if result:
        return result[0]
    return None


def _strip_type_quotes(type_name):
    """Normalize odd quoted SQLite type names like '"TEXT"'."""
    if type_name is None:
        return ""
    cleaned = str(type_name).strip()
    while (cleaned.startswith('"') and cleaned.endswith('"')) or (
        cleaned.startswith("'") and cleaned.endswith("'")
    ):
        cleaned = cleaned[1:-1].strip()
    return cleaned


def _map_sqlite_type_to_mysql(sqlite_type):
    """Map SQLite affinity/type declarations to MySQL column types."""
    t = _strip_type_quotes(sqlite_type).upper()

    if not t:
        return "LONGTEXT"
    if "JSON" in t:
        return "JSON"
    if "BOOL" in t:
        return "BOOLEAN"
    if "BIGINT" in t or "INT" in t:
        return "BIGINT"
    if "DOUBLE" in t or "REAL" in t or "FLOAT" in t:
        return "DOUBLE"
    if "DECIMAL" in t or "NUMERIC" in t:
        return "DECIMAL(38,10)"
    if "BLOB" in t:
        return "LONGBLOB"
    if "DATE" in t and "DATETIME" not in t:
        return "DATE"
    if "TIME" in t or "TIMESTAMP" in t or "DATETIME" in t:
        return "DATETIME"
    if "CHAR" in t or "CLOB" in t or "TEXT" in t or "VARCHAR" in t:
        varchar_match = re.search(r"VARCHAR\s*\(\s*(\d+)\s*\)", t)
        if varchar_match:
            return f"VARCHAR({varchar_match.group(1)})"
        # Keep fixed-width CHAR(n) (e.g. hib_sess_id CHAR(36)) indexable rather
        # than widening it to LONGTEXT, which cannot be part of a key.
        char_match = re.search(r"\bCHAR\s*\(\s*(\d+)\s*\)", t)
        if char_match:
            return f"CHAR({char_match.group(1)})"
        return "LONGTEXT"

    return "LONGTEXT"


# MySQL rejects DEFAULT on these types outright (error 1101).
_NO_DEFAULT_TYPES = ("TEXT", "BLOB", "JSON", "GEOMETRY")


def _format_default_for_mysql(default_value, mysql_type=""):
    """Convert SQLite default value syntax to a safe MySQL default clause when possible."""
    if default_value is None:
        return ""

    d = str(default_value).strip()
    if not d:
        return ""

    # SQLite stores the *text* "NULL" as the default expression for a nullable
    # column. Quoting it produced DEFAULT 'NULL' -- a literal four-character
    # string, and an outright CREATE TABLE error on TEXT/JSON columns.
    if d.upper() == "NULL":
        return ""

    # Skip SQLite-specific expression defaults (e.g. strftime(...))
    if "strftime(" in d.lower() or d.startswith("("):
        return ""

    # LONGTEXT/JSON/BLOB columns cannot carry a default at all.
    if any(t in mysql_type.upper() for t in _NO_DEFAULT_TYPES):
        return ""

    # Keep CURRENT_* style expressions unquoted
    upper = d.upper()
    if upper in {"CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME"}:
        return f" DEFAULT {upper}"

    # Numeric defaults
    if re.fullmatch(r"[-+]?\d+(\.\d+)?", d):
        return f" DEFAULT {d}"

    # Preserve already-quoted strings; otherwise quote as string.
    if (d.startswith("'") and d.endswith("'")) or (d.startswith('"') and d.endswith('"')):
        value = d[1:-1].replace("'", "''")
    else:
        value = d.replace("'", "''")
    return f" DEFAULT '{value}'"


def build_mysql_schema_from_sqlite_table(sqlite_conn, table_name):
    """Build a MySQL CREATE TABLE statement using SQLite PRAGMA metadata."""
    cursor = sqlite_conn.cursor()
    try:
        cursor.execute(f"PRAGMA table_info(`{table_name}`)")
        columns = cursor.fetchall()
    finally:
        cursor.close()

    if not columns:
        return None

    col_defs = []
    pk_cols = []

    # PRAGMA table_info columns: cid, name, type, notnull, dflt_value, pk
    for _, col_name, col_type, not_null, default_value, pk in columns:
        mysql_type = _map_sqlite_type_to_mysql(col_type)
        escaped_col = col_name.replace('`', '``')

        definition = f"`{escaped_col}` {mysql_type}"
        if not_null:
            definition += " NOT NULL"
        definition += _format_default_for_mysql(default_value, mysql_type)

        col_defs.append(definition)
        if pk:
            pk_cols.append((int(pk), escaped_col, mysql_type))

    pk_cols.sort(key=lambda x: x[0])

    if len(pk_cols) == 1:
        _, pk_name, pk_type = pk_cols[0]
        # A single integer PK must be NOT NULL for MySQL, but it is NOT made
        # AUTO_INCREMENT: most entities here allocate ids from a Hibernate *_seq
        # table, and forcing AUTO_INCREMENT changes how ids are issued.
        if any(t in pk_type.upper() for t in ["INT", "BIGINT"]):
            for i, col_def in enumerate(col_defs):
                if col_def.startswith(f"`{pk_name}` ") and "NOT NULL" not in col_def:
                    col_defs[i] = col_def + " NOT NULL"
                    break
        elif "LONGTEXT" in pk_type.upper():
            # MySQL cannot index a LONGTEXT primary key without a prefix length.
            for i, col_def in enumerate(col_defs):
                if col_def.startswith(f"`{pk_name}` "):
                    col_defs[i] = f"`{pk_name}` VARCHAR(255) NOT NULL"
                    break
        col_defs.append(f"PRIMARY KEY (`{pk_name}`)")
    elif len(pk_cols) > 1:
        # Composite keys have the same LONGTEXT problem (hib_sess_id, etc.).
        for idx, (_, name, ptype) in enumerate(pk_cols):
            if "LONGTEXT" in ptype.upper():
                for i, col_def in enumerate(col_defs):
                    if col_def.startswith(f"`{name}` "):
                        col_defs[i] = f"`{name}` VARCHAR(255) NOT NULL"
                        break
                pk_cols[idx] = (pk_cols[idx][0], name, "VARCHAR(255)")
        pk_expr = ", ".join(f"`{name}`" for _, name, _ in pk_cols)
        col_defs.append(f"PRIMARY KEY ({pk_expr})")

    escaped_table = table_name.replace('`', '``')
    return (
        f"CREATE TABLE `{escaped_table}` ("
        + ", ".join(col_defs)
        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
    )


def adapt_sqlite_to_mysql_schema(sqlite_schema, table_name):
    """Adapt SQLite CREATE TABLE statement to MySQL-compatible format"""
    if not sqlite_schema:
        return None
    
    mysql_schema = sqlite_schema
    
    # Basic type conversions
    import re

    # Keep Hibernate session ids indexable in MySQL. SQLite backups may store
    # them as TEXT, but MySQL cannot use TEXT in a PRIMARY KEY without a length.
    mysql_schema = re.sub(
        r'([`"]?hib_sess_id[`"]?\s+)(?:TEXT|VARCHAR\s*\(\s*\d+\s*\))\b',
        r'\1CHAR(36)',
        mysql_schema,
        flags=re.IGNORECASE,
    )

    mysql_schema = re.sub(
        r'(?is)^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?([`"]?)[^\s(`"]+\1\s*\(',
        f'CREATE TABLE `{table_name}` (',
        mysql_schema,
        count=1,
    )
    
    # Convert JSONB (PostgreSQL/SQLite) to MySQL JSON type
    mysql_schema = re.sub(r'\bJSONB\b', 'JSON', mysql_schema, flags=re.IGNORECASE)
    mysql_schema = re.sub(r'\bCLOB\b', 'LONGTEXT', mysql_schema, flags=re.IGNORECASE)
    
    # Convert INTEGER to appropriate MySQL type (keep as INT for now)
    # Convert TEXT to appropriate MySQL type
    mysql_schema = re.sub(r'\bINTEGER\b', 'BIGINT', mysql_schema, flags=re.IGNORECASE)
    mysql_schema = re.sub(r'\bTEXT\b', 'TEXT', mysql_schema, flags=re.IGNORECASE)
    mysql_schema = re.sub(r'\bBLOB\b', 'LONGBLOB', mysql_schema, flags=re.IGNORECASE)
    mysql_schema = re.sub(r'\bREAL\b', 'DOUBLE', mysql_schema, flags=re.IGNORECASE)
    
    # Add MySQL-specific options
    mysql_schema = mysql_schema.rstrip(';')
    mysql_schema += " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"

    mysql_schema = re.sub(
        r'\bPRIMARY\s+KEY\s+AUTOINCREMENT\b',
        'AUTO_INCREMENT PRIMARY KEY',
        mysql_schema,
        flags=re.IGNORECASE,
    )

    mysql_schema = re.sub(
        r'([`"]?hib_sess_id[`"]?\s+)TEXT\b',
        r'\1CHAR(36)',
        mysql_schema,
        flags=re.IGNORECASE,
    )
    
    return mysql_schema


def drop_all_tables(mysql_conn):
    """Drop all tables in MySQL database"""
    cursor = mysql_conn.cursor()
    
    try:
        # Disable foreign key checks
        cursor.execute("SET FOREIGN_KEY_CHECKS = 0")
        
        # Get all tables
        cursor.execute("SHOW TABLES")
        tables = [table[0] for table in cursor.fetchall()]
        
        if tables:
            print(f"\nDropping {len(tables)} existing tables...")
            for table in tables:
                try:
                    cursor.execute(f"DROP TABLE IF EXISTS `{table}`")
                    print(f"  Dropped table: {table}")
                except Error as e:
                    print(f"  Warning: Could not drop table '{table}': {e}")
            
            mysql_conn.commit()
        else:
            print("\nNo existing tables to drop")

        # Foreign key checks are deliberately left disabled: the caller keeps
        # them off for the whole restore and re-enables them at the end.

    finally:
        cursor.close()


def get_mysql_columns(mysql_conn, table_name):
    """Column names of a table that already exists in MySQL."""
    cursor = mysql_conn.cursor()
    try:
        cursor.execute(f"SHOW COLUMNS FROM `{table_name}`")
        return [row[0] for row in cursor.fetchall()]
    finally:
        cursor.close()


def copy_table_data(sqlite_conn, mysql_conn, table_name, target_columns=None):
    """Copy data from a SQLite table into MySQL.

    *target_columns* restricts the insert to columns that exist on the MySQL
    side, which is what makes a schema change survivable: a column added by new
    code takes its default, and a column dropped by new code is reported rather
    than aborting the load.

    Returns (source_rows, copied_rows, note). A shortfall is never swallowed.
    """
    sqlite_cursor = sqlite_conn.cursor()
    mysql_cursor = mysql_conn.cursor()

    try:
        sqlite_cursor.execute(f"SELECT * FROM `{table_name}`")
        all_columns = [desc[0] for desc in sqlite_cursor.description]
        rows = sqlite_cursor.fetchall()
        source_rows = len(rows)

        note = None
        if target_columns is None:
            columns = all_columns
            indexes = list(range(len(all_columns)))
        else:
            target_set = {c.lower() for c in target_columns}
            keep = [(i, c) for i, c in enumerate(all_columns) if c.lower() in target_set]
            dropped = [c for c in all_columns if c.lower() not in target_set]
            added = [c for c in target_columns if c.lower() not in {a.lower() for a in all_columns}]
            indexes = [i for i, _ in keep]
            columns = [c for _, c in keep]
            if dropped or added:
                parts = []
                if dropped:
                    parts.append(f"columns not in new schema (data dropped): {', '.join(dropped)}")
                if added:
                    parts.append(f"new columns left at default: {', '.join(added)}")
                note = "; ".join(parts)

        if not columns:
            return source_rows, 0, "no columns in common with the target table"

        if source_rows == 0:
            print(f"  Table '{table_name}': 0 rows (empty)")
            return 0, 0, note

        rows = [tuple(row[i] for i in indexes) for row in rows]

        placeholders = ','.join(['%s' for _ in columns])
        columns_str = ','.join([f'`{col}`' for col in columns])
        insert_sql = f"INSERT INTO `{table_name}` ({columns_str}) VALUES ({placeholders})"

        try:
            mysql_cursor.executemany(insert_sql, rows)
            mysql_conn.commit()
            copied = source_rows
        except Error as bulk_error:
            # Retry row by row so a handful of bad rows cannot discard the whole
            # table. Duplicate keys used to skip every row here, silently.
            mysql_conn.rollback()
            print(f"  Bulk insert failed ({bulk_error}); retrying row by row...")
            copied = 0
            first_error = None
            for row in rows:
                try:
                    mysql_cursor.execute(insert_sql, row)
                    copied += 1
                except Error as row_error:
                    if first_error is None:
                        first_error = row_error
            mysql_conn.commit()
            if first_error is not None:
                print(f"  First row error: {first_error}")

        if copied != source_rows:
            print(f"  Table '{table_name}': {copied}/{source_rows} rows restored  <-- INCOMPLETE")
        else:
            print(f"  Table '{table_name}': {copied} rows restored")

        return source_rows, copied, note

    except Error as e:
        print(f"  Error copying table '{table_name}': {e}")
        mysql_conn.rollback()
        return -1, 0, str(e)
    finally:
        sqlite_cursor.close()
        mysql_cursor.close()


def safety_dump(host, port, user, password, database):
    """mysqldump the current MySQL database before anything is dropped.

    Returns the dump path, or None when mysqldump is unavailable. The caller
    refuses to continue without one unless explicitly overridden.
    """
    import shutil
    import subprocess

    if shutil.which("mysqldump") is None:
        return None

    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    dump_path = BACKUP_DIR / f"predrop_{database}_{timestamp}.sql"

    cmd = [
        "mysqldump",
        f"--host={host}", f"--port={port}", f"--user={user}",
        "--single-transaction", "--routines", "--triggers", "--events",
        "--set-gtid-purged=OFF", "--column-statistics=0",
        database,
    ]
    env = dict(os.environ, MYSQL_PWD=password)

    print(f"\nTaking a pre-drop safety dump: {dump_path}")
    with open(dump_path, "w") as out:
        result = subprocess.run(cmd, stdout=out, stderr=subprocess.PIPE, env=env)

    if result.returncode != 0:
        stderr = result.stderr.decode(errors="replace")
        # --column-statistics / --set-gtid-purged are not on every client build.
        print(f"  mysqldump failed: {stderr.strip()[:300]}")
        print("  Retrying without optional flags...")
        cmd = [
            "mysqldump", f"--host={host}", f"--port={port}", f"--user={user}",
            "--single-transaction", "--routines", "--triggers", database,
        ]
        with open(dump_path, "w") as out:
            result = subprocess.run(cmd, stdout=out, stderr=subprocess.PIPE, env=env)
        if result.returncode != 0:
            print(f"  mysqldump failed again: {result.stderr.decode(errors='replace').strip()[:300]}")
            dump_path.unlink(missing_ok=True)
            return None

    print(f"  Safety dump written ({dump_path.stat().st_size} bytes)")
    print(f"  Roll back with: mysql -h {host} -P {port} -u {user} -p {database} < {dump_path}")
    return dump_path


def restore_sqlite_to_mysql(backup_file, host, port, user, password, database,
                            force=False, keep_target_schema=False, skip_safety_dump=False):
    """Restore a SQLite backup file into MySQL.

    Schema source, in order of preference:
      1. keep_target_schema=True  - leave the MySQL tables exactly as they are
         (used when Hibernate has just generated the new schema) and load data
         into the columns the two sides have in common.
      2. The MySQL DDL recorded by mysqlbackup.py - an exact rebuild of the
         source schema, indexes, UNIQUE keys and foreign keys included.
      3. SQLite PRAGMA metadata - lossy fallback for older backups.
    """
    print_header("SQLite to MySQL Restore")

    # Validate backup file
    backup_path = Path(backup_file)
    if not backup_path.exists():
        print(f"Error: Backup file not found: {backup_file}")
        sys.exit(1)

    print(f"Backup file: {backup_path}")
    print(f"Target MySQL: {user}@{host}:{port}/{database}")

    # Connect to SQLite backup
    print("\nConnecting to SQLite backup...")
    sqlite_conn = sqlite3.connect(str(backup_path))

    # Connect to MySQL
    print("Connecting to MySQL...")
    mysql_conn = get_mysql_connection(host, port, user, password, database)

    try:
        metadata = load_backup_metadata(sqlite_conn)
        if keep_target_schema:
            schema_source = "the existing MySQL schema (Hibernate-generated)"
        elif metadata:
            schema_source = f"recorded MySQL DDL ({len(metadata)} tables)"
        else:
            schema_source = "SQLite PRAGMA metadata (lossy fallback)"
        print(f"Schema source: {schema_source}")

        if not metadata and not keep_target_schema:
            print("\nWARNING: this backup carries no recorded MySQL DDL, so column")
            print("types, indexes, UNIQUE keys and foreign keys will be approximated.")
            print("Prefer a backup taken with the current mysqlbackup.py, or use")
            print("--keep-target-schema after letting Hibernate build the schema.")

        # Get confirmation
        if not force:
            print("\nWARNING: This will replace ALL data in the MySQL database!")
            if not keep_target_schema:
                print("All existing tables and data will be dropped and replaced with backup data.")
            else:
                print("All rows in the existing tables will be deleted and replaced.")
            response = input("\nContinue? (yes/no): ").strip().lower()
            if response not in ('yes', 'y'):
                print("Restore cancelled.")
                return
        else:
            print("\nWARNING: Force mode enabled - replacing ALL data in MySQL database!")

        # Always take a rollback point before destroying anything.
        if skip_safety_dump:
            print("\nSkipping the pre-drop safety dump (--skip-safety-dump).")
        else:
            dump_path = safety_dump(host, port, user, password, database)
            if dump_path is None:
                raise RuntimeError(
                    "Could not take a pre-drop safety dump (is mysqldump installed?). "
                    "Refusing to drop production data without a rollback point. "
                    "Pass --skip-safety-dump to override."
                )

        # Get all tables from SQLite
        print("\nFetching table list from backup...")
        tables = get_sqlite_tables(sqlite_conn)
        print(f"Found {len(tables)} tables in backup")

        failed_tables = []
        notes = []
        skipped_tables = []
        restored_tables = 0
        total_source = 0
        total_copied = 0

        # Tables are created and loaded in whatever order the backup lists them,
        # so foreign keys must stay unchecked for the whole run.
        cursor = mysql_conn.cursor()
        cursor.execute("SET FOREIGN_KEY_CHECKS = 0")
        cursor.close()

        try:
            if keep_target_schema:
                # Keep the schema, clear the rows.
                existing = set(list_mysql_tables(mysql_conn))
                print(f"\nClearing {len(existing)} existing tables (schema preserved)...")
                cursor = mysql_conn.cursor()
                for table in existing:
                    cursor.execute(f"DELETE FROM `{table}`")
                mysql_conn.commit()
                cursor.close()
            else:
                drop_all_tables(mysql_conn)

            print("\nRestoring tables...")

            for table_name in tables:
                print(f"\nProcessing table: {table_name}")

                target_columns = None

                if keep_target_schema:
                    try:
                        target_columns = get_mysql_columns(mysql_conn, table_name)
                    except Error:
                        # New code no longer has this table at all.
                        source_rows = sqlite_table_row_count(sqlite_conn, table_name)
                        print(f"  Not present in the new schema; {source_rows} row(s) will not be restored")
                        skipped_tables.append((table_name, source_rows))
                        continue
                else:
                    recorded = metadata.get(table_name)
                    if recorded:
                        mysql_schema = normalize_recorded_ddl(recorded[0], table_name)
                    else:
                        mysql_schema = build_mysql_schema_from_sqlite_table(sqlite_conn, table_name)

                    if not mysql_schema:
                        print(f"  Warning: Could not build schema for '{table_name}', skipping")
                        failed_tables.append((table_name, "missing schema"))
                        continue

                    try:
                        mysql_cursor = mysql_conn.cursor()
                        mysql_cursor.execute(f"DROP TABLE IF EXISTS `{table_name}`")
                        mysql_cursor.execute(mysql_schema)
                        mysql_conn.commit()
                        mysql_cursor.close()
                    except Error as e:
                        print(f"  Error creating table '{table_name}' in MySQL: {e}")
                        print(f"  Schema was: {mysql_schema}")
                        failed_tables.append((table_name, str(e)))
                        continue

                source_rows, copied_rows, note = copy_table_data(
                    sqlite_conn, mysql_conn, table_name, target_columns
                )
                total_source += max(source_rows, 0)
                total_copied += copied_rows
                restored_tables += 1

                if note:
                    notes.append((table_name, note))
                    print(f"  Note: {note}")
                if source_rows != copied_rows:
                    failed_tables.append(
                        (table_name, f"only {copied_rows} of {source_rows} rows restored")
                    )

        finally:
            cursor = mysql_conn.cursor()
            cursor.execute("SET FOREIGN_KEY_CHECKS = 1")
            cursor.close()

        print_header("Restore Summary")
        print(f"Tables processed: {restored_tables}/{len(tables)}")
        print(f"Rows in backup:   {total_source}")
        print(f"Rows restored:    {total_copied}")

        if notes:
            print("\nSchema differences absorbed during the load:")
            for table_name, note in notes:
                print(f"  - {table_name}: {note}")

        if skipped_tables:
            print("\nTables in the backup that the new schema no longer has:")
            for table_name, rows in skipped_tables:
                print(f"  - {table_name}: {rows} row(s) not restored")

        if failed_tables:
            print("\nFailed tables:")
            for table_name, reason in failed_tables:
                print(f"  - {table_name}: {reason}")
            raise RuntimeError(
                f"Restore incomplete: {len(failed_tables)} table(s) failed."
            )

        # Independent check: re-count both sides rather than trusting the tallies.
        print("\nVerifying row counts against the backup...")
        mismatches = verify_row_counts(sqlite_conn, mysql_conn, tables, skipped_tables)
        if mismatches:
            print(f"\n{len(mismatches)} table(s) do not match the backup:")
            for table_name, src, tgt in mismatches:
                print(f"  - {table_name}: backup {src}, MySQL {tgt}")
            raise RuntimeError("Restore verification failed: MySQL does not match the backup.")

        print("All tables verified: MySQL row counts match the backup.")

    finally:
        sqlite_conn.close()
        mysql_conn.close()


def list_mysql_tables(mysql_conn):
    """All table names currently in the MySQL database."""
    cursor = mysql_conn.cursor()
    try:
        cursor.execute("SHOW TABLES")
        return [row[0] for row in cursor.fetchall()]
    finally:
        cursor.close()


def sqlite_table_row_count(sqlite_conn, table_name):
    """Row count for a table in the SQLite backup."""
    cursor = sqlite_conn.cursor()
    try:
        cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
        return int(cursor.fetchone()[0])
    except sqlite3.Error:
        return -1
    finally:
        cursor.close()


def verify_row_counts(sqlite_conn, mysql_conn, tables, skipped_tables):
    """Compare backup and MySQL row counts. Returns a list of mismatches."""
    skipped = {name for name, _ in skipped_tables}
    mismatches = []

    for table_name in tables:
        if table_name in skipped:
            continue
        source = sqlite_table_row_count(sqlite_conn, table_name)
        cursor = mysql_conn.cursor()
        try:
            cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
            target = int(cursor.fetchone()[0])
        except Error:
            target = -1
        finally:
            cursor.close()

        if source != target:
            mismatches.append((table_name, source, target))

    return mismatches


def main():
    """Main restore process"""
    parser = argparse.ArgumentParser(description='Restore SQLite backup to MySQL database')
    parser.add_argument('--backup-file', default=None,
                       help='Path to SQLite backup file (default: use most recent backup)')
    parser.add_argument('--force', action='store_true',
                       help='Skip confirmation prompt')
    parser.add_argument('--keep-target-schema', action='store_true',
                       help='Do not recreate tables; load data into the schema already '
                            'in MySQL (use after Hibernate has generated the new schema)')
    parser.add_argument('--skip-safety-dump', action='store_true',
                       help='Do not mysqldump the target before dropping it (not recommended)')

    args = parser.parse_args()
    
    # Get MySQL configuration from .env
    host, port, username, password, database = get_mysql_config()
    
    # Determine backup file
    if args.backup_file:
        backup_file = Path(args.backup_file)
        # If relative path, resolve from project root
        if not backup_file.is_absolute():
            backup_file = PROJECT_ROOT / backup_file
    else:
        backup_file = find_latest_backup()
        print(f"Using most recent backup: {backup_file}")
    
    try:
        restore_sqlite_to_mysql(
            str(backup_file),
            host,
            port,
            username,
            password,
            database,
            force=args.force,
            keep_target_schema=args.keep_target_schema,
            skip_safety_dump=args.skip_safety_dump,
        )


    except KeyboardInterrupt:
        print("\n\nRestore interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
