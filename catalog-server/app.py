"""Small read-only HTTP JSON catalog API, deploy as a SEPARATE Render service.

The app never proxies media. POST /api/launch stays on the existing PS4 server.
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

try:
    from . import catalog
except ImportError:
    import catalog

MAX_QUERY = 1024


def classify_upstream_error(exc):
    """Return a safe, non-sensitive hint; never include upstream exception text in JSON.

    This distinguishes common HTTP blocks from library/parser failures but does
    NOT claim to detect a real CAPTCHA with certainty.
    """
    status = None
    cursor = exc
    for _ in range(4):
        if cursor is None:
            break
        candidate = getattr(cursor, 'status_code', None)
        if candidate is None:
            candidate = getattr(cursor, 'code', None)
        if candidate is None:
            response = getattr(cursor, 'response', None)
            candidate = getattr(response, 'status_code', None)
        if isinstance(candidate, int) and 100 <= candidate <= 599:
            status = candidate
            break
        cursor = getattr(cursor, '__cause__', None)

    name = type(exc).__name__
    # Exception messages can contain signed URLs or cookies. Match safe markers
    # only; never print the message or the full request path (query has URL).
    message = str(exc).lower()
    if status == 403 or '403 forbidden' in message or 'http 403' in message:
        return 'BLOCKED_403', ('HDrezka отклонила запрос сервера (HTTP 403). '
                               'Возможна проверка Cloudflare/капча или блокировка IP Render.'), status
    if status == 429 or 'http 429' in message:
        return 'RATE_LIMIT_429', ('Источник ограничил частоту запросов (HTTP 429). '
                                  'Попробуй позже; не выполняй частые повторы.'), status
    if any(marker in message for marker in ('captcha', 'cloudflare', 'just a moment', 'checking your browser', 'challenge-platform')):
        return 'CHALLENGE_SUSPECTED', ('Библиотека обнаружила признаки проверочной страницы. '
                                       'Возможна капча/Cloudflare.'), status
    if status in (404, 410):
        return 'UPSTREAM_NOT_FOUND', ('HDrezka вернула HTTP ' + str(status) +
                                       '. Возможно, устарел адрес или зеркало.'), status
    if status is not None:
        return 'UPSTREAM_HTTP', ('HDrezka вернула HTTP ' + str(status) +
                                 '. Проверь доступность сайта для Render.'), status
    if isinstance(exc, (TimeoutError, ConnectionError)) or 'timeout' in name.lower() or 'connection' in name.lower():
        return 'NETWORK_ERROR', 'Не удалось установить соединение с источником или ожидание истекло.', None
    if isinstance(exc, (AttributeError, IndexError, KeyError, TypeError)):
        return 'PARSER_ERROR', ('Python-библиотека не смогла разобрать ответ сайта. '
                                'Возможны изменение разметки или проверочная страница.'), None
    return 'UNKNOWN_UPSTREAM', ('Ошибка обращения к источнику. Открой Logs сервиса каталога '
                                'в Render и проверь код диагностики.'), None


class Handler(BaseHTTPRequestHandler):
    server_version = 'PS4Catalog/0.3.1'

    def log_request(self, code='-', size='-'):
        # Request URLs for /api/stream contain signed source URLs; never log them.
        safe_path = urlsplit(self.path).path
        self.log_message('%s %s HTTP %s', self.command, safe_path, code)

    def do_GET(self):
        if len(self.path) > 2500:
            return self.respond(414, {'error': 'Слишком длинный запрос'})
        path = urlsplit(self.path)
        q = parse_qs(path.query, keep_blank_values=False)
        get = lambda n, default='': q.get(n, [default])[0]
        try:
            page = int(get('page', '1'))
            if not 1 <= page <= 100:
                raise ValueError('Страница вне допустимого диапазона')
            if path.path in ('/', '/health'):
                return self.respond(200, {'ok': True, 'service': 'PS4 catalog', 'version': '0.3'})
            if path.path == '/api/home':
                films = catalog.navigation('films', 1)['items']
                serials = catalog.navigation('series', 1)['items']
                return self.respond(200, {'new': (films[:12] + serials[:8]), 'films': films[:18], 'series': serials[:18]})
            if path.path == '/api/catalog':
                return self.respond(200, catalog.navigation(get('category', 'films'), page))
            if path.path == '/api/search':
                return self.respond(200, catalog.search(get('q', '')[:MAX_QUERY], page))
            if path.path == '/api/detail':
                return self.respond(200, catalog.details(get('url')))
            if path.path == '/api/stream':
                secret = os.environ.get('CATALOG_API_KEY', '')
                if len(secret) < 12:
                    return self.respond(503, {'error': 'Настрой CATALOG_API_KEY (не менее 12 символов) в Render'})
                import hmac
                if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + secret):
                    return self.respond(401, {'error': 'Нет доступа к получению видеоссылок'})
                return self.respond(200, catalog.stream(get('url'), get('quality', '720p'), get('voice')))
            return self.respond(404, {'error': 'Маршрут не найден'})
        except ValueError as exc:
            return self.respond(400, {'error': str(exc)})
        except Exception as exc:
            # Do not leak source URLs, signed tokens, cookies or exception details.
            category, hint, status = classify_upstream_error(exc)
            print(f'Catalog source diagnostic: route={path.path} category={category} '
                  f'exception={type(exc).__name__} upstream_http={status or "unknown"}', flush=True)
            return self.respond(502, {'error': hint, 'diagnostic': category})

    def do_POST(self):
        self.respond(405, {'error': 'Only GET is available'})

    def respond(self, code, result):
        data = json.dumps(result, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == '__main__':
    port = int(os.environ.get('PORT', '8000'))
    print(f'Catalog API on 0.0.0.0:{port}', flush=True)
    ThreadingHTTPServer(('0.0.0.0', port), Handler).serve_forever()
