#!/bin/bash


set -e

CONTAINER_NAME="cabi_db"
DB_USER="user"
DB_PASSWORD="$DB_PASSWORD"
DB_NAME="cabi"

if [ -z "$1" ]; then
  echo "❌ Usage: $0 <backup_file.sql.gz>"
  echo "Example: $0 backups/database/cabinet_backup_20260103_120000.sql.gz"
  exit 1
fi

BACKUP_FILE="$1"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "❌ Backup file not found: $BACKUP_FILE"
  exit 1
fi

echo "⚠️  WARNING: This will restore the database from backup."
echo "📂 Backup file: $BACKUP_FILE"
echo ""
read -p "Are you sure? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
  echo "❌ Restore cancelled"
  exit 0
fi

echo "🔄 Restoring database..."

gunzip -c "$BACKUP_FILE" | docker exec -i "$CONTAINER_NAME" mysql \
  -u"$DB_USER" \
  -p"$DB_PASSWORD" \
  "$DB_NAME"

echo "✅ Database restored successfully!"
exit 0
