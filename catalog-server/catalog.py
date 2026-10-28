"""HDrezka catalog adapter. No hidden media proxies, credential storage or DRM handling.

The third-party HDrezka project is an unofficial website client. Only documented
methods are used for navigation and basic playback; unsupported features are
returned explicitly as empty values rather than fabricated data.
"""
from __future__ import annotations

import re
import threading
import time
from dataclasses import dataclass
from urllib.parse import urlparse
from typing import Any

CATEGORIES = {'films': ('films',), 'series': ('serials', 'series'), 'cartoons': ('cartoons',), 'anime': ('anime',)}
CACHE_TTL = 180
_CACHE: dict[str, tuple[float, Any]] = {}
_LOCK = threading.Lock()


def _client():
    from HDrezka import HDrezka
    return HDrezka()


def _text(obj: Any, *names: str) -> str:
    for name in names:
        try:
            value = getattr(obj, name, None)
            if callable(value) or value is None:
                continue
            if isinstance(value, (str, int, float)):
                return str(value).strip()
        except Exception:
            continue
    return ''


def _names(value: Any) -> list[str]:
    if not value:
        return []
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return [str(k) for k in list(value)[:35]]
    if isinstance(value, (list, tuple)):
        return [(_text(x, 'name', 'title') or str(x))[:90] for x in value[:35]]
    return []


def valid_film_url(url: str) -> bool:
    """Never ask the crawler to retrieve arbitrary URL: SSRF protection."""
    try:
        p = urlparse(url)
        host = (p.hostname or '').lower()
        return (p.scheme == 'https' and p.port in (None,443) and p.username is None
                and host in {'hdrezka.ag', 'rezka.ag', 'rezka.fi', 'rezka-tv.org', 'hdrezka-fly.net'}
                and p.path.endswith('.html') and len(url) <= 700)
    except ValueError:
        return False


def image_url(obj: Any) -> str:
    value = _text(obj, 'thumbnail', 'poster', 'image', 'poster_url', 'img')
    return value if value.startswith('https://') else ''


def poster_data(p: Any) -> dict:
    return dict(
        url=_text(p, 'url', 'link'),
        title=_text(p, 'title', 'name'),
        originalTitle=_text(p, 'original_title'),
        poster=image_url(p),
        year=_text(p, 'year', 'release_year'),
        country=_text(p, 'country'),
        rating=_text(p, 'rating'),
        genre=_text(p, 'genre'),
        category=_text(p, 'entity'),
    )


def _cached(key: str, fn):
    with _LOCK:
        value = _CACHE.get(key)
        if value and time.time() - value[0] < CACHE_TTL:
            return value[1]
    result = fn()
    with _LOCK:
        if len(_CACHE) > 100:
            _CACHE.clear()
        _CACHE[key] = (time.time(), result)
    return result


def navigation(category: str, page: int = 1) -> dict:
    if category not in CATEGORIES:
        raise ValueError('Неизвестная категория')
    if not 1 <= page <= 100:
        raise ValueError('Номер страницы вне диапазона')
    def perform():
        client = _client()
        method = next((getattr(client, name) for name in CATEGORIES[category] if callable(getattr(client, name, None))), None)
        if method is None:
            raise RuntimeError('Категория не поддерживается текущей библиотекой')
        nav = method()
        if page > 1:
            nav = nav.page(page)
        posters = [poster_data(x) for x in nav.get()]
        posters = [p for p in posters if valid_film_url(p['url'])]
        return {'items': posters, 'page': page, 'category': category, 'hasMore': bool(posters)}
    return _cached(f'category:{category}:{page}', perform)


def search(query: str, page: int = 1) -> dict:
    query = query.strip()[:100]
    if len(query) < 2:
        return {'items': [], 'page': page, 'hasMore': False}
    if not 1 <= page <= 100:
        raise ValueError('Номер страницы вне диапазона')
    def perform():
        nav = _client().search(query)
        if page > 1:
            nav = nav.page(page)
        items = [poster_data(x) for x in nav.get()]
        items = [p for p in items if valid_film_url(p['url'])]
        return {'items': items, 'page': page, 'query': query, 'hasMore': bool(items)}
    return _cached(f'search:{query.casefold()}:{page}', perform)


def details(url: str) -> dict:
    if not valid_film_url(url):
        raise ValueError('Недопустимая ссылка на фильм')
    def perform():
        film = _client().get(url)
        player = getattr(film, 'player', None)
        translators = getattr(player, 'translators_dict', None) or {}
        if not isinstance(translators, dict):
            translators = {}
        # The library's player types differ. Only publish real metadata returned.
        ratings = []
        info = getattr(film, 'info_table', None)
        for x in (getattr(info, 'rates', None) or []):
            ratings.append(_text(x, 'value', 'rate') or str(x)[:90])
        trailer_url = ''
        trailer = getattr(film, 'trailer', None)
        if trailer:
            try:
                trailer_url = _text(trailer.extract_content(), 'trailer_url')
            except Exception:
                pass
        return {
            'url': url, 'title': _text(film, 'title', 'name'),
            'originalTitle': _text(film, 'original_title'),
            'poster': image_url(film),
            'description': _text(film, 'description'),
            'year': _text(film, 'year', 'release_year'),
            'country': _text(film, 'country'),
            'genre': _text(film, 'genre'),
            'director': _text(film, 'director'),
            'actors': _names(getattr(film, 'actors', None)),
            'rating': ' · '.join(ratings[:3]),
            'trailerUrl': trailer_url if trailer_url.startswith('https://') else '',
            'voices': [{'name': str(n), 'id': str(i)} for n,i in translators.items()],
            'serial': type(player).__name__.lower().startswith('serial'),
            'recommended': [poster_data(x) for x in (getattr(film, 'recommendations', None) or [])[:12]],
        }
    return _cached(f'detail:{url}', perform)


def stream(url: str, quality: str, voice: str = '') -> dict:
    if not valid_film_url(url):
        raise ValueError('Недопустимая ссылка')
    if quality not in {'1080p Ultra','1080p','720p','480p','360p'}:
        raise ValueError('Неподдерживаемое качество')
    film = _client().get(url)
    player = getattr(film, 'player', None)
    if player is None:
        raise ValueError('У этого материала нет видеоплеера')
    voices = getattr(player, 'translators_dict', None) or {}
    if voice:
        if voice not in voices:
            raise ValueError('Выбранная озвучка больше недоступна')
        player.set_translate(voices[voice])
    if type(player).__name__.lower().startswith('serial'):
        # Episode selection is handled by the existing site-integrated player
        # until library season/episode semantics can be verified live.
        raise ValueError('Для выбора серии открой совместимый режим сайта')
    direct = player.get_video_url(quality)
    if not isinstance(direct, str) or not direct.startswith('https://'):
        raise ValueError('Это качество не доступно у источника')
    return {'title': _text(film, 'title', 'name'), 'quality': quality, 'url': direct,
            'subtitles': [], 'pageUrl': url}
