"""Small read-only HTTP JSON catalog API, deploy as a SEPARATE Render service.

The app never proxies media. POST /api/launch stays on the existing PS4 server.
"""
import json
import os
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

try:
    from . import catalog
except ImportError:
    import catalog

MAX_QUERY = 1024
PROBE_INTERVAL = 90
_probe_lock = threading.Lock()
_last_probe = 0.0


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def probe_source(section: str) -> dict:
    """Read-only, bounded inspection of the configured public catalog page.

    This is an independent probe, not the exact request sent by HDrezka.
    It never prints HTML, cookies, query strings, tokens, or response bodies.
    """
    section = section if section in ('films', 'series', 'cartoons', 'anime') else 'films'
    origin = catalog.source_origin()
    url = origin + '/' + ('series' if section == 'series' else section) + '/'
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/119.0',
        'Accept': 'text/html,application/xhtml+xml',
    })
    status = None
    content_type = ''
    body = b''
    try:
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=8) as response:
            status = response.status
            content_type = response.headers.get('Content-Type', '').split(';', 1)[0][:50]
            body = response.read(65536)
    except urllib.error.HTTPError as exc:
        status = exc.code
        content_type = exc.headers.get('Content-Type', '').split(';', 1)[0][:50] if exc.headers else ''
        body = exc.read(65536)
        exc.close()
    except (OSError, urllib.error.URLError):
        return {'section': section, 'host': urlparse(origin).hostname,
                'status': 'none', 'content_type': 'none', 'bytes': 0, 'result': 'NETWORK_ERROR'}

    lower = body.lower()
    challenges = (b'cf-chl', b'challenge-platform', b'captcha', b'just a moment',
                  b'checking your browser', b'ddos-guard', b'attention required')
    if any(marker in lower for marker in challenges):
        result = 'CHALLENGE_SUSPECTED'
    elif status in (403, 429):
        result = 'HTTP_RESTRICTED'
    elif status is not None and 300 <= status <= 399:
        result = 'REDIRECT'
    elif status != 200:
        result = 'HTTP_OTHER'
    elif b'b-content__inline_item' in lower:
        result = 'CATALOG_MARKUP_FOUND'
    elif b'<html' in lower:
        result = 'HTML_WITHOUT_CATALOG_MARKUP'
    else:
        result = 'UNEXPECTED_CONTENT'
    return {'section': section, 'host': urlparse(origin).hostname, 'status': status,
            'content_type': content_type, 'bytes': len(body), 'result': result}


def maybe_probe_source(section: str) -> None:
    """Rate limit diagnostics so repeated user reloads do not flood the site."""
    global _last_probe
    with _probe_lock:
        now = time.monotonic()
        if now - _last_probe < PROBE_INTERVAL:
            return
        _last_probe = now
    try:
        result = probe_source(section)
        print('Catalog source probe: ' + ' '.join(f'{k}={v}' for k, v in result.items()), flush=True)
    except (ValueError, OSError) as exc:
        print('Catalog source probe: result=CONFIG_OR_NETWORK_ERROR type=' + type(exc).__name__, flush=True)



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
    if name == 'EmptyPage':
        return 'EMPTY_CATALOG', ('Библиотека не нашла карточки на странице источника. '
                                 'Возможны страница проверки, другое зеркало или изменение разметки.'), status
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
    server_version = 'PS4Catalog/0.3.2'

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
        section = 'n/a'
        try:
            page = int(get('page', '1'))
            if not 1 <= page <= 100:
                raise ValueError('Страница вне допустимого диапазона')
            if path.path in ('/', '/health'):
                return self.respond(200, {'ok': True, 'service': 'PS4 catalog', 'version': '0.3'})
            if path.path == '/api/home':
                section = 'films'
                films = catalog.navigation('films', 1)['items']
                section = 'series'
                serials = catalog.navigation('series', 1)['items']
                return self.respond(200, {'new': (films[:12] + serials[:8]), 'films': films[:18], 'series': serials[:18]})
            if path.path == '/api/catalog':
                section = get('category', 'films')
                return self.respond(200, catalog.navigation(section, page))
            if path.path == '/api/search':
                section = 'search'
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
            print(f'Catalog source diagnostic: route={path.path} section={section} category={category} '
                  f'exception={type(exc).__name__} upstream_http={status or "unknown"}', flush=True)
            if category == 'EMPTY_CATALOG':
                maybe_probe_source(section)
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
