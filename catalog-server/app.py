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


class Handler(BaseHTTPRequestHandler):
    server_version = 'PS4Catalog/0.3'

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
            # Upstream errors do not leak source URLs, internal HTTP responses or tracebacks.
            print(f'Catalog upstream failed: {type(exc).__name__}', flush=True)
            return self.respond(502, {'error': 'Источник временно недоступен. Проверь зеркало/сеть или попробуй позже.'})

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
