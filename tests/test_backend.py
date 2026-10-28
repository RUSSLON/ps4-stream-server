import http.client
import importlib.util
import os
import sys
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).parents[1]
sys.path.insert(0, str(ROOT / 'catalog-server'))
import app
import catalog

URL = 'https://rezka.ag/films/fantasy/18-test-2022.html'
class FakePoster:
    url=URL
    title='Тестовый фильм'
    year=2022
    genre='Фантастика'
    thumbnail='https://example.com/poster.jpg'
    country='Тестландия'
    rating='8.2'
class FakeNav:
    def __init__(self): self.number=1
    def page(self,n): self.number=n;return self
    def get(self): return [FakePoster()]
class FakePlayer:
    translators_dict={'Тест':15}
    def set_translate(self,n): assert n==15
    def get_video_url(self,q): return 'https://example.com/video.mp4' if q=='720p' else None
class FakeFilm:
    url=URL
    title='Тестовый фильм'
    description='Описание тестового фильма'
    player=FakePlayer()
    recommendations=[FakePoster()]
    trailer=None
class FakeClient:
    def films(self):return FakeNav()
    def serials(self):return FakeNav()
    def cartoons(self):return FakeNav()
    def anime(self):return FakeNav()
    def search(self,q):return FakeNav()
    def get(self,url):assert url==URL;return FakeFilm()

class CatalogTests(unittest.TestCase):
    def setUp(self):
        catalog._CACHE.clear()
        self.patch=patch.object(catalog,'_client',FakeClient)
        self.patch.start()
    def tearDown(self):self.patch.stop()
    def test_navigation_and_search(self):
        self.assertEqual(catalog.navigation('films',1)['items'][0]['title'],'Тестовый фильм')
        self.assertEqual(catalog.navigation('series',2)['page'],2)
        self.assertEqual(catalog.search('Тестовый')['items'][0]['year'],'2022')
    def test_details_and_stream(self):
        d=catalog.details(URL)
        self.assertEqual(d['voices'][0],{'name':'Тест','id':'15'})
        self.assertEqual(catalog.stream(URL,'720p','Тест')['url'],'https://example.com/video.mp4')
        with self.assertRaises(ValueError):catalog.stream(URL,'2160p','')
        with self.assertRaises(ValueError):catalog.stream(URL,'720p','Fake voice')
    def test_ssrf(self):
        for u in ['http://rezka.ag/film.html','https://rezka.ag.evil.com/film.html',
                  'https://127.0.0.1/private.html','https://rezka.ag@evil.test/a.html',
                  'https://rezka.ag:9443/test.html','https://rezka.ag/movie']:
            self.assertFalse(catalog.valid_film_url(u),u)
        self.assertTrue(catalog.valid_film_url(URL))
    def test_invalid_page(self):
        with self.assertRaises(ValueError):catalog.navigation('films',500)

class APITests(unittest.TestCase):
    def setUp(self):
        self.patch=patch.object(catalog,'_client',FakeClient)
        self.patch.start()
        catalog._CACHE.clear()
        self.server=app.ThreadingHTTPServer(('127.0.0.1',0),app.Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True)
        self.thread.start()
        self.old=os.environ.get('CATALOG_API_KEY')
        os.environ['CATALOG_API_KEY']='my_secure_cat_key_1234'
    def tearDown(self):
        if self.old is None:os.environ.pop('CATALOG_API_KEY',None)
        else:os.environ['CATALOG_API_KEY']=self.old
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=3)
        self.patch.stop()
    def get(self,path,key=''):
        c=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=4)
        headers={'Authorization':'Bearer '+key} if key else {}
        c.request('GET',path,headers=headers)
        r=c.getresponse();code=r.status;data=r.read().decode('utf-8');c.close();return code,data
    def test_search_and_home(self):
        code,body=self.get('/api/home');self.assertEqual(code,200);self.assertIn('Тестовый фильм',body)
        code,body=self.get('/api/search?q=test');self.assertEqual(code,200);self.assertIn('Тестовый фильм',body)
    def test_stream_requires_bearer(self):
        from urllib.parse import quote
        path='/api/stream?url='+quote(URL,safe='')+'&quality=720p&voice='+quote('Тест')
        code,_=self.get(path);self.assertEqual(code,401)
        code,_=self.get(path,'wrong_key');self.assertEqual(code,401)
        code,body=self.get(path,'my_secure_cat_key_1234')
        self.assertEqual(code,200);self.assertIn('video.mp4',body)
    def test_bad_url(self):
        code,body=self.get('/api/detail?url=https%3A%2F%2F127.0.0.1%2Fabc.html')
        self.assertEqual(code,400)
    def test_health(self):self.assertEqual(self.get('/health')[0],200)

if __name__=='__main__':unittest.main()
