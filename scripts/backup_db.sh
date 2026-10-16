#!/bin/bash


set -e

BACKUP_DIR="./backups/database"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="cabinet_backup_${TIMESTAMP}.sql"
CONTAINER_NAME="cabi_db"
DB_USER="user"
DB_PASSWORD="$DB_PASSWORD"
DB_NAME="cabi"

mkdir -p "$BACKUP_DIR"

echo "📦 Starting database backup..."
echo "📅 Timestamp: $TIMESTAMP"

docker exec "$CONTAINER_NAME" mysqldump \
  -u"$DB_USER" \
  -p"$DB_PASSWORD" \
  --single-transaction \
  --routines \
  --triggers \
  --events \
  "$DB_NAME" > "$BACKUP_DIR/$BACKUP_FILE"

gzip "$BACKUP_DIR/$BACKUP_FILE"
BACKUP_FILE_GZ="${BACKUP_FILE}.gz"

echo "✅ Backup completed: $BACKUP_DIR/$BACKUP_FILE_GZ"
echo "📊 Backup size: $(du -h "$BACKUP_DIR/$BACKUP_FILE_GZ" | cut -f1)"

echo "🧹 Cleaning up old backups (older than 7 days)..."
find "$BACKUP_DIR" -name "cabinet_backup_*.sql.gz" -mtime +7 -delete
echo "✅ Cleanup completed"

echo ""
echo "📂 Recent backups:"
ls -lh "$BACKUP_DIR" | tail -5

exit 0
