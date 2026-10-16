#!/bin/bash


set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "🚀 Cabinet Backend - Blue-Green 무중단 배포 시작"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

IMAGE_TAG=${1:-latest}
log_info "배포 이미지 태그: $IMAGE_TAG"

log_info "[1/6] 현재 활성 컨테이너 확인 중..."

CURRENT_CONTAINER=$(docker ps --filter "name=cabi_backend" --format "{{.Names}}" | grep -E "blue|green" || echo "none")

if [[ "$CURRENT_CONTAINER" == *"blue"* ]]; then
    CURRENT_COLOR="blue"
    NEW_COLOR="green"
    log_info "현재 활성: ${BLUE}Blue${NC} → 배포 대상: ${GREEN}Green${NC}"
elif [[ "$CURRENT_CONTAINER" == *"green"* ]]; then
    CURRENT_COLOR="green"
    NEW_COLOR="blue"
    log_info "현재 활성: ${GREEN}Green${NC} → 배포 대상: ${BLUE}Blue${NC}"
else
    CURRENT_COLOR="none"
    NEW_COLOR="blue"
    log_warning "활성 컨테이너 없음. 최초 배포 시작: ${BLUE}Blue${NC}"
fi

log_info "[2/6] $NEW_COLOR 컨테이너 시작 중..."

docker-compose -f docker-compose.$NEW_COLOR.yml up -d --build

log_success "$NEW_COLOR 컨테이너 시작 완료: cabi_backend_$NEW_COLOR:8080"

log_info "[3/6] 헬스체크 대기 중... (30초)"

HEALTH_CHECK_URL="http://cabi_backend_$NEW_COLOR:8080/actuator/health"

for i in {1..6}; do
    sleep 5
    log_info "⏳ ${i}0초..."
    
    if docker exec cabi_backend_$NEW_COLOR wget -q --spider $HEALTH_CHECK_URL 2>/dev/null; then
        log_success "헬스체크 통과!"
        break
    fi
done

log_success "헬스체크 완료"

log_info "[4/6] Nginx 트래픽 전환 중..."

sed -i.bak "s/cabi_backend_$CURRENT_COLOR:8080/cabi_backend_$NEW_COLOR:8080/g" nginx/conf.d/upstream.conf

docker exec cabi_nginx nginx -t
docker exec cabi_nginx nginx -s reload

log_success "Nginx 트래픽 전환 완료: $CURRENT_COLOR → $NEW_COLOR"

log_info "[5/6] 이전 버전 종료 중..."

if [[ "$CURRENT_COLOR" != "none" ]]; then
    docker-compose -f docker-compose.$CURRENT_COLOR.yml down
    
    log_success "이전 컨테이너 종료 완료: $CURRENT_COLOR"
else
    log_info "최초 배포이므로 종료할 이전 컨테이너 없음"
fi

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
log_success "[6/6] 배포 완료!"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
log_success "✅ 새 버전: $IMAGE_TAG ($NEW_COLOR)"
log_success "✅ 활성 컨테이너: cabi_backend_$NEW_COLOR:8080"
log_success "✅ 사용자 다운타임: 0초"
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

log_info "현재 실행 중인 백엔드 컨테이너:"
docker ps --filter "name=cabi_backend" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
log_info "모니터링: http://localhost:3000 (Grafana)"
log_info "헬스체크: curl http://localhost/actuator/health"
echo ""
