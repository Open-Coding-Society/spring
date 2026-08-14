#!/usr/bin/env python3
"""
db_migrate.py - One entry point for every Spring database migration operation.

    python3 scripts/db_migrate.py status     # what am I pointed at, and what is there
    python3 scripts/db_migrate.py check      # prove the schema translation round-trips
    python3 scripts/db_migrate.py backup     # MySQL -> verified local SQLite backup
    python3 scripts/db_migrate.py init       # rebuild the schema with Hibernate
    python3 scripts/db_migrate.py restore    # SQLite backup -> MySQL

This replaces having to remember which of several similarly-named scripts is
the current one. `backup` and `restore` are the same code paths as
mysqlbackup.py and mysqlrestore.py -- those still work and are not going away,
this just puts one door in front of them.

Full production sequence (see README.md for the gates between each step):

    python3 scripts/db_migrate.py backup
    docker compose down && git pull
    python3 scripts/db_migrate.py init
    python3 scripts/db_migrate.py restore --keep-target-schema --backup-file <path>
    docker compose up -d --build
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from mysql_common import (  # noqa: E402
    BACKUP_DIR,
    describe_target,
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
    print(f"Target: {describe_target()}")

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

    print("\nLocal backups:")
    backups = sorted(BACKUP_DIR.glob("mysql_backup_*.db"), key=lambda p: p.stat().st_mtime, reverse=True)
    if not backups:
        print("  none -- run `db_migrate.py backup` first")
    for path in backups[:5]:
        stamp = datetime.fromtimestamp(path.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
        print(f"  {stamp}  {path.stat().st_size:>12,} bytes  {path.name}")

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
    """MySQL -> verified local SQLite backup."""
    import mysqlbackup
    mysqlbackup.main()
    return 0


def cmd_init(_args):
    """Rebuild the schema on the configured target with Hibernate."""
    import db_init
    db_init.main()
    return 0


def cmd_restore(args):
    """SQLite backup -> MySQL."""
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
                         help="load into the schema already in MySQL instead of recreating it")
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
