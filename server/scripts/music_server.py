#!/usr/bin/env python3
"""Simple static file server for serving transcoded MP3 files to AllMusic clients."""
import http.server
import os

PORT = 8090
#如果你没有修改bili.json里的cacheDir,这里应该的DIR填你与你服务端同级的music_cache文件夹的路径，否则跟cacheDir填成一样
DIR = '/home/minecraft/music_cache'

os.chdir(DIR)
handler = http.server.SimpleHTTPRequestHandler
httpd = http.server.ThreadingHTTPServer(('0.0.0.0', PORT), handler)
print('Music HTTP server on port %d, serving %s' % (PORT, DIR))
httpd.serve_forever()
