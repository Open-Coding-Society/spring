#!/usr/bin/env python3
"""
db_migrate.py - One entry point for every Spring database migration operation.

    python3 scripts/db_migrate.py status     # what am I pointed at, and what is there
    python3 scripts/db_migrate.py check      # prove the schema translation round-trips
    python3 scripts/db_migrate.py backup     # verified backup of the live database
    python3 scripts/db_migrate.py init       # rebuild the schema with Hibernate
    python3 scripts/db_migrate.py restore    # load a backup back in

Every command follows whatever the app itself is configured to use. If DB_URL is
set to a jdbc:mysql: URL the target is MySQL; otherwise application.properties
falls back to `jdbc:sqlite:volumes/sqlite.db` and the target is that file. Run
`status` first if you are not certain which one you are about to touch.

Full production sequence (see README.md for the gates between each step):

    python3 scripts/db_migrate.py status                 # confirm the target
    python3 scripts/db_migrate.py backup                 # must exit 0
    docker compose down && git pull
    python3 scripts/db_migrate.py init                   # rebuild schema (destructive)
    python3 scripts/db_migrate.py restore --backup-file <path>
    docker compose up -d --build

On MySQL the restore additionally takes --keep-target-schema; on SQLite the
target schema is always kept, because init has just rebuilt it.
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mysql_common import (  # noqa: E402
    BACKUP_DIR,
    SQLITE_DB_FILE,
    describe_target,
    detect_target,
    get_mysql_config,
    get_mysql_connection,
    print_header,
    read_ddl_from_mysql,
    read_ddl_from_schema_dump,
    roundtrip_report,
)


def cmd_status(_args):
    """Show the configured target and what is currently in it."""
    print_header("Migration Status")

    target = detect_target()
    print(f"Mode:   {target.upper()}")
    print(f"Target: {describe_target()}")

    if target == "sqlite":
        return _status_sqlite()

    host, port, user, password, database = get_mysql_config()
    conn = get_mysql_connection(host, port, user, password, database)
    try:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT table_name, table_rows FROM information_schema.tables "
            "WHERE table_schema = %s ORDER BY table_name",
            (database,),
        )
        rows = cursor.fetchall()
        cursor.close()
    finally:
        conn.close()

    envers = [r for r in rows if str(r[0]).startswith(("HT_", "HTE_"))]
    seqs = [r for r in rows if str(r[0]).endswith("_seq")]

    print(f"\nTables:              {len(rows)}")
    print(f"  Envers audit:      {len(envers)}  (HT_* / HTE_*)")
    print(f"  Hibernate id seqs: {len(seqs)}  (*_seq)")
    print(f"  Application:       {len(rows) - len(envers) - len(seqs)}")
    print("\n(information_schema row counts are InnoDB estimates, not exact.)")

    _print_backups("mysql_backup_*.db")
    return 0


def _print_backups(pattern):
    print("\nLocal backups:")
    if not BACKUP_DIR.exists():
        print("  none -- run `db_migrate.py backup` first")
        return
    backups = sorted(BACKUP_DIR.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)
    if not backups:
        print("  none -- run `db_migrate.py backup` first")
    for path in backups[:5]:
        stamp = datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
        print(f"  {stamp}  {path.stat().st_size:>12,} bytes  {path.name}")


def _status_sqlite():
    """Status for a SQLite-backed deployment."""
    import sqlite3
    import sqlite_migrate

    if not SQLITE_DB_FILE.exists():
        print(f"\nNo database file at {SQLITE_DB_FILE}")
        print("Run `db_migrate.py init` to create one.")
        return 1

    conn = sqlite3.connect(f"file:{SQLITE_DB_FILE}?mode=ro", uri=True)
    try:
        tables = sqlite_migrate.list_tables(conn)
        counts = {t: sqlite_migrate.row_count(conn, t) for t in tables}
        journal = conn.execute("PRAGMA journal_mode").fetchone()[0]
    finally:
        conn.close()

    seqs = [t for t in tables if t.endswith("_seq")]
    envers = [t for t in tables if t.startswith(("HT_", "HTE_"))]
    total_rows = sum(c for c in counts.values() if c > 0)

    print(f"\nFile size:           {SQLITE_DB_FILE.stat().st_size:,} bytes")
    print(f"Journal mode:        {journal}")
    print(f"\nTables:              {len(tables)}")
    print(f"  Envers audit:      {len(envers)}  (HT_* / HTE_*)")
    print(f"  Hibernate id seqs: {len(seqs)}  (*_seq)")
    print(f"  Application:       {len(tables) - len(envers) - len(seqs)}")
    print(f"Rows (exact):        {total_rows:,}")

    populated = sorted(
        ((t, c) for t, c in counts.items() if c > 0 and not t.endswith("_seq")),
        key=lambda kv: kv[1], reverse=True,
    )
    if populated:
        print("\nLargest tables:")
        for name, count in populated[:10]:
            print(f"  {count:>9,}  {name}")

    _print_backups("sqlite_backup_*.db")
    return 0


def cmd_check(args):
    """Verify the two schema translations are still inverses of each other.

    mysqlbackup.py maps MySQL types down to SQLite and mysqlrestore.py maps them
    back up. They live in separate files, so nothing but this command stops them
    drifting apart -- which is exactly how the round trip previously turned every
    VARCHAR and DATETIME column into LONGTEXT.
    """
    print_header("Schema Round-Trip Check")

    if args.live:
        print(f"Source: live MySQL ({describe_target()})")
        statements = read_ddl_from_mysql()
    else:
        dump = Path(args.schema) if args.schema else None
        statements = read_ddl_from_schema_dump(dump)
        print(f"Source: {dump or 'schema_full.txt'}")

    if not statements:
        print("\nNo CREATE TABLE statements found. Pass --live to read from MySQL,")
        print("or --schema <path> to point at a dump file.")
        return 1

    print(f"Tables: {len(statements)}\n")
    ok, findings = roundtrip_report(statements)

    if findings:
        print(f"{len(findings)} problem(s) found:\n")
        for table_name, problem in findings:
            print(f"  {table_name}: {problem}")
        print(f"\n{ok}/{len(statements)} tables round-trip cleanly.")
        print("\nThe two type maps have drifted. Fix them before migrating:")
        print("  adapt_mysql_to_sqlite_schema()        in scripts/mysqlbackup.py")
        print("  build_mysql_schema_from_sqlite_table() in scripts/mysqlrestore.py")
        return 1

    print(f"All {ok} tables round-trip cleanly. The two type maps agree.")
    return 0


def cmd_backup(_args):
    """Back up whichever database the app is configured to use."""
    if detect_target() == "sqlite":
        import sqlite_migrate
        sqlite_migrate.backup_sqlite()
        return 0

    import mysqlbackup
    mysqlbackup.main()
    return 0


def cmd_init(_args):
    """Rebuild the schema on the configured target with Hibernate."""
    import db_init
    db_init.main()
    return 0


def cmd_restore(args):
    """Restore a backup into whichever database the app is configured to use."""
    if detect_target() == "sqlite":
        import sqlite_migrate
        backup_file = Path(args.backup_file) if args.backup_file else sqlite_migrate.find_latest_backup()
        if args.backup_file and not backup_file.is_absolute():
            backup_file = SQLITE_DB_FILE.parent.parent / backup_file
        if not args.backup_file:
            print(f"Using most recent backup: {backup_file}")
        sqlite_migrate.restore_sqlite(backup_file, force=args.force)
        return 0

    import mysqlrestore

    host, port, user, password, database = get_mysql_config()

    if args.backup_file:
        backup_file = Path(args.backup_file)
        if not backup_file.is_absolute():
            backup_file = BACKUP_DIR.parent.parent / backup_file
    else:
        backup_file = mysqlrestore.find_latest_backup()
        print(f"Using most recent backup: {backup_file}")

    mysqlrestore.restore_sqlite_to_mysql(
        str(backup_file), host, port, user, password, database,
        force=args.force,
        keep_target_schema=args.keep_target_schema,
        skip_safety_dump=args.skip_safety_dump,
    )
    return 0


def build_parser():
    parser = argparse.ArgumentParser(
        prog="db_migrate.py",
        description="Spring database migration operations.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("status", help="show the configured target and what is in it")

    check = sub.add_parser("check", help="verify the schema translation round-trips")
    check.add_argument("--live", action="store_true",
                       help="read the schema from the configured MySQL server")
    check.add_argument("--schema", help="path to a CREATE TABLE dump (default: schema_full.txt)")

    sub.add_parser("backup", help="MySQL -> verified local SQLite backup")
    sub.add_parser("init", help="rebuild the schema with Hibernate (destructive)")

    restore = sub.add_parser("restore", help="SQLite backup -> MySQL (destructive)")
    restore.add_argument("--backup-file", help="backup to restore (default: most recent)")
    restore.add_argument("--force", action="store_true", help="skip the confirmation prompt")
    restore.add_argument("--keep-target-schema", action="store_true",
                         help="load into the schema already in MySQL instead of recreating it "
                              "(SQLite targets always keep the target schema)")
    restore.add_argument("--skip-safety-dump", action="store_true",
                         help="do not mysqldump the target first (not recommended)")

    return parser


HANDLERS = {
    "status":  cmd_status,
    "check":   cmd_check,
    "backup":  cmd_backup,
    "init":    cmd_init,
    "restore": cmd_restore,
}


def main():
    args = build_parser().parse_args()
    try:
        return HANDLERS[args.command](args)
    except KeyboardInterrupt:
        print("\n\nInterrupted by user")
        return 1
    except Exception as e:
        print(f"\nAn error occurred: {e}", file=sys.stderr)
        import traceback
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main())
