#!/usr/bin/env python3
"""
sqlite_migrate.py - Backup and restore for a SQLite-backed Spring deployment.

Spring's production data currently lives in a single SQLite file
(volumes/sqlite.db) because DB_URL is unset, so application.properties falls
back to `jdbc:sqlite:volumes/sqlite.db?journal_mode=WAL`. This module is the
SQLite equivalent of mysqlbackup.py / mysqlrestore.py, with the same guarantees:
a backup that verifies its own completeness, and a restore that loads into the
new schema column-by-column and re-counts everything afterwards.

Two things it does NOT do that a naive approach would get wrong:

  * It never `cp`s a live WAL database. A plain copy of sqlite.db taken while
    Spring is running can miss everything sitting in the -wal file, or capture a
    torn page. This uses sqlite3's online backup API, which is consistent even
    against an open database.

  * It never assumes the schema is unchanged. db_init.py rebuilds the schema
    from the JPA entities, so the restore loads only the columns the backup and
    the new schema have in common, and reports the rest.
"""

from __future__ import annotations

import sqlite3
import sys
from datetime import datetime
from pathlib import Path

from mysql_common import META_TABLE, PROJECT_ROOT, print_header

DB_FILE = PROJECT_ROOT / "volumes" / "sqlite.db"
BACKUP_DIR = PROJECT_ROOT / "volumes" / "backups"

# Tables SQLite maintains itself; never ours to copy.
INTERNAL_PREFIX = "sqlite_"


def ensure_writable(directory: Path):
    """Fail early and usefully if the backup directory cannot be written.

    Docker bind-mounts under volumes/ are often created root-owned, which
    surfaces much later as a bare 'unable to open database file'.
    """
    try:
        directory.mkdir(parents=True, exist_ok=True)
        probe = directory / ".write_probe"
        probe.touch()
        probe.unlink()
    except PermissionError:
        print(f"Error: cannot write to {directory}")
        print("  This is usually a Docker-created directory owned by root. Fix with:")
        print(f"    sudo chown -R $(id -u):$(id -g) {directory.parent}")
        sys.exit(1)
    except OSError as e:
        print(f"Error: cannot use {directory}: {e}")
        sys.exit(1)


def list_tables(conn):
    """User tables in a SQLite database, excluding internal and meta tables."""
    cursor = conn.cursor()
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table'")
    names = [row[0] for row in cursor.fetchall()]
    cursor.close()
    return [n for n in names if not n.startswith(INTERNAL_PREFIX) and n != META_TABLE]


def table_columns(conn, table_name):
    """Column names of a table."""
    cursor = conn.cursor()
    try:
        cursor.execute(f"PRAGMA table_info(`{table_name}`)")
        return [row[1] for row in cursor.fetchall()]
    finally:
        cursor.close()


def row_count(conn, table_name):
    """Row count for a table, or -1 if it cannot be read."""
    cursor = conn.cursor()
    try:
        cursor.execute(f"SELECT COUNT(*) FROM `{table_name}`")
        return int(cursor.fetchone()[0])
    except sqlite3.Error:
        return -1
    finally:
        cursor.close()


def table_ddl(conn, table_name):
    """The CREATE TABLE statement for a table."""
    cursor = conn.cursor()
    try:
        cursor.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table_name,)
        )
        result = cursor.fetchone()
        return result[0] if result else ""
    finally:
        cursor.close()


# ── Backup ─────────────────────────────────────────────────────────────────────

def backup_sqlite(db_file=None, backup_dir=None):
    """Take a consistent backup of the live SQLite database.

    Returns the backup path. Raises RuntimeError if any table failed to copy.
    """
    db_file = Path(db_file) if db_file else DB_FILE
    backup_dir = Path(backup_dir) if backup_dir else BACKUP_DIR

    print_header("SQLite Backup")

    if not db_file.exists():
        print(f"Error: database file not found: {db_file}")
        sys.exit(1)

    ensure_writable(backup_dir)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_file = backup_dir / f"sqlite_backup_{timestamp}.db"

    print(f"Source: {db_file}")
    print(f"Target: {backup_file}\n")

    # Online backup API: consistent even while Spring holds the database open,
    # and it folds the -wal contents in rather than leaving them behind.
    source = sqlite3.connect(f"file:{db_file}?mode=ro", uri=True)
    dest = sqlite3.connect(str(backup_file))
    try:
        source.backup(dest)
        dest.commit()
    finally:
        source.close()
        dest.close()

    # Verify the copy table by table against the live database.
    live = sqlite3.connect(f"file:{db_file}?mode=ro", uri=True)
    copy = sqlite3.connect(str(backup_file))
    failures = []
    total_rows = 0

    try:
        tables = list_tables(live)
        print(f"Verifying {len(tables)} tables...\n")

        copy.execute(f"DROP TABLE IF EXISTS `{META_TABLE}`")
        copy.execute(
            f"CREATE TABLE `{META_TABLE}` ("
            "table_name TEXT PRIMARY KEY, mysql_ddl TEXT NOT NULL, source_rows INTEGER NOT NULL)"
        )

        for table_name in tables:
            source_rows = row_count(live, table_name)
            copied_rows = row_count(copy, table_name)
            total_rows += max(source_rows, 0)

            copy.execute(
                f"INSERT OR REPLACE INTO `{META_TABLE}` "
                "(table_name, mysql_ddl, source_rows) VALUES (?, ?, ?)",
                (table_name, table_ddl(live, table_name), source_rows),
            )

            if source_rows != copied_rows:
                print(f"  {table_name}: {copied_rows}/{source_rows} rows  <-- INCOMPLETE")
                failures.append((table_name, f"{copied_rows} of {source_rows} rows"))
            else:
                print(f"  {table_name}: {source_rows} rows")

        copy.commit()
    finally:
        live.close()
        copy.close()

    print_header("Backup Summary")
    print(f"Backup file: {backup_file}")
    print(f"Tables:      {len(tables)}")
    print(f"Rows:        {total_rows}")

    if failures:
        print(f"\n{len(failures)} table(s) did NOT back up completely:")
        for table_name, reason in failures:
            print(f"  - {table_name}: {reason}")
        raise RuntimeError(
            f"Backup incomplete: {len(failures)} table(s) failed. "
            "This backup must not be used as a migration source."
        )

    print("\nAll tables and all rows accounted for.")
    return backup_file


def find_latest_backup(backup_dir=None):
    """Most recent sqlite_backup_*.db file."""
    backup_dir = Path(backup_dir) if backup_dir else BACKUP_DIR
    if not backup_dir.exists():
        print(f"Error: backup directory not found: {backup_dir}")
        sys.exit(1)

    candidates = sorted(
        backup_dir.glob("sqlite_backup_*.db"), key=lambda p: p.stat().st_mtime, reverse=True
    )
    if not candidates:
        print(f"Error: no SQLite backups found in {backup_dir}")
        sys.exit(1)
    return candidates[0]


# ── Restore ────────────────────────────────────────────────────────────────────

def restore_sqlite(backup_file, db_file=None, force=False):
    """Load a backup's data into the schema currently in db_file.

    The target keeps its schema -- db_init.py has just rebuilt it from the JPA
    entities -- and only the columns both sides share are copied. Tables and
    columns that the new schema no longer has are reported, never silently
    dropped.
    """
    backup_file = Path(backup_file)
    db_file = Path(db_file) if db_file else DB_FILE

    print_header("SQLite Restore")

    if not backup_file.exists():
        print(f"Error: backup file not found: {backup_file}")
        sys.exit(1)
    if not db_file.exists():
        print(f"Error: target database not found: {db_file}")
        print("  Run `python3 scripts/db_migrate.py init` first to build the new schema.")
        sys.exit(1)

    print(f"Backup: {backup_file}")
    print(f"Target: {db_file}")

    if not force:
        print("\nWARNING: this replaces ALL rows in the target database.")
        print("The schema currently in the target is kept as-is.")
        if input("\nContinue? (yes/no): ").strip().lower() not in ("yes", "y"):
            print("Restore cancelled.")
            return

    source = sqlite3.connect(f"file:{backup_file}?mode=ro", uri=True)
    target = sqlite3.connect(str(db_file))

    failures = []
    notes = []
    skipped = []
    total_source = 0
    total_copied = 0

    try:
        target.execute("PRAGMA foreign_keys = OFF")

        backup_tables = list_tables(source)
        target_tables = set(list_tables(target))
        print(f"\nTables in backup: {len(backup_tables)}")
        print(f"Tables in target: {len(target_tables)}\n")

        for table_name in backup_tables:
            source_rows = row_count(source, table_name)

            if table_name not in target_tables:
                print(f"  {table_name}: not in the new schema; {source_rows} row(s) NOT restored")
                skipped.append((table_name, source_rows))
                continue

            source_cols = table_columns(source, table_name)
            target_cols = table_columns(target, table_name)
            target_set = {c.lower() for c in target_cols}

            keep = [(i, c) for i, c in enumerate(source_cols) if c.lower() in target_set]
            dropped = [c for c in source_cols if c.lower() not in target_set]
            added = [c for c in target_cols if c.lower() not in {s.lower() for s in source_cols}]

            if not keep:
                print(f"  {table_name}: no columns in common; {source_rows} row(s) NOT restored")
                skipped.append((table_name, source_rows))
                continue

            if dropped or added:
                parts = []
                if dropped:
                    parts.append(f"columns gone from the new schema (data dropped): {', '.join(dropped)}")
                if added:
                    parts.append(f"new columns left at default: {', '.join(added)}")
                notes.append((table_name, "; ".join(parts)))

            indexes = [i for i, _ in keep]
            columns = [c for _, c in keep]

            target.execute(f"DELETE FROM `{table_name}`")

            cursor = source.cursor()
            cursor.execute(f"SELECT * FROM `{table_name}`")
            rows = [tuple(row[i] for i in indexes) for row in cursor.fetchall()]
            cursor.close()

            total_source += max(source_rows, 0)

            if rows:
                placeholders = ",".join("?" for _ in columns)
                column_sql = ",".join(f"`{c}`" for c in columns)
                insert = f"INSERT INTO `{table_name}` ({column_sql}) VALUES ({placeholders})"
                try:
                    target.executemany(insert, rows)
                    copied = len(rows)
                except sqlite3.Error as bulk_error:
                    print(f"  Bulk insert failed on {table_name} ({bulk_error}); retrying row by row...")
                    copied = 0
                    first_error = None
                    for row in rows:
                        try:
                            target.execute(insert, row)
                            copied += 1
                        except sqlite3.Error as row_error:
                            if first_error is None:
                                first_error = row_error
                    if first_error is not None:
                        print(f"  First row error: {first_error}")
            else:
                copied = 0

            target.commit()
            total_copied += copied

            if copied != source_rows:
                print(f"  {table_name}: {copied}/{source_rows} rows  <-- INCOMPLETE")
                failures.append((table_name, f"{copied} of {source_rows} rows restored"))
            else:
                print(f"  {table_name}: {copied} rows")

        target.commit()
        target.execute("PRAGMA foreign_keys = ON")

        print_header("Restore Summary")
        print(f"Rows in backup:  {total_source}")
        print(f"Rows restored:   {total_copied}")

        if notes:
            print("\nSchema differences absorbed during the load:")
            for table_name, note in notes:
                print(f"  - {table_name}: {note}")

        if skipped:
            print("\nTables in the backup that the new schema no longer has:")
            for table_name, rows_count in skipped:
                print(f"  - {table_name}: {rows_count} row(s) not restored")

        if failures:
            print("\nFailed tables:")
            for table_name, reason in failures:
                print(f"  - {table_name}: {reason}")
            raise RuntimeError(f"Restore incomplete: {len(failures)} table(s) failed.")

        # Independent re-count rather than trusting the running tallies.
        print("\nVerifying row counts against the backup...")
        mismatches = []
        skipped_names = {name for name, _ in skipped}
        for table_name in backup_tables:
            if table_name in skipped_names:
                continue
            src = row_count(source, table_name)
            tgt = row_count(target, table_name)
            if src != tgt:
                mismatches.append((table_name, src, tgt))

        if mismatches:
            print(f"\n{len(mismatches)} table(s) do not match the backup:")
            for table_name, src, tgt in mismatches:
                print(f"  - {table_name}: backup {src}, target {tgt}")
            raise RuntimeError("Restore verification failed: target does not match the backup.")

        print("All tables verified: target row counts match the backup.")

    finally:
        source.close()
        target.close()
