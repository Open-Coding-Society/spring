import os
import json
import sqlite3
from glob import glob

def get_table_name_from_path(path):
    # Extract table name from backup path (e.g., backups/bank/bank_backup_2026-05-21_09-58-13.json -> bank)
    return os.path.basename(os.path.dirname(path))

def get_json_files():
    # Find all JSON backup files in backups/*/*
    return glob('backups/*/*.json') + glob('volumes/backups/*.json')

def insert_data(conn, table, data):
    if not data:
        return
    columns = data[0].keys()
    placeholders = ','.join(['?'] * len(columns))
    sql = f"INSERT OR IGNORE INTO {table} ({','.join(columns)}) VALUES ({placeholders})"
    for row in data:
        values = [row.get(col) for col in columns]
        try:
            conn.execute(sql, values)
        except sqlite3.Error as e:
            print(f"Error inserting into {table}: {e}\nRow: {row}")
    conn.commit()

def migrate():
    db_path = 'volumes/sqlite.db'
    if not os.path.exists(db_path):
        print(f"Database not found at {db_path}")
        return
    conn = sqlite3.connect(db_path)
    files = get_json_files()
    for file in files:
        table = get_table_name_from_path(file)
        with open(file, 'r') as f:
            try:
                data = json.load(f)
                if isinstance(data, dict):
                    data = [data]
                if not isinstance(data, list):
                    print(f"Unexpected data format in {file}")
                    continue
                insert_data(conn, table, data)
                print(f"Migrated {len(data)} records to {table} from {file}")
            except Exception as e:
                print(f"Failed to process {file}: {e}")
    conn.close()

if __name__ == "__main__":
    migrate()
